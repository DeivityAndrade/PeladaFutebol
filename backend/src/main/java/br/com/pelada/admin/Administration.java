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

  public Administration(
    Accounts accounts,
    AdminAccess access,
    JdbcTemplate jdbc,
    Clock clock
  ) {
    this.accounts = accounts;
    this.access = access;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public record Month(String period, long registrations) {}

  public record Summary(
    Instant generatedAt,
    String timeZone,
    long totalAccounts,
    long totalGroups,
    long newLast7Days,
    long newThisMonth,
    long undatedAccounts,
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
      months.add(new Month(period, counts.getOrDefault(period, 0L)));
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
      List.copyOf(months)
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
