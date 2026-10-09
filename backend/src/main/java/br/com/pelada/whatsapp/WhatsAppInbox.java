package br.com.pelada.whatsapp;

import static br.com.pelada.whatsapp.WhatsAppOutbox.*;

import br.com.pelada.domain.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WhatsAppInbox {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final WhatsAppIntegration config;
  private final WhatsAppOutbox outbox;

  public WhatsAppInbox(
    JdbcTemplate jdbc,
    Clock clock,
    WhatsAppIntegration config,
    WhatsAppOutbox outbox
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.config = config;
    this.outbox = outbox;
  }

  public void accept(WhatsApp.Incoming message) {
    if (
      !config.available() ||
      !config.permits(message.from()) ||
      message.timestamp() < clock.instant().getEpochSecond() - 600
    ) return;
    var contacts = jdbc.queryForList(
      "SELECT * FROM whatsapp_contacts WHERE phone=? AND stopped_at IS NULL AND verified_at<=?",
      "+" + message.from(),
      time(Instant.ofEpochSecond(message.timestamp()).plusSeconds(1))
    );
    if (contacts.isEmpty()) return;
    var contact = contacts.getFirst();
    UUID user = (UUID) contact.get("player_id");
    if (groups(user).isEmpty()) return;
    String bucket =
      "inbox:" + user + ":" + clock.instant().getEpochSecond() / 3600;
    if (
      jdbc
        .queryForList(
          "INSERT INTO whatsapp_worker_limits VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET requests=whatsapp_worker_limits.requests+1 WHERE whatsapp_worker_limits.requests<30 RETURNING requests",
          Integer.class,
          bucket,
          time(clock.instant().plusSeconds(7200))
        )
        .isEmpty()
    ) return;
    jdbc.update(
      "INSERT INTO whatsapp_inbox(id,event_hash,player_id,verified_at,message_at,text,button,reply_to,created_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(event_hash) DO NOTHING",
      UUID.randomUUID(),
      WhatsApp.hash(message.id()),
      user,
      contact.get("verified_at"),
      time(Instant.ofEpochSecond(message.timestamp())),
      message.text(),
      message.button(),
      message.replyTo(),
      time(clock.instant())
    );
    jdbc.update(
      """
      INSERT INTO whatsapp_conversations(player_id,verified_at,last_received_at,expires_at) VALUES(?,?,?,?)
      ON CONFLICT(player_id) DO UPDATE SET last_received_at=GREATEST(whatsapp_conversations.last_received_at,EXCLUDED.last_received_at),
      game_id=CASE WHEN whatsapp_conversations.verified_at=EXCLUDED.verified_at THEN whatsapp_conversations.game_id ELSE NULL END,
      club_id=CASE WHEN whatsapp_conversations.verified_at=EXCLUDED.verified_at THEN whatsapp_conversations.club_id ELSE NULL END,
      proposal_id=CASE WHEN whatsapp_conversations.verified_at=EXCLUDED.verified_at THEN whatsapp_conversations.proposal_id ELSE NULL END,
      verified_at=EXCLUDED.verified_at,expires_at=EXCLUDED.expires_at
      """,
      user,
      contact.get("verified_at"),
      time(Instant.ofEpochSecond(message.timestamp())),
      time(clock.instant().plusSeconds(1800))
    );
  }

  public record Job(
    UUID id,
    UUID leaseId,
    String text,
    String button,
    List<Map<String, Object>> groups,
    List<Map<String, Object>> games,
    UUID contextGameId,
    boolean proposalPending,
    UUID proposalClubId
  ) {}

  public List<Job> claim() {
    if (!config.available()) return List.of();
    outbox.lock();
    cleanup();
    var jobs = new ArrayList<Job>();
    for (var row : jdbc.queryForList(
      """
      SELECT i.* FROM whatsapp_inbox i WHERE i.state='PENDING' AND i.message_at>? AND i.attempts<3
      AND NOT EXISTS(SELECT 1 FROM whatsapp_inbox p WHERE p.player_id=i.player_id AND p.state='PROCESSING')
      AND NOT EXISTS(SELECT 1 FROM whatsapp_inbox p WHERE p.player_id=i.player_id AND p.state='PENDING' AND (p.message_at,p.created_at,p.id)<(i.message_at,i.created_at,i.id))
      ORDER BY i.message_at,i.created_at,i.id LIMIT 2 FOR UPDATE OF i SKIP LOCKED
      """,
      time(clock.instant().minusSeconds(600))
    )) {
      if (!current(row)) {
        abandon((UUID) row.get("id"));
        continue;
      }
      UUID lease = UUID.randomUUID();
      jdbc.update(
        "UPDATE whatsapp_inbox SET state='PROCESSING',lease_id=?,lease_until=?,attempts=attempts+1 WHERE id=?",
        lease,
        time(clock.instant().plusSeconds(300)),
        row.get("id")
      );
      var groups = groups((UUID) row.get("player_id"));
      var games = games((UUID) row.get("player_id"));
      var c = conversation((UUID) row.get("player_id"));
      UUID context = contextGame(row, games);
      jobs.add(
        new Job(
          (UUID) row.get("id"),
          lease,
          (String) row.get("text"),
          (String) row.get("button"),
          groups,
          games,
          context,
          c.get("proposal_id") != null,
          (UUID) c.get("club_id")
        )
      );
    }
    return jobs;
  }

  public List<Map<String, Object>> groups(UUID user) {
    return jdbc.queryForList(
      "SELECT c.id,c.name,(c.owner_id=?) owner FROM clubs c JOIN members m ON m.club_id=c.id JOIN whatsapp_group_settings s ON s.club_id=c.id WHERE m.player_id=? AND s.enabled AND NOT c.demo ORDER BY c.name LIMIT 30",
      user,
      user
    );
  }

  public List<Map<String, Object>> games(UUID user) {
    return jdbc.queryForList(
      """
      SELECT g.id,g.club_id,c.name group_name,g.title,g.location,g.starts_at,c.time_zone,
      coalesce(p.status,'NONE') attendance FROM games g JOIN clubs c ON c.id=g.club_id
      JOIN members m ON m.club_id=c.id JOIN whatsapp_group_settings s ON s.club_id=c.id
      LEFT JOIN participations p ON p.game_id=g.id AND p.player_id=m.player_id
      WHERE m.player_id=? AND s.enabled AND NOT c.demo AND NOT g.cancelled AND g.match_started_at IS NULL
      AND g.starts_at>? AND g.starts_at<? ORDER BY g.starts_at,g.id LIMIT 30
      """,
      user,
      time(clock.instant()),
      time(clock.instant().plus(Duration.ofDays(14)))
    );
  }

  public UUID contextGame(
    Map<String, Object> row,
    List<Map<String, Object>> games
  ) {
    if (row.get("reply_to") != null) {
      var messages = jdbc.queryForList(
        "SELECT game_id,revision FROM whatsapp_outbox WHERE provider_id=? AND player_id=? AND verified_at=?",
        row.get("reply_to"),
        row.get("player_id"),
        row.get("verified_at")
      );
      if (!messages.isEmpty() && messages.getFirst().get("game_id") != null) {
        UUID id = (UUID) messages.getFirst().get("game_id");
        if (
          games.stream().anyMatch(g -> id.equals(g.get("id"))) &&
          Objects.equals(
            messages.getFirst().get("revision"),
            outbox.revision(outbox.game(id))
          )
        ) return id;
        return null;
      }
      return null;
    }
    // Without an explicit reply, a bare yes/no applies only when one game is possible.
    return games.size() == 1 ? (UUID) games.getFirst().get("id") : null;
  }

  public Map<String, Object> conversation(UUID user) {
    return jdbc
      .queryForList(
        "SELECT * FROM whatsapp_conversations WHERE player_id=? AND expires_at>?",
        user,
        time(clock.instant())
      )
      .stream()
      .findFirst()
      .orElse(Map.of());
  }

  public boolean current(Map<String, Object> row) {
    return (
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_contacts WHERE player_id=? AND verified_at=? AND phone IS NOT NULL AND stopped_at IS NULL",
        Integer.class,
        row.get("player_id"),
        row.get("verified_at")
      ) == 1
    );
  }

  public Map<String, Object> leased(UUID id, UUID lease) {
    var rows = jdbc.queryForList(
      "SELECT * FROM whatsapp_inbox WHERE id=? FOR UPDATE",
      id
    );
    if (rows.isEmpty()) throw ApiException.notFound();
    var row = rows.getFirst();
    if (
      !lease.equals(row.get("lease_id")) ||
      !row.get("state").equals("PROCESSING") ||
      !instant(row.get("lease_until")).isAfter(clock.instant()) ||
      !current(row)
    ) throw ApiException.conflict("Conversa expirada ou número desvinculado.");
    return row;
  }

  public void finish(UUID id, UUID lease) {
    outbox.lock();
    if (
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_inbox WHERE id=? AND lease_id=? AND state='DONE'",
        Integer.class,
        id,
        lease
      ) == 1
    ) return;
    var row = leased(id, lease);
    if (row.get("result") == null) throw ApiException.conflict(
      "Execute uma ação ou peça esclarecimento antes de concluir."
    );
    jdbc.update(
      "UPDATE whatsapp_inbox SET state='DONE',text=NULL,button=NULL,reply_to=NULL WHERE id=?",
      id
    );
  }

  private void abandon(UUID id) {
    jdbc.update(
      "UPDATE whatsapp_inbox SET state='EXPIRED',text=NULL,button=NULL,reply_to=NULL,result=NULL WHERE id=?",
      id
    );
  }

  public void cleanup() {
    jdbc.update(
      "UPDATE whatsapp_inbox SET state='PENDING',lease_id=NULL WHERE state='PROCESSING' AND lease_until<=?",
      time(clock.instant())
    );
    jdbc.update(
      "UPDATE whatsapp_inbox SET state='EXPIRED',text=NULL,button=NULL,reply_to=NULL,result=NULL WHERE state IN ('PENDING','PROCESSING') AND (message_at<=? OR (attempts>=3 AND state='PENDING'))",
      time(clock.instant().minusSeconds(600))
    );
    jdbc.update(
      "DELETE FROM whatsapp_inbox WHERE created_at<?",
      time(clock.instant().minus(Duration.ofDays(7)))
    );
    jdbc.update(
      "DELETE FROM whatsapp_conversations WHERE last_received_at<?",
      time(clock.instant().minus(Duration.ofDays(1)))
    );
  }
}
