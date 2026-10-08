package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.career.Career;
import br.com.pelada.career.CareerContracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
import br.com.pelada.groups.Groups;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(CareerTest.TimeConfiguration.class)
class CareerTest {

  static final Instant START = Instant.parse("2026-10-08T12:00:00Z");

  @TestConfiguration
  static class TimeConfiguration {

    @Bean
    @Primary
    MutableClock careerTestClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {

    final AtomicReference<Instant> now = new AtomicReference<>(START);

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }
  }

  @Autowired
  Career career;

  @Autowired
  Games games;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MutableClock clock;

  UUID owner, member, guest, club;

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE spring_session,players CASCADE");
    clock.now.set(START);
    List<UUID> players = tx.execute(s ->
      List.of(
        store
          .save(new Player("Organizador", "owner@career.invalid", "!disabled"))
          .id,
        store
          .save(new Player("Parceiro", "member@career.invalid", "!disabled"))
          .id,
        store
          .save(new Player("Convidado", "guest@career.invalid", "!disabled"))
          .id
      )
    );
    owner = players.get(0);
    member = players.get(1);
    guest = players.get(2);
    ClubView group = groups.create(
      owner,
      new CreateClub("Galera", "Encontros")
    );
    club = group.id();
    groups.join(member, group.invite());
    career.configure(owner, club, true);
  }

  UUID scheduled(int hours, int teamCount) {
    return games
      .create(
        owner,
        club,
        new CreateGame(
          "Encontro " + hours,
          "Quadra",
          clock.instant().plusSeconds(hours * 3600L),
          teamCount,
          5
        )
      )
      .game()
      .id();
  }

  void passed(UUID game) {
    Instant at = tx.execute(s -> store.get(Game.class, game).startsAt);
    clock.now.set(at.plusSeconds(3600));
  }

  ReviewInput choices(
    long version,
    boolean ownerPresent,
    boolean memberPresent
  ) {
    return new ReviewInput(
      version,
      true,
      List.of(
        new AttendanceChoice(owner, ownerPresent),
        new AttendanceChoice(member, memberPresent)
      )
    );
  }

  UUID attended() {
    UUID game = scheduled(1, 3);
    games.attend(owner, game);
    games.attend(member, game);
    passed(game);
    return game;
  }

  List<String> earned(UUID player) {
    return career
      .mine(player, club)
      .achievements()
      .stream()
      .filter(a -> a.awardedAt() != null)
      .map(AchievementView::code)
      .toList();
  }

  void forbidden(Runnable action, int status) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ApiException.class,
      e -> assertThat(e.status).isEqualTo(status)
    );
  }

  @Test
  void onlyVerifiedPresenceCountsAndReplayIsIdempotent() {
    UUID game = attended();
    assertThat(career.mine(member, club).appearances()).isZero();
    assertThat(career.mine(member, club).pending()).hasSize(1);
    assertThat(career.review(owner, game).players()).allMatch(
      p -> p.present() == null
    );
    ReviewView saved = career.saveReview(owner, game, choices(0, true, false));
    assertThat(saved.version()).isEqualTo(1);
    assertThat(career.mine(owner, club).appearances()).isEqualTo(1);
    assertThat(career.mine(member, club).appearances()).isZero();
    assertThat(earned(owner)).containsExactly("FIRST_APPEARANCE");
    assertThat(
      career.saveReview(owner, game, choices(0, true, false)).version()
    ).isEqualTo(1);
    assertThat(
      jdbc.queryForObject("select count(*) from attendance_audits", Long.class)
    ).isEqualTo(1);
    career.seen(owner, club);
    assertThat(career.mine(owner, club).achievements()).noneMatch(
      AchievementView::unseen
    );
  }

  @Test
  void correctionsRevokeOnlyInvalidMilestonesAndPreserveAudit() {
    UUID game = attended();
    career.saveReview(owner, game, choices(0, true, true));
    career.customize(
      member,
      club,
      new CardInput(true, null, null, List.of("FIRST_APPEARANCE"))
    );
    career.saveReview(owner, game, choices(1, true, false));
    CareerView view = career.mine(member, club);
    assertThat(view.appearances()).isZero();
    assertThat(view.card().badges()).isEmpty();
    assertThat(view.correctionUnseen()).isTrue();
    assertThat(earned(member)).isEmpty();
    assertThat(career.review(owner, game).history()).hasSize(2);
    forbidden(
      () -> career.saveReview(owner, game, choices(1, false, true)),
      409
    );
    career.seen(member, club);
    assertThat(career.mine(member, club).correctionUnseen()).isFalse();
  }

  @Test
  void cancelledGamesNeverContributeAndDoNotChangeTheRoster() {
    UUID game = attended();
    career.saveReview(owner, game, choices(0, true, true));
    var before = games.get(owner, game).attendees();
    games.cancel(owner, game);
    assertThat(career.mine(member, club).appearances()).isZero();
    assertThat(earned(member)).isEmpty();
    assertThat(games.get(owner, game).attendees()).isEqualTo(before);
    forbidden(
      () -> career.saveReview(owner, game, choices(1, true, true)),
      409
    );
  }

  @Test
  void allFourMilestonesArePersonalAndLockedCosmeticsAreRejected() {
    forbidden(
      () ->
        career.customize(
          member,
          club,
          new CardInput(false, "FIVE_APPEARANCES", null, List.of())
        ),
      400
    );
    for (int i = 1; i <= 25; i++) {
      UUID game = attended();
      career.saveReview(owner, game, choices(0, true, true));
      if (i == 1) assertThat(earned(member)).hasSize(1);
      if (i == 5) assertThat(earned(member)).hasSize(2);
      if (i == 10) assertThat(earned(member)).hasSize(3);
    }
    assertThat(earned(member)).containsExactly(
      "FIRST_APPEARANCE",
      "FIVE_APPEARANCES",
      "TEN_APPEARANCES",
      "TWENTY_FIVE_APPEARANCES"
    );
    CardView card = career.customize(
      member,
      club,
      new CardInput(
        true,
        "TWENTY_FIVE_APPEARANCES",
        "TEN_APPEARANCES",
        List.of("FIRST_APPEARANCE", "TWENTY_FIVE_APPEARANCES")
      )
    );
    assertThat(card.title()).isEqualTo("TWENTY_FIVE_APPEARANCES");
    assertThat(card.frame()).isEqualTo("TEN_APPEARANCES");
    forbidden(
      () ->
        career.customize(
          member,
          club,
          new CardInput(true, "FIRST_APPEARANCE", null, List.of())
        ),
      400
    );
    forbidden(
      () ->
        career.customize(
          member,
          club,
          new CardInput(
            true,
            null,
            null,
            List.of("FIRST_APPEARANCE", "FIRST_APPEARANCE")
          )
        ),
      400
    );
  }

  @Test
  void ownershipPrivacyAndGuestsAreEnforced() {
    UUID game = attended();
    tx.executeWithoutResult(s ->
      store.save(new Participation(game, guest, "CONFIRMED"))
    );
    passed(game);
    forbidden(() -> career.review(member, game), 403);
    forbidden(
      () -> career.saveReview(member, game, choices(0, true, true)),
      403
    );
    forbidden(() -> career.configure(member, club, false), 403);
    career.saveReview(
      owner,
      game,
      new ReviewInput(
        0,
        true,
        List.of(
          new AttendanceChoice(owner, true),
          new AttendanceChoice(member, true),
          new AttendanceChoice(guest, true)
        )
      )
    );
    assertThat(career.mine(guest, club).appearances()).isEqualTo(1);
    assertThat(career.mine(guest, club).canShare()).isFalse();
    forbidden(
      () ->
        career.customize(
          guest,
          club,
          new CardInput(true, null, null, List.of())
        ),
      403
    );
    assertThat(career.directory(guest))
      .singleElement()
      .satisfies(g -> assertThat(g.member()).isFalse());
    forbidden(() -> career.shared(guest, club, member), 403);
    forbidden(() -> career.shared(owner, club, member), 404);
    career.customize(
      member,
      club,
      new CardInput(true, null, null, List.of("FIRST_APPEARANCE"))
    );
    assertThat(career.shared(owner, club, member).badges()).containsExactly(
      "FIRST_APPEARANCE"
    );
    tx.executeWithoutResult(s ->
      store.remove(
        store
          .first(
            Member.class,
            "from Member where clubId=:club and playerId=:player",
            "club",
            club,
            "player",
            member
          )
          .orElseThrow()
      )
    );
    assertThat(career.mine(member, club).appearances()).isEqualTo(1);
    forbidden(() -> career.shared(owner, club, member), 404);
  }

  @Test
  void neverBackfillOldGamesAndPauseIsEvaluatedAtEventTime() {
    UUID game = scheduled(1, 3);
    games.attend(owner, game);
    games.attend(member, game);
    career.configure(owner, club, false);
    passed(game);
    career.configure(owner, club, true);
    assertThat(career.review(owner, game).eligible()).isFalse();
    forbidden(
      () -> career.saveReview(owner, game, choices(0, true, true)),
      409
    );
    // The first activation timestamp is retained after a pause.
    assertThat(career.mine(owner, club).program().startedAt()).isEqualTo(START);
    UUID old = attended();
    career.saveReview(owner, old, choices(0, true, true));
    career.configure(owner, club, false);
    assertThat(earned(owner)).containsExactly("FIRST_APPEARANCE");
    UUID prior = scheduled(1, 3);
    games.attend(owner, prior);
    games.attend(member, prior);
    passed(prior);
    career.configure(owner, club, true);
    assertThat(career.review(owner, prior).eligible()).isFalse();
  }

  @Test
  void incompleteFutureLiveAndWaitingSubmissionsAreRejected() {
    UUID game = scheduled(1, 2);
    games.attend(owner, game);
    games.attend(member, game);
    tx.executeWithoutResult(s ->
      store.save(new Participation(game, guest, "WAITING"))
    );
    forbidden(
      () -> career.saveReview(owner, game, choices(0, true, true)),
      409
    );
    passed(game);
    forbidden(
      () ->
        career.saveReview(
          owner,
          game,
          new ReviewInput(0, false, choices(0, true, true).players())
        ),
      400
    );
    forbidden(
      () ->
        career.saveReview(
          owner,
          game,
          new ReviewInput(0, true, List.of(new AttendanceChoice(owner, true)))
        ),
      409
    );
    forbidden(
      () ->
        career.saveReview(
          owner,
          game,
          new ReviewInput(
            0,
            true,
            List.of(
              new AttendanceChoice(owner, true),
              new AttendanceChoice(member, true),
              new AttendanceChoice(guest, true)
            )
          )
        ),
      409
    );
    forbidden(
      () ->
        career.saveReview(
          owner,
          game,
          new ReviewInput(
            0,
            true,
            List.of(
              new AttendanceChoice(owner, true),
              new AttendanceChoice(owner, false)
            )
          )
        ),
      400
    );
    tx.executeWithoutResult(
      s -> store.get(Game.class, game).matchStartedAt = clock.instant()
    );
    forbidden(
      () -> career.saveReview(owner, game, choices(0, true, true)),
      409
    );
    assertThat(career.mine(owner, club).appearances()).isZero();
  }

  @Test
  void firstActivationDoesNotConvertPreviousConfirmationsIntoCredits() {
    ClubView other = groups.create(
      owner,
      new CreateClub("Grupo novo", "Sem histórico conferido")
    );
    UUID old = games
      .create(
        owner,
        other.id(),
        new CreateGame(
          "Antes do programa",
          "Quadra",
          clock.instant().plusSeconds(3600),
          3,
          5
        )
      )
      .game()
      .id();
    games.attend(owner, old);
    passed(old);
    career.configure(owner, other.id(), true);
    assertThat(career.review(owner, old).eligible()).isFalse();
    assertThat(career.mine(owner, other.id()).appearances()).isZero();
    assertThat(career.mine(owner, other.id()).pending()).isEmpty();
  }

  @Test
  void anotherGroupAndSportingDataCannotAffectProgress() {
    UUID game = attended();
    career.saveReview(owner, game, choices(0, true, true));
    UUID other = groups
      .create(owner, new CreateClub("Outro grupo", "Separado"))
      .id();
    career.configure(owner, other, true);
    assertThat(career.mine(owner, other).appearances()).isZero();
    // These sporting fields are deliberately unrelated to the attendance ledger.
    tx.executeWithoutResult(s -> {
      Game g = store.get(Game.class, game);
      g.matchStartedAt = g.startsAt;
      g.matchEndedAt = clock.instant();
      g.matchDurationSeconds = 999;
      Participation p = store
        .first(
          Participation.class,
          "from Participation where gameId=:game and playerId=:player",
          "game",
          game,
          "player",
          member
        )
        .orElseThrow();
      p.slot = null;
      Team team = store
        .first(
          Team.class,
          "from Team where gameId=:game order by ordinal",
          "game",
          game
        )
        .orElseThrow();
      p.teamId = team.id;
      store.save(new Goal(game, team.id, member, 1, false, clock.instant()));
      store.save(new Rating(game, owner, member, 5, clock.instant()));
    });
    assertThat(career.mine(member, club).appearances()).isEqualTo(1);
    assertThat(earned(member)).containsExactly("FIRST_APPEARANCE");
    forbidden(() -> career.mine(guest, other), 403);
  }

  @Test
  void simultaneousRetriesHaveOneGrantAndOneAudit() throws Exception {
    UUID game = attended();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<ReviewView> submit = () -> {
        start.await();
        return career.saveReview(owner, game, choices(0, true, true));
      };
      Future<ReviewView> one = pool.submit(submit),
        two = pool.submit(submit);
      start.countDown();
      assertThat(one.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
      assertThat(two.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
    }
    assertThat(career.mine(member, club).appearances()).isEqualTo(1);
    assertThat(
      jdbc.queryForObject("select count(*) from attendance_audits", Long.class)
    ).isEqualTo(1);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from career_achievements where player_id=?",
        Long.class,
        member
      )
    ).isEqualTo(1);
  }
}
