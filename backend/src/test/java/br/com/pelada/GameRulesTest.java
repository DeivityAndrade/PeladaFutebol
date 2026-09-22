package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
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
