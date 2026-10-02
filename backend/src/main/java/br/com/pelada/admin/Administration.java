package br.com.pelada.admin;

import br.com.pelada.auth.Accounts;
import br.com.pelada.domain.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Administration {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final String REAL_ACCOUNT =
    "password <> '!disabled' AND email NOT LIKE 'demo-%@example.invalid'";
  private final Accounts accounts;
  private final AdminAccess access;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final SiteVisits siteVisits;

  public Administration(
    Accounts accounts,
    AdminAccess access,
    JdbcTemplate jdbc,
    Clock clock,
    SiteVisits siteVisits
  ) {
    this.accounts = accounts;
    this.access = access;
    this.jdbc = jdbc;
    this.clock = clock;
    this.siteVisits = siteVisits;
  }

  public record Month(String period, long registrations, long visits) {}

  public record Visits(
    boolean enabled,
    Instant startedAt,
    long total,
    long today,
    long last7Days,
    long thisMonth
  ) {}

  public record Summary(
    Instant generatedAt,
    String timeZone,
    long totalAccounts,
    long totalGroups,
    long newLast7Days,
    long newThisMonth,
    long undatedAccounts,
    Visits visits,
    List<Month> months
  ) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Summary summary(Authentication auth) {
    if (!access.allowed(accounts.current(auth))) throw ApiException.forbidden();
    Instant now = clock.instant();
    YearMonth currentMonth = YearMonth.from(now.atZone(ZONE));
    Instant monthStart = currentMonth.atDay(1).atStartOfDay(ZONE).toInstant();
    Instant historyStart = currentMonth
      .minusMonths(11)
      .atDay(1)
      .atStartOfDay(ZONE)
      .toInstant();
    Map<String, Long> counts = new HashMap<>();
    Map<String, Long> visitCounts = new HashMap<>();
    jdbc.query(
      "SELECT to_char(day, 'YYYY-MM') AS period, sum(visits) AS visits " +
        "FROM site_visit_days WHERE day >= ? AND day <= ? GROUP BY period",
      row -> {
        visitCounts.put(row.getString("period"), row.getLong("visits"));
      },
      java.sql.Date.valueOf(currentMonth.minusMonths(11).atDay(1)),
      java.sql.Date.valueOf(now.atZone(ZONE).toLocalDate())
    );
    jdbc.query(
      "SELECT to_char(created_at AT TIME ZONE ?, 'YYYY-MM') AS period, count(*) AS registrations " +
        "FROM players WHERE " +
        REAL_ACCOUNT +
        " AND created_at >= ? AND created_at <= ? GROUP BY period",
      row -> {
        counts.put(row.getString("period"), row.getLong("registrations"));
      },
      ZONE.getId(),
      Timestamp.from(historyStart),
      Timestamp.from(now)
    );
    List<Month> months = new ArrayList<>();
    for (int i = 11; i >= 0; i--) {
      String period = currentMonth.minusMonths(i).toString();
      months.add(
        new Month(
          period,
          counts.getOrDefault(period, 0L),
          visitCounts.getOrDefault(period, 0L)
        )
      );
    }
    return new Summary(
      now,
      ZONE.getId(),
      count(""),
      jdbc.queryForObject(
        "SELECT count(*) FROM clubs WHERE demo = false",
        Long.class
      ),
      between(now.minus(Duration.ofDays(7)), now),
      between(monthStart, now),
      count(" AND created_at IS NULL"),
      visitSummary(now),
      List.copyOf(months)
    );
  }

  private Visits visitSummary(Instant now) {
    LocalDate today = now.atZone(ZONE).toLocalDate();
    return jdbc.queryForObject(
      """
      SELECT min(first_recorded_at) AS started_at,
        coalesce(sum(visits),0) AS total,
        coalesce(sum(visits) FILTER (WHERE day = ?),0) AS today,
        coalesce(sum(visits) FILTER (WHERE day >= ?),0) AS last_seven,
        coalesce(sum(visits) FILTER (WHERE day >= ?),0) AS this_month
      FROM site_visit_days WHERE day <= ?
      """,
      (row, index) ->
        new Visits(
          siteVisits.enabled(),
          row.getTimestamp("started_at") == null
            ? null
            : row.getTimestamp("started_at").toInstant(),
          row.getLong("total"),
          row.getLong("today"),
          row.getLong("last_seven"),
          row.getLong("this_month")
        ),
      java.sql.Date.valueOf(today),
      java.sql.Date.valueOf(today.minusDays(6)),
      java.sql.Date.valueOf(today.withDayOfMonth(1)),
      java.sql.Date.valueOf(today)
    );
  }

  private long count(String condition) {
    return jdbc.queryForObject(
      "SELECT count(*) FROM players WHERE " + REAL_ACCOUNT + condition,
      Long.class
    );
  }

  private long between(Instant start, Instant end) {
    return jdbc.queryForObject(
      "SELECT count(*) FROM players WHERE " +
        REAL_ACCOUNT +
        " AND created_at >= ? AND created_at <= ?",
      Long.class,
      Timestamp.from(start),
      Timestamp.from(end)
    );
  }
}
