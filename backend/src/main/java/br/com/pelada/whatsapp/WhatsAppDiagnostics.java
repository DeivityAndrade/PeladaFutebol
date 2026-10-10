package br.com.pelada.whatsapp;

import static br.com.pelada.whatsapp.WhatsAppOutbox.time;

import br.com.pelada.domain.ApiException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WhatsAppDiagnostics {

  private final JdbcTemplate jdbc;
  private final WhatsAppIntegration config;
  private final WhatsApp whatsapp;
  private final Clock clock;

  public WhatsAppDiagnostics(
    JdbcTemplate jdbc,
    WhatsAppIntegration config,
    WhatsApp whatsapp,
    Clock clock
  ) {
    this.jdbc = jdbc;
    this.config = config;
    this.whatsapp = whatsapp;
    this.clock = clock;
  }

  public record Status(
    boolean sendsEnabled,
    boolean repliesOnly,
    boolean deliveryReady,
    boolean phoneIdMatches,
    boolean deliveryErrorDetailsAvailable,
    long exactVerifiedContacts,
    long legacyBrazilianVerifiedContacts,
    long enabledGroups,
    Map<String, Long> recentInbox,
    long recentReplies,
    Map<String, Long> recentReplyStates,
    Map<String, Long> recentReplyErrors
  ) {}

  @Transactional(readOnly = true)
  public Status inspect(String phone, String expectedPhoneId) {
    if (!config.permits(phone)) throw ApiException.forbidden();
    String exact = "+" + phone;
    // Compare também um vínculo brasileiro antigo sem o nono dígito.
    // Apenas diagnosticar: não aceitar, unir ou trocar identidades automaticamente.
    String legacy =
      phone.startsWith("55") && phone.length() == 13 && phone.charAt(4) == '9'
        ? "+" + phone.substring(0, 4) + phone.substring(5)
        : exact;
    long exactContacts = verified(exact);
    long legacyContacts = exact.equals(legacy) ? 0 : verified(legacy);
    long enabledGroups = jdbc.queryForObject(
      """
      SELECT count(DISTINCT m.club_id) FROM members m
      JOIN whatsapp_contacts c ON c.player_id=m.player_id
      JOIN whatsapp_group_settings s ON s.club_id=m.club_id
      WHERE (c.phone=? OR c.phone=?) AND c.verified_at IS NOT NULL
      AND c.stopped_at IS NULL AND s.enabled
      """,
      Long.class,
      exact,
      legacy
    );
    var since = time(clock.instant().minusSeconds(86400));
    var states = new LinkedHashMap<String, Long>();
    jdbc
      .queryForList(
        """
        SELECT i.state,count(*) n FROM whatsapp_inbox i
        JOIN whatsapp_contacts c ON c.player_id=i.player_id
        WHERE (c.phone=? OR c.phone=?) AND i.created_at>? GROUP BY i.state
        """,
        exact,
        legacy,
        since
      )
      .forEach(row ->
        states.put(
          (String) row.get("state"),
          ((Number) row.get("n")).longValue()
        )
      );
    long replies = jdbc.queryForObject(
      """
      SELECT count(*) FROM whatsapp_outbox o
      JOIN whatsapp_contacts c ON c.player_id=o.player_id
      WHERE (c.phone=? OR c.phone=?) AND o.kind='REPLY' AND o.created_at>?
      """,
      Long.class,
      exact,
      legacy,
      since
    );
    var replyStates = new LinkedHashMap<String, Long>();
    var replyErrors = new LinkedHashMap<String, Long>();
    jdbc
      .queryForList(
        """
        SELECT o.state,o.error_code,count(*) n FROM whatsapp_outbox o
        JOIN whatsapp_contacts c ON c.player_id=o.player_id
        WHERE (c.phone=? OR c.phone=?) AND o.kind='REPLY' AND o.created_at>?
        GROUP BY o.state,o.error_code
        """,
        exact,
        legacy,
        since
      )
      .forEach(row -> {
        long count = ((Number) row.get("n")).longValue();
        replyStates.merge((String) row.get("state"), count, Long::sum);
        String error = (String) row.get("error_code");
        if (error != null && !error.isBlank()) {
          replyErrors.merge(safeError(error), count, Long::sum);
        }
      });
    return new Status(
      config.sendEnabled,
      config.repliesOnly,
      config.deliveryAvailable(),
      whatsapp.phoneId().equals(expectedPhoneId),
      true,
      exactContacts,
      legacyContacts,
      enabledGroups,
      states,
      replies,
      replyStates,
      replyErrors
    );
  }

  private String safeError(String error) {
    if (
      error.matches("[0-9]{1,8}") ||
      Set.of(
        "INVALID_RESPONSE",
        "TRANSPORT_UNKNOWN",
        "TIMEOUT",
        "INELIGIBLE",
        "PAUSED",
        "EXPIRED",
        "REVOKED",
        "GAME_CHANGED",
        "SCHEDULE_CHANGED"
      ).contains(error)
    ) return error;
    return "OTHER";
  }

  private long verified(String phone) {
    return jdbc.queryForObject(
      "SELECT count(*) FROM whatsapp_contacts WHERE phone=? AND verified_at IS NOT NULL AND stopped_at IS NULL",
      Long.class,
      phone
    );
  }
}
