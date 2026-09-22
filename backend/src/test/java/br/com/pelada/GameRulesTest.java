package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Groups;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class GameRulesTest {

  @Autowired
  Games games;

  @Autowired
  Matches matches;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  List<UUID> players;
  UUID owner, club, game;

  @BeforeEach
  void setup() {
    jdbc.execute(
      "TRUNCATE spring_session,participations,teams,games,members,clubs,players CASCADE"
    );
    players = tx.execute(status -> {
      List<UUID> result = new ArrayList<>();
      for (int i = 0; i < 15; i++) result.add(
        store
          .save(
            new Player("Jogador " + i, "p" + i + "@test.invalid", "!disabled")
          )
          .id
      );
      return result;
    });
    owner = players.getFirst();
    ClubView c = groups.create(
      owner,
      new CreateClub("Grupo de teste", "Futebol entre amigos")
    );
    club = c.id();
    for (UUID player : players) groups.join(player, c.invite());
    game = games
      .create(
        owner,
        club,
        new CreateGame(
          "Pelada teste",
          "Quadra 1",
          Instant.now().plusSeconds(86400),
          2,
          5
        )
      )
      .game()
      .id();
  }

  void confirmed(int count) {
    for (int i = 0; i < count; i++) games.attend(players.get(i), game);
  }

  List<TeamView> captains() {
    var teams = games.get(owner, game).teams();
    games.configure(
      owner,
      game,
      teams.get(0).id(),
      new UpdateTeam("Verde", "#d8f36a", owner)
    );
    games.configure(
      owner,
      game,
      teams.get(1).id(),
      new UpdateTeam("Roxo", "#a69aff", players.get(1))
    );
    return games.get(owner, game).teams();
  }

  @Test
  void capacityAndFifoPromotionRemainCorrect() {
    confirmed(12);
    var full = games.get(owner, game);
    assertThat(full.game().confirmed()).isEqualTo(10);
    assertThat(full.game().waiting()).isEqualTo(2);
    games.leave(players.get(2), game);
    var next = games.get(owner, game);
    assertThat(
      next
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(10)))
        .findFirst()
        .orElseThrow()
        .status()
    ).isEqualTo("CONFIRMED");
    assertThat(
      next
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(11)))
        .findFirst()
        .orElseThrow()
        .status()
    ).isEqualTo("WAITING");
    assertThat(next.game().confirmed()).isEqualTo(10);
  }

  @Test
  void simultaneousLastSpotDoesNotOverbook() throws Exception {
    confirmed(9);
    race(i -> games.attend(players.get(9 + i), game));
    var result = games.get(owner, game);
    assertThat(result.game().confirmed()).isEqualTo(10);
    assertThat(result.game().waiting()).isEqualTo(1);
  }

  @Test
  void repeatedConfirmationAndWithdrawalAreIdempotent() {
    games.attend(owner, game);
    games.attend(owner, game);
    assertThat(games.get(owner, game).game().confirmed()).isEqualTo(1);
    games.leave(owner, game);
    games.leave(owner, game);
    assertThat(games.get(owner, game).attendees()).isEmpty();
  }

  @Test
  void simultaneousCaptainPicksHaveOnlyOneWinner() throws Exception {
    confirmed(3);
    var teams = captains();
    var results = race(i ->
      games.pick(players.get(i), game, teams.get(i).id(), players.get(2))
    );
    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(
      results.stream().filter(Objects::nonNull).findFirst().orElseThrow()
    ).isInstanceOf(ApiException.class);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(2)))
        .findFirst()
        .orElseThrow()
        .teamId()
    ).isNotNull();
  }

  @Test
  void lineupSupportsSwapsAllFormationsAndEmptySlots() {
    confirmed(4);
    UUID team = captains().getFirst().id();
    games.pick(owner, game, team, players.get(2));
    for (String formation : List.of("2-2", "1-2-1", "3-1")) {
      long version = games.get(owner, game).teams().getFirst().version();
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          formation,
          Arrays.asList(owner, players.get(2), null, null, null),
          version
        )
      );
      version = games.get(owner, game).teams().getFirst().version();
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          formation,
          Arrays.asList(players.get(2), owner, null, null, null),
          version
        )
      );
      var result = games.get(owner, game);
      assertThat(result.teams().getFirst().formation()).isEqualTo(formation);
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> p.id().equals(owner))
          .findFirst()
          .orElseThrow()
          .slot()
      ).isEqualTo(1);
    }
  }

  @Test
  void duplicateForeignAndStaleLineupsAreRejected() {
    confirmed(3);
    UUID team = captains().getFirst().id();
    long version = games.get(owner, game).teams().getFirst().version();
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          "2-2",
          Arrays.asList(owner, owner, null, null, null),
          version
        )
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          "2-2",
          Arrays.asList(players.get(1), null, null, null, null),
          version
        )
      )
    ).isInstanceOf(ApiException.class);
    games.lineup(
      owner,
      game,
      team,
      new Lineup("2-2", Arrays.asList(owner, null, null, null, null), version)
    );
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup("2-2", Arrays.asList(owner, null, null, null, null), version)
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("mudou");
  }

  @Test
  void participantCannotManageAndCaptainCannotEditOtherTeam() {
    confirmed(3);
    var teams = captains();
    assertThatThrownBy(() ->
      games.configure(
        players.get(2),
        game,
        teams.getFirst().id(),
        new UpdateTeam("Outro", "#ffffff", null)
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      games.pick(owner, game, teams.get(1).id(), players.get(2))
    ).isInstanceOf(ApiException.class);
    UUID outsider = tx.execute(
      s -> store.save(new Player("Fora", "fora@test.invalid", "!disabled")).id
    );
    assertThatThrownBy(() -> games.get(outsider, game)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() -> games.attend(outsider, game)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void captainDepartureClearsRosterAndAllowsReplacement() {
    confirmed(11);
    UUID team = captains().getFirst().id();
    games.lineup(
      owner,
      game,
      team,
      new Lineup(
        "2-2",
        Arrays.asList(owner, null, null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );
    games.leave(owner, game);
    var result = games.get(owner, game);
    assertThat(result.teams().getFirst().captainId()).isNull();
    assertThat(result.attendees()).noneMatch(p -> p.id().equals(owner));
    games.configure(
      owner,
      game,
      team,
      new UpdateTeam("Verde", "#d8f36a", players.get(2))
    );
    assertThat(games.get(owner, game).teams().getFirst().captainId()).isEqualTo(
      players.get(2)
    );
  }

  @Test
  void releaseClearsPositionAndDoesNotCancelAttendance() {
    confirmed(3);
    UUID team = captains().getFirst().id();
    games.pick(owner, game, team, players.get(2));
    games.lineup(
      owner,
      game,
      team,
      new Lineup(
        "2-2",
        Arrays.asList(owner, players.get(2), null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );
    games.release(owner, game, team, players.get(2));
    var p = games
      .get(owner, game)
      .attendees()
      .stream()
      .filter(a -> a.id().equals(players.get(2)))
      .findFirst()
      .orElseThrow();
    assertThat(p.teamId()).isNull();
    assertThat(p.slot()).isNull();
    assertThat(p.status()).isEqualTo("CONFIRMED");
  }

  @Test
  void fullRosterRejectsAnotherPick() {
    confirmed(7);
    UUID team = captains().getFirst().id();
    for (int i = 2; i < 6; i++) games.pick(owner, game, team, players.get(i));
    assertThatThrownBy(() -> games.pick(owner, game, team, players.get(6)))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("completo");
  }

  @Test
  void cancelledAndStartedGamesAreReadOnly() {
    games.cancel(owner, game);
    assertThatThrownBy(() -> games.attend(owner, game)).isInstanceOf(
      ApiException.class
    );
    UUID other = games
      .create(
        owner,
        club,
        new CreateGame("Outra", "Quadra", Instant.now().plusSeconds(3600), 2, 5)
      )
      .game()
      .id();
    tx.executeWithoutResult(
      s -> store.get(Game.class, other).startsAt = Instant.now().minusSeconds(5)
    );
    assertThatThrownBy(() -> games.attend(owner, other)).isInstanceOf(
      ApiException.class
    );
    assertThat(games.get(owner, other).game().editable()).isFalse();
  }

  @Test
  void demoGroupRejectsEvenItsOwner() {
    tx.executeWithoutResult(s -> store.get(Club.class, club).demo = true);
    assertThatThrownBy(() -> games.attend(owner, game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("demonstração");
    assertThatThrownBy(() -> games.cancel(owner, game)).isInstanceOf(
      ApiException.class
    );
  }

  void scheduledTimeHasPassed() {
    tx.executeWithoutResult(
      s -> store.get(Game.class, game).startsAt = Instant.now().minusSeconds(2)
    );
  }

  @Test
  void liveStartRequiresTimeAndAllConfirmedAssigned() {
    confirmed(3);
    var teams = captains();
    assertThatThrownBy(() -> matches.start(players.get(2), game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("horário");
    scheduledTimeHasPassed();
    assertThatThrownBy(() -> matches.start(players.get(2), game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("Distribua");
    games.pick(owner, game, teams.getFirst().id(), players.get(2));
    matches.start(players.get(3), game);
    assertThat(games.get(owner, game).game().matchStatus()).isEqualTo("LIVE");
    assertThatThrownBy(() -> games.cancel(owner, game)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() ->
      games.pick(owner, game, teams.getFirst().id(), players.get(4))
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> games.attend(players.get(4), game)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void onlyOneSimultaneousStartWins() throws Exception {
    confirmed(2);
    captains();
    scheduledTimeHasPassed();
    var results = race(i -> matches.start(players.get(i), game));
    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
    assertThat(games.get(owner, game).game().matchStartedAt()).isNotNull();
  }

  @Test
  void ownGoalsVoidsAndOrganizerCorrectionKeepFrozenClock() {
    confirmed(2);
    var teams = captains();
    scheduledTimeHasPassed();
    matches.start(owner, game);
    matches.goal(
      owner,
      game,
      new NewGoal(teams.get(1).id(), owner, true, null)
    );
    assertThat(matches.goals(storeGetGame()).getFirst().ownGoal()).isTrue();
    UUID goalId = matches.goals(storeGetGame()).getFirst().id();
    assertThatThrownBy(() ->
      matches.goal(
        owner,
        game,
        new NewGoal(teams.get(1).id(), owner, false, null)
      )
    ).isInstanceOf(ApiException.class);
    matches.voidGoal(players.get(1), game, goalId);
    assertThat(matches.goals(storeGetGame()).getFirst().voided()).isTrue();
    matches.finish(owner, game);
    assertThatThrownBy(() ->
      matches.goal(
        owner,
        game,
        new NewGoal(teams.get(0).id(), owner, false, null)
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      matches.correction(players.get(1), game, true)
    ).isInstanceOf(ApiException.class);
    matches.correction(owner, game, true);
    matches.duration(owner, game, 3120);
    matches.goal(owner, game, new NewGoal(teams.get(0).id(), owner, false, 42));
    matches.correction(owner, game, false);
    var result = games.get(owner, game);
    assertThat(result.game().matchDurationSeconds()).isEqualTo(3120);
    assertThat(
      result
        .goals()
        .stream()
        .filter(g -> !g.voided)
        .count()
    ).isEqualTo(1);
    assertThat(result.goals().getLast().minute()).isEqualTo(42);
  }

  @Test
  void ratingsArePrivateUntilDeadlineAndOverallUsesEqualGameWeight() {
    confirmed(3);
    var teams = captains();
    games.pick(owner, game, teams.getFirst().id(), players.get(2));
    scheduledTimeHasPassed();
    matches.start(owner, game);
    matches.finish(owner, game);
    assertThatThrownBy(() ->
      matches.rate(owner, game, new SaveRating(owner, 5))
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      matches.rate(players.get(1), game, new SaveRating(owner, 5))
    ).isInstanceOf(ApiException.class);
    matches.rate(players.get(2), game, new SaveRating(owner, 3));
    matches.rate(players.get(2), game, new SaveRating(owner, 5));
    assertThat(games.get(players.get(1), game).ratings()).isEmpty();
    assertThat(games.get(players.get(2), game).myRatings()).hasSize(1);
    tx.executeWithoutResult(
      s ->
        store.get(Game.class, game).matchEndedAt = Instant.now().minusSeconds(
          86401
        )
    );
    assertThat(
      games
        .get(players.get(1), game)
        .ratings()
        .stream()
        .filter(r -> r.playerId().equals(owner))
        .findFirst()
        .orElseThrow()
        .average()
    ).isEqualTo(5.0);
    assertThatThrownBy(() ->
      matches.rate(players.get(2), game, new SaveRating(owner, 4))
    ).isInstanceOf(ApiException.class);

    UUID second = games
      .create(
        owner,
        club,
        new CreateGame(
          "Outra pelada",
          "Quadra 2",
          Instant.now().plusSeconds(3600),
          2,
          5
        )
      )
      .game()
      .id();
    tx.executeWithoutResult(s -> {
      Game other = store.get(Game.class, second);
      other.matchStartedAt = Instant.now().minusSeconds(90000);
      other.matchEndedAt = Instant.now().minusSeconds(86401);
      other.matchDurationSeconds = 1200;
      store.save(
        new Rating(
          second,
          players.get(1),
          owner,
          1,
          Instant.now().minusSeconds(89000)
        )
      );
      store.save(
        new Rating(
          second,
          players.get(2),
          owner,
          3,
          Instant.now().minusSeconds(89000)
        )
      );
    });
    var profile = matches.profile(players.get(1), owner);
    assertThat(profile.average()).isEqualTo(3.5); // (5 + (1+3)/2) / 2
    assertThat(profile.ratedGames()).isEqualTo(2);
    UUID outsider = tx.execute(
      s ->
        store.save(new Player("Fora", "outsider@test.invalid", "!disabled")).id
    );
    assertThatThrownBy(() -> matches.profile(outsider, owner)).isInstanceOf(
      ApiException.class
    );
  }

  private Game storeGetGame() {
    return tx.execute(s -> store.get(Game.class, game));
  }

  private List<Throwable> race(IntConsumer operation) throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2),
        start = new CountDownLatch(1);
      List<Future<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        futures.add(
          executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
              operation.accept(index);
              return null;
            } catch (Throwable e) {
              return e;
            }
          })
        );
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Throwable> results = new ArrayList<>();
      for (var f : futures) results.add(f.get(15, TimeUnit.SECONDS));
      return results;
    }
  }
}
