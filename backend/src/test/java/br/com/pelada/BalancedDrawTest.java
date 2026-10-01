package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.*;
import br.com.pelada.groups.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class BalancedDrawTest {

  @Autowired
  Draws draws;

  @Autowired
  Games games;

  @Autowired
  Groups groups;

  @Autowired
  GroupPlayers classifications;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  List<UUID> people;
  UUID owner, club, game;

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE players CASCADE");
    people = tx.execute(s -> {
      List<UUID> result = new ArrayList<>();
      for (int i = 0; i < 13; i++) result.add(
        store
          .save(
            new Player("Pessoa " + i, "draw" + i + "@test.invalid", "!disabled")
          )
          .id
      );
      return result;
    });
    owner = people.getFirst();
    ClubView c = groups.create(owner, new CreateClub("Sorteio", ""));
    club = c.id();
    for (UUID p : people.subList(0, 12)) groups.join(p, c.invite());
    game = games
      .create(
        owner,
        club,
        new CreateGame(
          "Treino",
          "Quadra",
          Instant.now().plusSeconds(86400),
          2,
          5
        )
      )
      .game()
      .id();
  }

  void attend(int n) {
    for (UUID p : people.subList(0, n)) games.attend(p, game);
  }

  void classify(int index, String position, int level) {
    classifications.classify(
      owner,
      club,
      people.get(index),
      new ClassificationInput(position, null, level)
    );
  }

  void status(Runnable action, int code) {
    assertThatThrownBy(action::run)
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", code);
  }

  @Test
  void classificationsArePerGroupAndRestrictedAndCanBeCleared() {
    classify(0, "DEFENSE", 4);
    ClubView second = groups.create(owner, new CreateClub("Outro grupo", ""));
    assertThat(
      classifications.list(owner, second.id()).getFirst().skillLevel()
    ).isNull();
    assertThat(classifications.list(people.get(1), club)).hasSize(12);
    status(() -> classifications.list(people.get(12), club), 403);
    status(
      () ->
        classifications.classify(
          people.get(1),
          club,
          owner,
          new ClassificationInput("ATTACK", null, 5)
        ),
      403
    );
    status(
      () ->
        classifications.classify(
          owner,
          club,
          people.get(12),
          new ClassificationInput("ATTACK", null, 5)
        ),
      404
    );
    status(
      () ->
        classifications.classify(
          owner,
          club,
          owner,
          new ClassificationInput("ATTACK", "ATTACK", 5)
        ),
      400
    );
    status(
      () ->
        classifications.classify(
          owner,
          club,
          owner,
          new ClassificationInput("ATTACK", null, null)
        ),
      400
    );
    status(
      () ->
        classifications.classify(
          owner,
          club,
          owner,
          new ClassificationInput("BAD", null, 3)
        ),
      400
    );
    classifications.classify(
      owner,
      club,
      owner,
      new ClassificationInput(null, null, null)
    );
    assertThat(
      classifications
        .list(owner, club)
        .stream()
        .filter(p -> p.playerId().equals(owner))
        .findFirst()
        .orElseThrow()
        .skillLevel()
    ).isNull();
  }

  @Test
  void previewDoesNotMutateAndBalancedKeepsCaptainsExcludesWaitAndStoresSnapshot() {
    attend(12);
    List<TeamView> teams = games.get(owner, game).teams();
    games.configure(
      owner,
      game,
      teams.get(0).id(),
      new UpdateTeam("Verde", "#d4f25a", people.get(0))
    );
    games.configure(
      owner,
      game,
      teams.get(1).id(),
      new UpdateTeam("Azul", "#4588dd", people.get(1))
    );
    for (int i = 0; i < 10; i++) classify(
      i,
      i < 2 ? "GOALKEEPER" : i % 2 == 0 ? "DEFENSE" : "ATTACK",
      i < 5 ? 5 : 1
    );
    GameDetail before = games.get(owner, game);
    DrawPreview preview = draws.preview(owner, game, new DrawInput("BALANCED"));
    assertThat(games.get(owner, game).attendees()).isEqualTo(
      before.attendees()
    );
    assertThat(games.get(owner, game).teams()).isEqualTo(before.teams());
    assertThat(preview.unclassified()).isZero();
    assertThat(preview.teams()).allSatisfy(t ->
      assertThat(t.players()).hasSize(5)
    );
    assertThat(
      preview
        .teams()
        .stream()
        .flatMap(t -> t.players().stream())
        .map(DrawPlayer::playerId)
        .toList()
    )
      .doesNotHaveDuplicates()
      .hasSize(10)
      .doesNotContain(people.get(10), people.get(11));
    assertThat(
      Math.abs(
        preview.teams().get(0).estimatedAverage() -
          preview.teams().get(1).estimatedAverage()
      )
    ).isLessThanOrEqualTo(0.81);
    for (int i = 0; i < 2; i++) {
      UUID captain = people.get(i);
      assertThat(preview.teams().get(i).players()).anyMatch(
        p -> p.playerId().equals(captain) && p.fixed()
      );
    }
    GameDetail applied = draws.apply(owner, game, preview.id());
    assertThat(
      applied
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allMatch(p -> p.teamId() != null);
    status(() -> draws.apply(owner, game, preview.id()), 409);
    classify(0, "MIDFIELD", 1);
    assertThat(draws.history(owner, game).getFirst().preview()).isEqualTo(
      preview
    );
  }

  @Test
  void neutralLevelsOddCountsAndRandomRedraw() {
    attend(7);
    DrawPreview first = draws.preview(owner, game, new DrawInput("RANDOM"));
    assertThat(first.unclassified()).isEqualTo(7);
    assertThat(first.teams()).allSatisfy(t ->
      assertThat(t.estimatedAverage()).isEqualTo(3)
    );
    assertThat(
      first
        .teams()
        .stream()
        .map(t -> t.players().size())
    ).containsExactlyInAnyOrder(3, 4);
    DrawPreview next = draws.preview(owner, game, new DrawInput("BALANCED"));
    assertThat(next.id()).isNotEqualTo(first.id());
    status(() -> draws.apply(owner, game, first.id()), 404);
    draws.apply(owner, game, next.id());
    assertThat(draws.history(owner, game)).hasSize(1);
  }

  @Test
  void staleAttendanceClassificationAndLineupPreviewsAreRejected() {
    attend(6);
    DrawPreview first = draws.preview(owner, game, new DrawInput("BALANCED"));
    games.leave(people.get(5), game);
    status(() -> draws.apply(owner, game, first.id()), 409);
    DrawPreview second = draws.preview(owner, game, new DrawInput("BALANCED"));
    classify(0, "DEFENSE", 4);
    status(() -> draws.apply(owner, game, second.id()), 409);
    DrawPreview third = draws.preview(owner, game, new DrawInput("BALANCED"));
    TeamView team = games.get(owner, game).teams().getFirst();
    games.configure(
      owner,
      game,
      team.id(),
      new UpdateTeam("Nome novo", team.color(), owner)
    );
    status(() -> draws.apply(owner, game, third.id()), 409);
    assertThat(draws.history(owner, game)).isEmpty();
  }

  @Test
  void expiryPermissionsCancellationAndMatchStartBlockApply() {
    attend(6);
    status(
      () -> draws.preview(people.get(1), game, new DrawInput("BALANCED")),
      403
    );
    status(() -> draws.history(people.get(12), game), 403);
    DrawPreview preview = draws.preview(owner, game, new DrawInput("BALANCED"));
    jdbc.update(
      "update draw_attempts set expires_at=now()-interval '1 second' where id=?",
      preview.id()
    );
    status(() -> draws.apply(owner, game, preview.id()), 409);
    DrawPreview next = draws.preview(owner, game, new DrawInput("BALANCED"));
    draws.apply(owner, game, next.id());
    DrawPreview pending = draws.preview(owner, game, new DrawInput("BALANCED"));
    jdbc.update("update games set match_started_at=now() where id=?", game);
    status(() -> draws.apply(owner, game, pending.id()), 409);
    status(() -> draws.preview(owner, game, new DrawInput("RANDOM")), 409);
    jdbc.update(
      "update games set match_started_at=null,cancelled=true where id=?",
      game
    );
    status(() -> draws.apply(owner, game, pending.id()), 409);
  }

  @Test
  void simultaneousApplicationsApplyOnce() throws Exception {
    attend(10);
    DrawPreview preview = draws.preview(owner, game, new DrawInput("BALANCED"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Integer> apply = () -> {
      start.await();
      try {
        draws.apply(owner, game, preview.id());
        return 200;
      } catch (ApiException e) {
        return e.status;
      }
    };
    try {
      Future<Integer> a = pool.submit(apply),
        b = pool.submit(apply);
      start.countDown();
      assertThat(
        List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
      ).containsExactlyInAnyOrder(200, 409);
      assertThat(draws.history(owner, game)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sixTeamsAndIncompleteRostersRemainWithinCapacity() {
    UUID many = games
      .create(
        owner,
        club,
        new CreateGame(
          "Seis times",
          "Quadra",
          Instant.now().plusSeconds(86400),
          6,
          5
        )
      )
      .game()
      .id();
    for (UUID p : people.subList(0, 11)) games.attend(p, many);
    DrawPreview preview = draws.preview(owner, many, new DrawInput("BALANCED"));
    assertThat(preview.teams())
      .hasSize(6)
      .allSatisfy(t -> assertThat(t.players().size()).isBetween(1, 2));
    assertThat(
      preview
        .teams()
        .stream()
        .flatMap(t -> t.players().stream())
    ).hasSize(11);
    draws.apply(owner, many, preview.id());
  }
}
