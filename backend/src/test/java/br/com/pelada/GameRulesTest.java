package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Barbecues;
import br.com.pelada.groups.Groups;
import java.time.*;
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
  Barbecues barbecues;

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
      "TRUNCATE spring_session,barbecue_attendance,barbecues,barbecue_series,participations,teams,games,members,clubs,players CASCADE"
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
    assertThatThrownBy(() -> games.draw(players.get(2), game)).isInstanceOf(
      ApiException.class
    );
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
  void drawPinsCaptainsBalancesTeamsAndLeavesWaitlistOut() {
    confirmed(12);
    List<TeamView> teams = captains();
    UUID first = teams.getFirst().id();
    games.pick(owner, game, first, players.get(2));
    games.lineup(
      owner,
      game,
      first,
      new Lineup(
        "2-2",
        Arrays.asList(owner, players.get(2), null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );

    var result = games.draw(owner, game);

    assertThat(result.teams().get(0).captainId()).isEqualTo(owner);
    assertThat(result.teams().get(1).captainId()).isEqualTo(players.get(1));
    for (TeamView team : result.teams())
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> team.id().equals(p.teamId()))
      ).hasSize(5);
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> {
      assertThat(p.teamId()).isNotNull();
      assertThat(p.slot()).isNull();
    });
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("WAITING"))
    ).allSatisfy(p -> {
      assertThat(p.teamId()).isNull();
      assertThat(p.slot()).isNull();
    });
    games.leave(players.get(2), game);
    var promoted = games
      .get(owner, game)
      .attendees()
      .stream()
      .filter(p -> p.id().equals(players.get(10)))
      .findFirst()
      .orElseThrow();
    assertThat(promoted.status()).isEqualTo("CONFIRMED");
    assertThat(promoted.teamId()).isNull();
    games.draw(owner, game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
  }

  @Test
  void newConfirmationsWaitForManualRedrawAndStartedGameCannotBeRedrawn() {
    confirmed(3);
    List<TeamView> teams = captains();
    games.draw(owner, game);
    games.attend(players.get(3), game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(3)))
        .findFirst()
        .orElseThrow()
        .teamId()
    ).isNull();

    games.draw(owner, game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
    scheduledTimeHasPassed();
    matches.start(players.get(2), game);
    assertThatThrownBy(() -> games.draw(owner, game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("começou");
    assertThat(teams).hasSize(2);
  }

  @Test
  void concurrentDrawsLeaveEveryConfirmedPlayerOnExactlyOneTeam()
    throws Exception {
    confirmed(8);
    captains();

    var results = race(i -> games.draw(owner, game));

    assertThat(results.stream().filter(Objects::nonNull).count()).isZero();
    var result = games.get(owner, game);
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
    for (TeamView team : result.teams())
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> team.id().equals(p.teamId()))
      ).hasSize(4);
  }

  @Test
  void monthlyBarbecueKeepsMonthEndAndReplenishesAfterAnIsolatedCancellation() {
    ClubView recurring = groups.create(
      owner,
      new CreateClub("Grupo do churrasco", "", "MONTHLY")
    );
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant anchor = ZonedDateTime.of(
      2027,
      1,
      31,
      19,
      0,
      0,
      0,
      zone
    ).toInstant();

    List<BarbecueView> firstWindow = barbecues.startSeries(
      owner,
      recurring.id(),
      new CreateBarbecueSeries(anchor, "Salão", zone.getId())
    );

    assertThat(firstWindow).hasSize(6);
    assertThat(
      firstWindow
        .stream()
        .limit(3)
        .map(event -> event.startsAt().atZone(zone).toLocalDate())
    ).containsExactly(
      LocalDate.of(2027, 1, 31),
      LocalDate.of(2027, 2, 28),
      LocalDate.of(2027, 3, 31)
    );
    assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    assertThat(
      race(i -> barbecues.list(owner, recurring.id()).size())
        .stream()
        .filter(Objects::nonNull)
        .count()
    ).isZero();
    assertThat(
      barbecues.cancel(owner, firstWindow.getFirst().id()).cancelled()
    ).isTrue();

    List<BarbecueView> replenished = barbecues.list(owner, recurring.id());
    assertThat(replenished).hasSize(7);
    assertThat(
      replenished
        .stream()
        .filter(event -> event.recurring() && !event.cancelled())
    ).hasSize(6);
    assertThat(replenished.stream().map(BarbecueView::id).distinct()).hasSize(
      7
    );
  }

  @Test
  void twoAndThreeMonthBarbecueRecurrencesUseTheirAnchorDate() {
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant anchor = ZonedDateTime.of(
      2027,
      1,
      31,
      19,
      0,
      0,
      0,
      zone
    ).toInstant();
    for (String frequency : List.of("EVERY_2_MONTHS", "EVERY_3_MONTHS")) {
      ClubView recurring = groups.create(
        owner,
        new CreateClub("Grupo " + frequency, "", frequency)
      );
      List<BarbecueView> events = barbecues.startSeries(
        owner,
        recurring.id(),
        new CreateBarbecueSeries(anchor, "Salão", zone.getId())
      );
      LocalDate expected = frequency.equals("EVERY_2_MONTHS")
        ? LocalDate.of(2027, 3, 31)
        : LocalDate.of(2027, 4, 30);
      assertThat(events).hasSize(6);
      assertThat(events.get(1).startsAt().atZone(zone).toLocalDate()).isEqualTo(
        expected
      );
      assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    }
  }

  @Test
  void pauseStopsWindowGenerationAndResumeRestoresSixUpcomingEvents() {
    ClubView recurring = groups.create(
      owner,
      new CreateClub("Grupo pausável", "", "EVERY_2_MONTHS")
    );
    Instant anchor = Instant.now().plus(Duration.ofDays(45));
    List<BarbecueView> initial = barbecues.startSeries(
      owner,
      recurring.id(),
      new CreateBarbecueSeries(anchor, "Salão", "UTC")
    );
    UUID seriesId = tx.execute(
      s ->
        store
          .first(
            BarbecueSeries.class,
            "from BarbecueSeries where clubId=:club",
            "club",
            recurring.id()
          )
          .orElseThrow()
          .id
    );
    barbecues.pauseSeries(owner, recurring.id());
    tx.executeWithoutResult(s -> {
      store.get(BarbecueSeries.class, seriesId).nextOccurrenceIndex =
        initial.size();
      store
        .list(
          Barbecue.class,
          "from Barbecue where seriesId=:series",
          "series",
          seriesId
        )
        .forEach(event -> event.startsAt = Instant.now().minusSeconds(60));
    });

    assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    assertThat(
      groups
        .list(owner)
        .stream()
        .filter(c -> c.id().equals(recurring.id()))
        .findFirst()
        .orElseThrow()
        .barbecueSeriesActive()
    ).isFalse();
    List<BarbecueView> resumed = barbecues.resumeSeries(owner, recurring.id());
    assertThat(resumed).hasSize(12);
    assertThat(
      resumed.stream().filter(event -> event.startsAt().isAfter(Instant.now()))
    ).hasSize(6);
    assertThat(
      groups
        .list(owner)
        .stream()
        .filter(c -> c.id().equals(recurring.id()))
        .findFirst()
        .orElseThrow()
        .barbecueSeriesActive()
    ).isTrue();
  }

  @Test
  void barbecueGuestInviteIsEventScopedAndAttendanceIsIndependentFromFootball() {
    ClubView barbecueClub = groups.create(
      owner,
      new CreateClub("Churrasco avulso", "")
    );
    groups.join(players.get(1), barbecueClub.invite());
    Instant startsAt = Instant.now().plus(Duration.ofDays(3));
    BarbecueView invited = barbecues.createOneOff(
      owner,
      barbecueClub.id(),
      new CreateBarbecue(startsAt, "Salão")
    );
    BarbecueView other = barbecues.createOneOff(
      owner,
      barbecueClub.id(),
      new CreateBarbecue(startsAt.plus(Duration.ofDays(30)), "Quintal")
    );
    UUID outsider = tx.execute(
      s ->
        store
          .save(new Player("Convidado", "guest@test.invalid", "!disabled"))
          .id
    );

    barbecues.attendance(players.get(1), invited.id(), true);
    assertThat(games.get(players.get(1), game).attendees()).isEmpty();
    assertThatThrownBy(() ->
      barbecues.attendance(outsider, invited.id(), true)
    ).isInstanceOf(ApiException.class);
    UUID oldToken = UUID.fromString(invited.inviteToken());
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isFalse();
    barbecues.inviteAttendance(outsider, oldToken, true);
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isTrue();
    assertThat(groups.list(outsider)).isEmpty();
    assertThatThrownBy(() ->
      groups.requireMember(outsider, barbecueClub.id())
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      barbecues.list(outsider, barbecueClub.id())
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      barbecues.inviteDetails(outsider, UUID.randomUUID())
    ).isInstanceOf(ApiException.class);

    BarbecueView updated = barbecues.update(
      owner,
      other.id(),
      new UpdateBarbecue(startsAt.plus(Duration.ofDays(31)), "Outro salão")
    );
    assertThat(updated.location()).isEqualTo("Outro salão");
    assertThat(barbecues.cancel(owner, other.id()).cancelled()).isTrue();
    assertThat(barbecues.inviteDetails(outsider, oldToken)).isNotNull();
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isTrue();
    assertThatThrownBy(() ->
      barbecues.createOneOff(
        players.get(1),
        barbecueClub.id(),
        new CreateBarbecue(startsAt, "Salão")
      )
    ).isInstanceOf(ApiException.class);
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
        .filter(g -> !g.voided())
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
