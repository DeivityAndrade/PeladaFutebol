package br.com.pelada.whatsapp;

import br.com.pelada.domain.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class WhatsAppOutbox {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final WhatsAppIntegration config;
  private final String phoneId;
  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  public WhatsAppOutbox(
    JdbcTemplate jdbc,
    Clock clock,
    WhatsAppIntegration config,
    @Value("${app.whatsapp.phone-id:}") String phoneId
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.config = config;
    this.phoneId = phoneId;
  }

  public static Timestamp time(Instant i) {
    return Timestamp.from(i);
  }

  public static Instant instant(Object o) {
    return o == null ? null : ((Timestamp) o).toInstant();
  }

  public void lock() {
    jdbc.execute("SELECT pg_advisory_xact_lock(742109812)");
  }

  public record Settings(
    boolean available,
    boolean deliveryAvailable,
    boolean repliesOnly,
    boolean enabled,
    int reminderMinutes,
    Map<String, Long> states,
    List<Map<String, Object>> responses
  ) {}

  public record Choice(boolean enabled, int reminderMinutes) {}

  public record Lease(UUID id, UUID leaseId) {}

  public record Dispatch(
    boolean send,
    String url,
    Map<String, Object> payload
  ) {}

  public record Receipt(String state, String providerId, String errorCode) {}

  public Settings settings(UUID user, UUID club) {
    owner(user, club);
    var s = jdbc.queryForList(
      "SELECT * FROM whatsapp_group_settings WHERE club_id=?",
      club
    );
    var counts = new LinkedHashMap<String, Long>();
    jdbc
      .queryForList(
        "SELECT state,count(*) n FROM whatsapp_outbox WHERE club_id=? AND created_at>? GROUP BY state",
        club,
        time(clock.instant().minus(Duration.ofDays(7)))
      )
      .forEach(r ->
        counts.put((String) r.get("state"), ((Number) r.get("n")).longValue())
      );
    var replies = jdbc.queryForList(
      """
      SELECT r.game_id,r.player_id,g.title,g.starts_at,c.time_zone,p.name,r.response,r.message_at,coalesce(a.status,'NO') attendance
      FROM whatsapp_responses r JOIN games g ON g.id=r.game_id JOIN players p ON p.id=r.player_id
      JOIN clubs c ON c.id=g.club_id
      LEFT JOIN participations a ON a.game_id=r.game_id AND a.player_id=r.player_id
      WHERE g.club_id=? AND g.starts_at>? ORDER BY g.starts_at,p.name LIMIT 100
      """,
      club,
      time(clock.instant().minus(Duration.ofDays(1)))
    );
    return new Settings(
      config.available(),
      config.deliveryAvailable(),
      config.repliesOnly,
      !s.isEmpty() && Boolean.TRUE.equals(s.getFirst().get("enabled")),
      s.isEmpty()
        ? 120
        : ((Number) s.getFirst().get("reminder_minutes")).intValue(),
      counts,
      replies
    );
  }

  private void owner(UUID user, UUID club) {
    if (
      jdbc.queryForObject(
        "SELECT count(*) FROM clubs WHERE id=? AND owner_id=? AND NOT demo",
        Integer.class,
        club,
        user
      ) != 1
    ) throw ApiException.forbidden();
  }

  public Settings choose(UUID user, UUID club, Choice choice) {
    owner(user, club);
    lock();
    if (
      choice.reminderMinutes() < 15 || choice.reminderMinutes() > 1440
    ) throw new ApiException(
      400,
      "Escolha um lembrete entre 15 minutos e 24 horas antes."
    );
    if (choice.enabled() && !config.available()) throw new ApiException(
      503,
      "Configure a integração antes de ativar o grupo piloto."
    );
    jdbc.update(
      "INSERT INTO whatsapp_group_settings VALUES(?,?,?) ON CONFLICT(club_id) DO UPDATE SET enabled=EXCLUDED.enabled,reminder_minutes=EXCLUDED.reminder_minutes",
      club,
      choice.enabled(),
      choice.reminderMinutes()
    );
    jdbc
      .queryForList(
        "SELECT id FROM games WHERE club_id=? AND starts_at>?",
        club,
        time(clock.instant())
      )
      .forEach(r -> plan((UUID) r.get("id")));
    return settings(user, club);
  }

  public Map<String, Object> game(UUID id) {
    return jdbc
      .queryForList(
        """
        SELECT g.*,c.name club_name,c.time_zone,c.demo,coalesce(s.enabled,false) enabled,
        coalesce(s.reminder_minutes,120) reminder_minutes FROM games g JOIN clubs c ON c.id=g.club_id
        LEFT JOIN whatsapp_group_settings s ON s.club_id=c.id WHERE g.id=?
        """,
        id
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::notFound);
  }

  public String revision(Map<String, Object> g) {
    return WhatsApp.hash(
      g.get("id") +
        "|" +
        g.get("title") +
        "|" +
        g.get("location") +
        "|" +
        instant(g.get("starts_at")) +
        "|" +
        g.get("team_count") +
        "|" +
        g.get("team_size") +
        "|" +
        g.get("time_zone")
    );
  }

  public boolean active(Map<String, Object> g) {
    return (
      !Boolean.TRUE.equals(g.get("cancelled")) &&
      !Boolean.TRUE.equals(g.get("demo")) &&
      g.get("match_started_at") == null &&
      instant(g.get("starts_at")).isAfter(clock.instant())
    );
  }

  public String describe(Map<String, Object> g) {
    var date = instant(g.get("starts_at")).atZone(
      ZoneId.of((String) g.get("time_zone"))
    );
    return (
      g.get("club_name") +
      " — " +
      g.get("title") +
      "\n" +
      DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").format(date) +
      " (" +
      g.get("time_zone") +
      ")\n" +
      g.get("location")
    );
  }

  @EventListener
  public void changed(GameChanged event) {
    if (config.available()) plan(event.gameId());
  }

  public void plan(UUID id) {
    var g = game(id);
    String revision = revision(g);
    jdbc.update(
      "INSERT INTO whatsapp_game_revisions VALUES(?,?,?) ON CONFLICT(game_id) DO UPDATE SET revision=EXCLUDED.revision,updated_at=EXCLUDED.updated_at WHERE whatsapp_game_revisions.revision<>EXCLUDED.revision",
      id,
      revision,
      time(clock.instant())
    );
    boolean enabled =
      config.available() && Boolean.TRUE.equals(g.get("enabled")) && active(g);
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='CANCELLED',error_code='GAME_CHANGED',updated_at=? WHERE game_id=? AND state IN ('PENDING','CLAIMED') AND (?=false OR revision<>?)",
      time(clock.instant()),
      id,
      enabled,
      revision
    );
    if (!Boolean.TRUE.equals(g.get("enabled")) && active(g)) jdbc.update(
      "UPDATE whatsapp_outbox SET error_code='PAUSED' WHERE game_id=? AND state='CANCELLED' AND error_code='GAME_CHANGED' AND revision=?",
      id,
      revision
    );
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='CANCELLED',error_code='SCHEDULE_CHANGED',updated_at=? WHERE game_id=? AND kind='REMINDER' AND state IN ('PENDING','CLAIMED') AND send_after<>?",
      time(clock.instant()),
      id,
      time(
        instant(g.get("starts_at")).minusSeconds(
          ((Number) g.get("reminder_minutes")).longValue() * 60
        )
      )
    );
    if (!enabled) return;
    Instant start = instant(g.get("starts_at"));
    Instant invite = start.minus(Duration.ofDays(7));
    if (invite.isBefore(clock.instant())) invite = clock.instant();
    Instant reminder = start.minusSeconds(
      ((Number) g.get("reminder_minutes")).longValue() * 60
    );
    for (var m : jdbc.queryForList(
      """
      SELECT m.player_id,w.invitations,w.reminders,c.verified_at,c.phone FROM members m
      JOIN whatsapp_preferences w ON w.member_id=m.id JOIN whatsapp_contacts c ON c.player_id=m.player_id
      WHERE m.club_id=? AND c.phone IS NOT NULL AND c.stopped_at IS NULL
      """,
      g.get("club_id")
    )) {
      UUID player = (UUID) m.get("player_id");
      Instant verified = instant(m.get("verified_at"));
      if (!config.permits((String) m.get("phone"))) continue;
      if (Boolean.TRUE.equals(m.get("invitations"))) enqueue(
        player,
        (UUID) g.get("club_id"),
        id,
        verified,
        "INVITATION",
        revision,
        null,
        invite,
        start,
        "invite:" + id + ":" + player + ":" + revision + ":" + verified
      );
      if (
        Boolean.TRUE.equals(m.get("reminders")) &&
        reminder.plusSeconds(900).isAfter(clock.instant())
      ) enqueue(
        player,
        (UUID) g.get("club_id"),
        id,
        verified,
        "REMINDER",
        revision,
        null,
        reminder,
        reminder.plusSeconds(900),
        "remind:" +
          id +
          ":" +
          player +
          ":" +
          revision +
          ":" +
          verified +
          ":" +
          g.get("reminder_minutes")
      );
    }
  }

  public UUID action(
    UUID user,
    Instant verified,
    UUID game,
    UUID proposal,
    Long version,
    String revision,
    String action,
    Instant expires
  ) {
    UUID id = UUID.randomUUID();
    jdbc.update(
      "INSERT INTO whatsapp_actions VALUES(?,?,?,?,?,?,?,?,?)",
      id,
      user,
      time(verified),
      game,
      proposal,
      version,
      revision,
      action,
      time(expires)
    );
    return id;
  }

  public Map<String, Object> button(UUID token, String label) {
    return Map.of(
      "type",
      "reply",
      "reply",
      Map.of("id", "TD:" + token, "title", label)
    );
  }

  public void reply(
    UUID event,
    UUID user,
    Instant verified,
    String message,
    List<Map<String, Object>> buttons,
    UUID club,
    UUID game
  ) {
    Map<String, Object> body = buttons.isEmpty()
      ? Map.of(
          "type",
          "text",
          "text",
          Map.of("body", message, "preview_url", false)
        )
      : Map.of(
          "type",
          "interactive",
          "interactive",
          Map.of(
            "type",
            "button",
            "body",
            Map.of("text", message),
            "action",
            Map.of("buttons", buttons)
          )
        );
    enqueue(
      user,
      club,
      game,
      verified,
      "REPLY",
      game == null ? null : revision(game(game)),
      json.writeValueAsString(body),
      clock.instant(),
      clock.instant().plusSeconds(1800),
      "reply:" + event
    );
  }

  private void enqueue(
    UUID user,
    UUID club,
    UUID game,
    Instant verified,
    String kind,
    String revision,
    String body,
    Instant due,
    Instant expires,
    String key
  ) {
    jdbc.update(
      """
      INSERT INTO whatsapp_outbox(id,dedupe_key,player_id,club_id,game_id,verified_at,kind,revision,body,send_after,expires_at,created_at,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(dedupe_key) DO UPDATE SET state='PENDING',send_after=EXCLUDED.send_after,expires_at=EXCLUDED.expires_at,error_code=NULL
      WHERE whatsapp_outbox.state='CANCELLED' AND whatsapp_outbox.error_code='PAUSED'
      """,
      UUID.randomUUID(),
      key,
      user,
      club,
      game,
      time(verified),
      kind,
      revision,
      body,
      time(due),
      time(expires),
      time(clock.instant()),
      time(clock.instant())
    );
  }

  public List<Lease> claim() {
    if (!config.deliveryAvailable() || phoneId.isBlank()) return List.of();
    lock();
    reconcile();
    jdbc
      .queryForList(
        "SELECT g.id FROM games g JOIN whatsapp_group_settings s ON s.club_id=g.club_id WHERE s.enabled AND g.starts_at>? AND NOT g.cancelled ORDER BY g.starts_at LIMIT 500",
        time(clock.instant())
      )
      .forEach(r -> plan((UUID) r.get("id")));
    var leases = new ArrayList<Lease>();
    for (var row : jdbc.queryForList(
      "SELECT * FROM whatsapp_outbox WHERE state='PENDING' AND send_after<=? AND expires_at>? AND attempts<3 AND (NOT ? OR kind='REPLY') ORDER BY send_after,id LIMIT 3 FOR UPDATE SKIP LOCKED",
      time(clock.instant()),
      time(clock.instant()),
      config.repliesOnly
    )) {
      UUID id = (UUID) row.get("id");
      if (!eligible(row)) {
        cancel(id, "INELIGIBLE");
        continue;
      }
      UUID lease = UUID.randomUUID();
      jdbc.update(
        "UPDATE whatsapp_outbox SET state='CLAIMED',lease_id=?,lease_until=?,updated_at=? WHERE id=?",
        lease,
        time(clock.instant().plusSeconds(90)),
        time(clock.instant()),
        id
      );
      leases.add(new Lease(id, lease));
    }
    return leases;
  }

  public Dispatch dispatch(UUID id, UUID lease) {
    lock();
    var row = leased(id, lease, "CLAIMED");
    if (config.repliesOnly && !"REPLY".equals(row.get("kind"))) {
      jdbc.update(
        "UPDATE whatsapp_outbox SET state='PENDING',lease_id=NULL,lease_until=NULL,updated_at=? WHERE id=?",
        time(clock.instant()),
        id
      );
      return new Dispatch(false, null, null);
    }
    if (!config.deliveryAvailable() || !eligible(row)) {
      cancel(id, "INELIGIBLE");
      return new Dispatch(false, null, null);
    }
    String bucket = "send:" + clock.instant().getEpochSecond() / 86400;
    if (
      jdbc
        .queryForList(
          "INSERT INTO whatsapp_worker_limits VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET requests=whatsapp_worker_limits.requests+1 WHERE whatsapp_worker_limits.requests<? RETURNING requests",
          Integer.class,
          bucket,
          time(clock.instant().plusSeconds(172800)),
          config.dailyLimit
        )
        .isEmpty()
    ) {
      jdbc.update(
        "UPDATE whatsapp_outbox SET state='PENDING',lease_id=NULL,send_after=?,updated_at=? WHERE id=?",
        time(
          clock
            .instant()
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            .plusSeconds(86400)
        ),
        time(clock.instant()),
        id
      );
      return new Dispatch(false, null, null);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    if (row.get("kind").equals("REPLY")) payload.putAll(
      json.readValue((String) row.get("body"), Map.class)
    );
    else {
      var g = game((UUID) row.get("game_id"));
      var date = instant(g.get("starts_at")).atZone(
        ZoneId.of((String) g.get("time_zone"))
      );
      List<Object> components = new ArrayList<>();
      components.add(
        Map.of(
          "type",
          "body",
          "parameters",
          List.of(
            g.get("club_name"),
            g.get("title"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(date),
            g.get("location"),
            config.publicUrl + "/#game/" + g.get("id")
          )
            .stream()
            .map(s -> Map.of("type", "text", "text", s))
            .toList()
        )
      );
      if (row.get("kind").equals("INVITATION")) {
        for (String a : List.of("ATTEND", "DECLINE")) {
          UUID token = action(
            (UUID) row.get("player_id"),
            instant(row.get("verified_at")),
            (UUID) g.get("id"),
            null,
            null,
            revision(g),
            a,
            instant(g.get("starts_at"))
          );
          components.add(
            Map.of(
              "type",
              "button",
              "sub_type",
              "quick_reply",
              "index",
              a.equals("ATTEND") ? "0" : "1",
              "parameters",
              List.of(Map.of("type", "payload", "payload", "TD:" + token))
            )
          );
        }
      }
      payload.put("type", "template");
      payload.put(
        "template",
        Map.of(
          "name",
          row.get("kind").equals("INVITATION")
            ? config.invitationTemplate
            : config.reminderTemplate,
          "language",
          Map.of("code", "pt_BR"),
          "components",
          components
        )
      );
    }
    String phone = (String) jdbc
      .queryForMap(
        "SELECT phone FROM whatsapp_contacts WHERE player_id=?",
        row.get("player_id")
      )
      .get("phone");
    payload.put("messaging_product", "whatsapp");
    payload.put("to", phone.substring(1));
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='SENDING',attempts=attempts+1,lease_until=?,updated_at=? WHERE id=?",
      time(clock.instant().plusSeconds(90)),
      time(clock.instant()),
      id
    );
    return new Dispatch(
      true,
      config.deliveryUrl(phoneId),
      payload
    );
  }

  private boolean eligible(Map<String, Object> row) {
    if (!instant(row.get("expires_at")).isAfter(clock.instant())) return false;
    var contacts = jdbc.queryForList(
      "SELECT * FROM whatsapp_contacts WHERE player_id=? AND phone IS NOT NULL AND stopped_at IS NULL AND verified_at=?",
      row.get("player_id"),
      row.get("verified_at")
    );
    if (
      contacts.isEmpty() ||
      !config.permits((String) contacts.getFirst().get("phone"))
    ) return false;
    if (
      row.get("club_id") != null &&
      jdbc.queryForObject(
        "SELECT count(*) FROM members m JOIN whatsapp_group_settings s ON s.club_id=m.club_id WHERE m.club_id=? AND m.player_id=? AND s.enabled",
        Integer.class,
        row.get("club_id"),
        row.get("player_id")
      ) != 1
    ) return false;
    if (row.get("kind").equals("REPLY")) return (
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_conversations WHERE player_id=? AND verified_at=? AND last_received_at>?",
        Integer.class,
        row.get("player_id"),
        row.get("verified_at"),
        time(clock.instant().minusSeconds(86400))
      ) == 1 &&
      jdbc.queryForObject(
        "SELECT count(*) FROM members m JOIN whatsapp_group_settings s ON s.club_id=m.club_id WHERE m.player_id=? AND s.enabled",
        Integer.class,
        row.get("player_id")
      ) > 0
    );
    var g = game((UUID) row.get("game_id"));
    if (
      !active(g) ||
      !revision(g).equals(row.get("revision")) ||
      !Boolean.TRUE.equals(g.get("enabled"))
    ) return false;
    String column = row.get("kind").equals("INVITATION")
      ? "invitations"
      : "reminders";
    if (
      jdbc.queryForObject(
        "SELECT count(*) FROM members m JOIN whatsapp_preferences p ON p.member_id=m.id WHERE m.club_id=? AND m.player_id=? AND p." +
          column,
        Integer.class,
        row.get("club_id"),
        row.get("player_id")
      ) != 1
    ) return false;
    int confirmed = jdbc.queryForObject(
      "SELECT count(*) FROM participations WHERE game_id=? AND player_id=? AND status='CONFIRMED'",
      Integer.class,
      row.get("game_id"),
      row.get("player_id")
    );
    if (row.get("kind").equals("REMINDER")) return confirmed == 1;
    return (
      confirmed == 0 &&
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_responses WHERE game_id=? AND player_id=? AND response='NO'",
        Integer.class,
        row.get("game_id"),
        row.get("player_id")
      ) == 0 &&
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_outbox WHERE game_id=? AND player_id=? AND kind='INVITATION' AND id<>? AND state IN ('SENDING','ACCEPTED','DELIVERED','READ','UNKNOWN')",
        Integer.class,
        row.get("game_id"),
        row.get("player_id"),
        row.get("id")
      ) == 0
    );
  }

  public Map<String, Object> leased(UUID id, UUID lease, String expected) {
    var rows = jdbc.queryForList(
      "SELECT * FROM whatsapp_outbox WHERE id=? FOR UPDATE",
      id
    );
    if (rows.isEmpty()) throw ApiException.notFound();
    var row = rows.getFirst();
    if (
      !lease.equals(row.get("lease_id")) ||
      !expected.equals(row.get("state")) ||
      !instant(row.get("lease_until")).isAfter(clock.instant())
    ) throw ApiException.conflict(
      "Reserva de envio expirou ou já foi utilizada."
    );
    return row;
  }

  public void receipt(UUID id, UUID lease, Receipt receipt) {
    lock();
    var rows = jdbc.queryForList(
      "SELECT * FROM whatsapp_outbox WHERE id=? AND lease_id=? FOR UPDATE",
      id,
      lease
    );
    if (rows.isEmpty()) throw ApiException.conflict("Reserva inválida.");
    var row = rows.getFirst();
    if (
      !row.get("state").equals("SENDING") && !row.get("state").equals("UNKNOWN")
    ) return;
    if (
      !Set.of("ACCEPTED", "FAILED", "UNKNOWN").contains(receipt.state())
    ) throw new ApiException(400, "Resultado de envio inválido.");
    if (
      receipt.state().equals("ACCEPTED") &&
      (receipt.providerId() == null ||
        !receipt.providerId().matches("[A-Za-z0-9_.:+=/-]{1,256}"))
    ) throw new ApiException(400, "Identificador do provedor inválido.");
    String error =
      !receipt.state().equals("ACCEPTED") &&
      receipt.errorCode() != null &&
      receipt.errorCode().matches("[A-Z0-9_]{1,60}")
        ? receipt.errorCode()
        : null;
    String state = receipt.state();
    boolean retry =
      state.equals("FAILED") &&
      Set.of("4", "80007", "130429", "131000", "131016").contains(
        error == null ? "" : error
      ) &&
      ((Number) row.get("attempts")).intValue() < 3;
    jdbc.update(
      "UPDATE whatsapp_outbox SET state=?,provider_id=?,error_code=?,body=CASE WHEN ?='ACCEPTED' THEN NULL ELSE body END,send_after=?,updated_at=? WHERE id=?",
      retry ? "PENDING" : state,
      state.equals("ACCEPTED") ? receipt.providerId() : null,
      error,
      state,
      time(
        clock
          .instant()
          .plusSeconds(60L * ((Number) row.get("attempts")).intValue())
      ),
      time(clock.instant()),
      id
    );
    if (state.equals("ACCEPTED")) applyDelivery(receipt.providerId());
  }

  public void delivery(
    String id,
    String state,
    long timestamp,
    String errorCode
  ) {
    if (
      id == null ||
      id.length() > 256 ||
      !Set.of("sent", "delivered", "read", "failed").contains(state) ||
      timestamp <= 0 ||
      timestamp > clock.instant().getEpochSecond() + 5
    ) return;
    String error =
      state.equals("failed") &&
      errorCode != null &&
      errorCode.matches("[0-9]{1,8}")
        ? errorCode
        : null;
    jdbc.update(
      """
      INSERT INTO whatsapp_delivery_events(event_hash,provider_id,state,message_at,received_at,error_code)
      VALUES(?,?,?,?,?,?) ON CONFLICT(event_hash) DO UPDATE
      SET error_code=coalesce(whatsapp_delivery_events.error_code,EXCLUDED.error_code)
      """,
      WhatsApp.hash(id + state + timestamp),
      id,
      state.toUpperCase(Locale.ROOT),
      time(Instant.ofEpochSecond(timestamp)),
      time(clock.instant()),
      error
    );
    applyDelivery(id);
  }

  private void applyDelivery(String id) {
    var states = jdbc.queryForList(
      "SELECT state,error_code FROM whatsapp_delivery_events WHERE provider_id=? ORDER BY CASE state WHEN 'READ' THEN 4 WHEN 'DELIVERED' THEN 3 WHEN 'FAILED' THEN 2 ELSE 1 END DESC,message_at DESC LIMIT 1",
      id
    );
    if (states.isEmpty()) return;
    String state = (String) states.getFirst().get("state");
    if (state.equals("SENT")) return;
    jdbc.update(
      "UPDATE whatsapp_outbox SET state=?,error_code=?,updated_at=? WHERE provider_id=? AND state NOT IN ('READ','CANCELLED') AND (state<>'DELIVERED' OR ?='READ')",
      state,
      state.equals("FAILED") ? states.getFirst().get("error_code") : null,
      time(clock.instant()),
      id,
      state
    );
  }

  private void cancel(UUID id, String reason) {
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='CANCELLED',error_code=?,body=NULL,updated_at=? WHERE id=?",
      reason,
      time(clock.instant()),
      id
    );
  }

  public void reconcile() {
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='UNKNOWN',error_code='TIMEOUT',updated_at=? WHERE state='SENDING' AND lease_until<=?",
      time(clock.instant()),
      time(clock.instant())
    );
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='PENDING',lease_id=NULL WHERE state='CLAIMED' AND lease_until<=?",
      time(clock.instant())
    );
    jdbc.update(
      "UPDATE whatsapp_outbox SET state='CANCELLED',body=NULL,error_code='EXPIRED',updated_at=? WHERE state IN ('PENDING','CLAIMED') AND expires_at<=?",
      time(clock.instant()),
      time(clock.instant())
    );
    jdbc.update(
      "DELETE FROM whatsapp_actions WHERE expires_at<?",
      time(clock.instant().minus(Duration.ofDays(1)))
    );
    jdbc.update(
      "DELETE FROM whatsapp_delivery_events WHERE received_at<?",
      time(clock.instant().minus(Duration.ofDays(7)))
    );
    jdbc.update(
      "DELETE FROM whatsapp_outbox WHERE updated_at<? AND state NOT IN ('SENDING','CLAIMED')",
      time(clock.instant().minus(Duration.ofDays(30)))
    );
    jdbc.update(
      "DELETE FROM whatsapp_worker_limits WHERE expires_at<?",
      time(clock.instant())
    );
  }
}
