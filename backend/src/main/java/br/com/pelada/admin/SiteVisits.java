package br.com.pelada.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteVisits {

  public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  public static final Duration WINDOW = Duration.ofMinutes(30);
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean enabled;

  public SiteVisits(
    JdbcTemplate jdbc,
    Clock clock,
    @Value("${app.visit-stats-enabled:true}") boolean enabled
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  @Transactional
  public void record(UUID token) {
    if (!enabled) return;
    Instant now = clock.instant();
    // The upsert serializes concurrent requests for the same browser window.
    // Expired identifiers are replaced, so a replay cannot deduplicate forever.
    int started = jdbc.update(
      """
      INSERT INTO site_visit_windows (token_hash, expires_at) VALUES (?, ?)
      ON CONFLICT (token_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at
      WHERE site_visit_windows.expires_at <= ?
      """,
      hash(token),
      Timestamp.from(now.plus(WINDOW)),
      Timestamp.from(now)
    );
    if (started > 0) jdbc.update(
      """
      INSERT INTO site_visit_days (day, visits, first_recorded_at) VALUES (?, 1, ?)
      ON CONFLICT (day) DO UPDATE SET visits = site_visit_days.visits + 1
      """,
      java.sql.Date.valueOf(now.atZone(ZONE).toLocalDate()),
      Timestamp.from(now)
    );
    jdbc.update(
      "DELETE FROM site_visit_windows WHERE expires_at <= ?",
      Timestamp.from(now)
    );
  }

  private static String hash(UUID token) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          token.toString().getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
