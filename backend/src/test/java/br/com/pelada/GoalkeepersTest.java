package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.domain.Store;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import br.com.pelada.social.Goalkeepers;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class GoalkeepersTest {

  private static final String SAO_PAULO = "3550308";
  private static final String GUARULHOS = "3518800";
  private static final String MANAUS = "1302603";

  @Autowired
  Goalkeepers goalkeepers;

  @Autowired
  Games games;

  @Autowired
  Matches matches;

  @Autowired
  Groups groups;

  @Autowired
  Finance finance;

  @Autowired
  Store store;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  UUID organizer, keeper, secondKeeper, stranger, rival, club, rivalClub, game;
  List<UUID> members;

  @BeforeEach
  void setup() {
    jdbc.execute(
      "TRUNCATE spring_session,finance_receipt_files,finance_charges,goalkeeper_invites,goalkeeper_profiles,ratings,goals,participations,teams,game_series,games,social_messages,social_matches,social_listings,members,clubs,players CASCADE"
    );
    List<UUID> people = tx.execute(status -> {
      List<UUID> created = new ArrayList<>();
      for (int i = 0; i < 16; i++) created.add(
        store
          .save(
            new Player("Pessoa " + i, "gk" + i + "@test.invalid", "!disabled")
          )
          .id
      );
      return created;
    });
    organizer = people.get(0);
    keeper = people.get(1);
    secondKeeper = people.get(2);
    stranger = people.get(3);
    rival = people.get(4);
    members = people.subList(5, 16);
    ClubView view = groups.create(
      organizer,
      new CreateClub("Pelada do Parque", "")
    );
    club = view.id();
    for (UUID member : members) groups.join(member, view.invite());
    rivalClub = groups.create(rival, new CreateClub("Outro grupo", "")).id();
    game = newGame(club, organizer, false);
    tx.executeWithoutResult(s -> {
      store.get(Player.class, keeper).name = "Rafa Goleiro";
      store.get(Player.class, secondKeeper).name = "Dida Reserva";
    });
  }

  @Test
  void pausedProfileIsHiddenAndPublishedProfileMatchesCompatibleFilters() {
    goalkeepers.saveProfile(keeper, profile(SAO_PAULO, "INTERMEDIATE", false));
    assertThat(search(organizer, SAO_PAULO, 10, null)).isEmpty();

    goalkeepers.saveProfile(keeper, profile(GUARULHOS, "INTERMEDIATE", true));
    goalkeepers.saveProfile(
      secondKeeper,
      profile(MANAUS, "INTERMEDIATE", true)
    );
    goalkeepers.saveProfile(
      organizer,
      profile(SAO_PAULO, "INTERMEDIATE", true)
    );

    List<GoalkeeperSearchResult> nearby = goalkeepers.search(
      organizer,
      SAO_PAULO,
      50,
      "INTERMEDIATE",
      List.of("SAT"),
      List.of("EVENING")
    );
    assertThat(nearby)
      .extracting(GoalkeeperSearchResult::name)
      .containsExactly("Rafa Goleiro");
    GoalkeeperSearchResult result = nearby.getFirst();
    assertThat(result.municipalityName()).isEqualTo("Guarulhos");
    assertThat(result.uf()).isEqualTo("SP");
    assertThat(result.distanceKm()).isPositive().isLessThan(50);
    assertThat(result.scheduleCompatible()).isTrue();
    assertThat(result.averageRating()).isNull();
    assertThat(result.ratedGames()).isZero();
    assertThat(result.toString()).doesNotContain("@test.invalid");

    assertThat(
      goalkeepers.search(organizer, SAO_PAULO, 50, "COMPETITIVE", null, null)
    ).isEmpty();
    assertThat(
      goalkeepers.search(organizer, SAO_PAULO, 50, null, List.of("MON"), null)
    ).isEmpty();
    assertThat(search(organizer, SAO_PAULO, 200, null))
      .extracting(GoalkeeperSearchResult::name)
      .doesNotContain("Dida Reserva", "Pessoa 0");
    assertThatThrownBy(() -> search(organizer, SAO_PAULO, 30, null))
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 400);
  }

  @Test
  void ratedGoalkeeperShowsOnlyOverallAverageAndCount() {
    goalkeepers.saveProfile(keeper, profile(SAO_PAULO, "COMPETITIVE", true));
    UUID rated = newGame(club, organizer, false);
    tx.executeWithoutResult(s -> {
      Game old = store.get(Game.class, rated);
      old.matchStartedAt = Instant.now().minus(Duration.ofDays(3));
      old.matchEndedAt = Instant.now().minus(Duration.ofDays(2));
      old.matchDurationSeconds = 3600;
      store.save(new Rating(rated, members.get(0), keeper, 5, Instant.now()));
      store.save(new Rating(rated, members.get(1), keeper, 4, Instant.now()));
    });

    GoalkeeperSearchResult result = search(
      organizer,
      SAO_PAULO,
      10,
      null
    ).getFirst();
    assertThat(result.averageRating()).isEqualTo(4.5);
    assertThat(result.ratedGames()).isEqualTo(1);
    assertThat(goalkeepers.mine(keeper).profile().averageRating()).isEqualTo(
      4.5
    );
    // The detailed profile keeps requiring a shared group.
    assertStatus(() -> matches.profile(organizer, keeper), 403);
  }

  @Test
  void anyPlayerManagesOnlyTheirOwnProfileAndOnlyOrganizersSearchOrInvite() {
    MyGoalkeeperProfile empty = goalkeepers.mine(keeper);
    assertThat(empty.profile()).isNull();
    assertThat(empty.organizer()).isFalse();
    assertThat(goalkeepers.mine(organizer).organizer()).isTrue();

    goalkeepers.saveProfile(keeper, profile(SAO_PAULO, "RECREATIONAL", true));
    goalkeepers.saveProfile(stranger, profile(MANAUS, "COMPETITIVE", false));
    assertThat(goalkeepers.mine(keeper).profile().skillLevel()).isEqualTo(
      "RECREATIONAL"
    );
    assertThat(goalkeepers.mine(keeper).profile().published()).isTrue();

    goalkeepers.saveProfile(keeper, profile(SAO_PAULO, "RECREATIONAL", false));
    assertThat(goalkeepers.mine(keeper).profile().published()).isFalse();
    goalkeepers.removeProfile(keeper);
    assertThat(goalkeepers.mine(keeper).profile()).isNull();
    assertThat(
      goalkeepers.mine(stranger).profile().municipalityCode()
    ).isEqualTo(MANAUS);

    UUID profileId = publish(secondKeeper);
    assertStatus(() -> search(keeper, SAO_PAULO, 10, null), 403);
    assertStatus(() -> search(members.getFirst(), SAO_PAULO, 10, null), 403);
    assertStatus(() -> goalkeepers.inviteOptions(keeper), 403);
    assertStatus(() -> goalkeepers.sent(keeper), 403);
    assertStatus(() -> invite(members.getFirst(), profileId, 0), 403);
    // Owning another group does not allow inviting to this one.
    assertStatus(() -> invite(rival, profileId, 0), 403);
    assertThat(goalkeepers.received(keeper)).isEmpty();
  }

  @Test
  void inviteOnlyAcceptsFutureEditableGamesAndTeamsWithAFreeGoal() {
    UUID profileId = publish(keeper);
    assertStatus(
      () ->
        goalkeepers.invite(
          organizer,
          new GoalkeeperInviteInput(profileId, game, UUID.randomUUID(), "")
        ),
      404
    );

    UUID past = newGame(club, organizer, false);
    tx.executeWithoutResult(
      s -> store.get(Game.class, past).startsAt = Instant.now().minusSeconds(60)
    );
    assertStatus(() -> invite(organizer, profileId, past, 0), 409);

    UUID cancelled = newGame(club, organizer, false);
    games.cancel(organizer, cancelled);
    assertStatus(() -> invite(organizer, profileId, cancelled, 0), 409);

    // Team 1 already has a goalkeeper; team 2 has a full roster.
    List<TeamView> teams = teams(game);
    captain(teams.get(0).id(), members.get(0));
    games.lineup(
      members.get(0),
      game,
      teams.get(0).id(),
      new Lineup("2-2", slots(members.get(0)), version(teams.get(0).id()))
    );
    assertStatus(() -> invite(organizer, profileId, 0), 409);
    captain(teams.get(1).id(), members.get(1));
    for (int i = 2; i < 6; i++) {
      games.attend(members.get(i), game);
      games.pick(members.get(1), game, teams.get(1).id(), members.get(i));
    }
    assertStatus(() -> invite(organizer, profileId, 1), 409);
    List<GoalkeeperInviteTeamOption> options = goalkeepers
      .inviteOptions(organizer)
      .getFirst()
      .games()
      .stream()
      .filter(option -> option.gameId().equals(game))
      .findFirst()
      .orElseThrow()
      .teams();
    assertThat(options)
      .extracting(GoalkeeperInviteTeamOption::available)
      .containsExactly(false, false);
    assertThat(goalkeepers.sent(organizer)).isEmpty();

    goalkeepers.saveProfile(keeper, profile(SAO_PAULO, "INTERMEDIATE", false));
    UUID open = newGame(club, organizer, false);
    assertStatus(() -> invite(organizer, profileId, open, 0), 404);
  }

  @Test
  void pendingInviteReservesTheGoalAgainstConcurrentInvitesPresenceAndLineups()
    throws Exception {
    UUID first = publish(keeper);
    UUID second = publish(secondKeeper);
    List<TeamView> teams = teams(game);
    UUID reservedTeam = teams.get(0).id();

    List<Throwable> race = race(i ->
      invite(organizer, i == 0 ? first : second, 0)
    );
    assertThat(race.stream().filter(Objects::isNull)).hasSize(1);
    assertThat(race.stream().filter(Objects::nonNull))
      .singleElement()
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 409);
    GoalkeeperInviteView pending = goalkeepers.sent(organizer).getFirst();
    assertThat(pending.status()).isEqualTo("PENDING");
    Instant deadline = pending.expiresAt();
    assertThat(deadline).isBefore(
      Instant.now().plus(Duration.ofHours(24).plusSeconds(5))
    );
    assertThat(deadline).isAfter(Instant.now().plus(Duration.ofHours(23)));

    UUID pendingKeeper = pending.goalkeeperName().equals("Rafa Goleiro")
      ? keeper
      : secondKeeper;
    UUID otherProfile = pendingKeeper.equals(keeper) ? second : first;
    assertStatus(() -> invite(organizer, otherProfile, 0), 409);
    UUID pendingProfile = pendingKeeper.equals(keeper) ? first : second;
    assertStatus(() -> invite(organizer, pendingProfile, 1), 409);

    GameDetail detail = games.get(organizer, game);
    assertThat(detail.goalkeeperReservations())
      .singleElement()
      .satisfies(reservation -> {
        assertThat(reservation.teamId()).isEqualTo(reservedTeam);
        assertThat(reservation.goalkeeperName()).isEqualTo(
          pending.goalkeeperName()
        );
        assertThat(reservation.expiresAt()).isEqualTo(deadline);
      });

    // The captain cannot fill the reserved goal, but may still use the other positions.
    captain(reservedTeam, members.get(0));
    assertStatus(
      () ->
        games.lineup(
          members.get(0),
          game,
          reservedTeam,
          new Lineup("2-2", slots(members.get(0)), version(reservedTeam))
        ),
      409
    );
    games.lineup(
      members.get(0),
      game,
      reservedTeam,
      new Lineup(
        "2-2",
        Arrays.asList(null, members.get(0), null, null, null),
        version(reservedTeam)
      )
    );

    // The reserved goal counts toward the team size and the game capacity.
    for (int i = 1; i < 4; i++) {
      games.attend(members.get(i), game);
      games.pick(members.get(0), game, reservedTeam, members.get(i));
    }
    games.attend(members.get(4), game);
    assertStatus(
      () -> games.pick(members.get(0), game, reservedTeam, members.get(4)),
      409
    );
    for (int i = 5; i < 9; i++) games.attend(members.get(i), game);
    games.attend(members.get(9), game);
    GameDetail full = games.get(organizer, game);
    assertThat(full.game().confirmed()).isEqualTo(9);
    assertThat(status(full, members.get(9))).isEqualTo("WAITING");

    games.draw(organizer, game);
    GameDetail drawn = games.get(organizer, game);
    assertThat(
      drawn
        .attendees()
        .stream()
        .filter(a -> reservedTeam.equals(a.teamId()))
    ).hasSize(4);
    assertThat(
      drawn
        .attendees()
        .stream()
        .filter(a -> Integer.valueOf(0).equals(a.slot()))
    ).isEmpty();
  }

  @Test
  void acceptingPlacesTheGuestInGoalWithoutMembershipOrCharges() {
    UUID paid = newGame(club, organizer, true);
    UUID profileId = publish(keeper);
    List<TeamView> teams = teams(paid);
    GoalkeeperInviteView invite = invite(organizer, profileId, paid, 1);
    assertThat(invite.canAccept()).isFalse();
    assertStatus(() -> goalkeepers.accept(organizer, invite.id()), 403);

    GoalkeeperInviteView accepted = goalkeepers.accept(keeper, invite.id());
    assertThat(accepted.status()).isEqualTo("ACCEPTED");
    assertThat(accepted.canWithdraw()).isTrue();
    assertThat(accepted.canOpenGame()).isTrue();

    GameDetail seen = games.get(keeper, paid);
    assertThat(seen.viewerGuest()).isTrue();
    assertThat(seen.club().invite()).isNull();
    assertThat(seen.club().pixInstructions()).isEmpty();
    assertThat(seen.game().occasionalAmountCents()).isNull();
    Attendee guest = seen
      .attendees()
      .stream()
      .filter(a -> a.id().equals(keeper))
      .findFirst()
      .orElseThrow();
    assertThat(guest.status()).isEqualTo("CONFIRMED");
    assertThat(guest.teamId()).isEqualTo(teams.get(1).id());
    assertThat(guest.slot()).isZero();
    assertThat(guest.guestGoalkeeper()).isTrue();
    assertThat(seen.goalkeeperReservations()).isEmpty();
    assertThat(groups.isMember(keeper, club)).isFalse();
    assertThat(
      jdbc.queryForObject(
        "select count(*) from members where player_id = ?",
        Long.class,
        keeper
      )
    ).isZero();

    // Access stays limited to the accepted match.
    assertStatus(() -> games.get(keeper, game), 403);
    assertStatus(() -> games.list(keeper, club), 403);
    assertStatus(() -> finance.summary(keeper, club, null, null, null), 403);
    assertStatus(() -> games.attend(keeper, game), 403);
    assertThat(groups.list(keeper)).isEmpty();

    // The guest stays in goal through draws; captains cannot move or release them.
    captain(teams.get(1).id(), members.get(0), paid);
    assertStatus(() -> captain(teams.get(0).id(), keeper, paid), 400);
    assertStatus(
      () ->
        games.lineup(
          members.get(0),
          paid,
          teams.get(1).id(),
          new Lineup(
            "2-2",
            slots(members.get(0), keeper),
            version(teams.get(1).id(), paid)
          )
        ),
      409
    );
    assertStatus(
      () -> games.release(members.get(0), paid, teams.get(1).id(), keeper),
      409
    );
    for (int i = 1; i < 4; i++) games.attend(members.get(i), paid);
    games.draw(organizer, paid);
    Attendee afterDraw = games
      .get(organizer, paid)
      .attendees()
      .stream()
      .filter(a -> a.id().equals(keeper))
      .findFirst()
      .orElseThrow();
    assertThat(afterDraw.teamId()).isEqualTo(teams.get(1).id());
    assertThat(afterDraw.slot()).isZero();

    tx.executeWithoutResult(
      s -> store.get(Game.class, paid).startsAt = Instant.now().minusSeconds(5)
    );
    matches.start(members.get(0), paid);
    List<UUID> charged = jdbc.queryForList(
      "select player_id from finance_charges where game_id = ?",
      UUID.class,
      paid
    );
    assertThat(charged).hasSize(4).doesNotContain(keeper);
    assertThat(goalkeepers.received(keeper).getFirst().canWithdraw()).isFalse();
    assertStatus(() -> goalkeepers.withdraw(keeper, invite.id()), 409);
  }

  @Test
  void declineCancelExpiryAndWithdrawalReleaseTheGoalAndKeepHistory() {
    UUID first = publish(keeper);
    UUID second = publish(secondKeeper);
    // Fill the game so the reservation holds the last spot.
    for (int i = 0; i < 9; i++) games.attend(members.get(i), game);
    GoalkeeperInviteView declined = invite(organizer, first, 0);
    games.attend(members.get(9), game);
    assertThat(status(games.get(organizer, game), members.get(9))).isEqualTo(
      "WAITING"
    );

    assertThat(goalkeepers.decline(keeper, declined.id()).status()).isEqualTo(
      "DECLINED"
    );
    assertThat(status(games.get(organizer, game), members.get(9))).isEqualTo(
      "CONFIRMED"
    );
    assertThat(games.get(organizer, game).goalkeeperReservations()).isEmpty();
    games.leave(members.get(9), game);

    GoalkeeperInviteView cancelled = invite(organizer, second, 0);
    assertStatus(() -> goalkeepers.cancel(keeper, cancelled.id()), 403);
    GoalkeeperInviteView byOrganizer = goalkeepers.cancel(
      organizer,
      cancelled.id()
    );
    assertThat(byOrganizer.status()).isEqualTo("CANCELLED");
    assertThat(byOrganizer.outcome()).contains("organizador cancelou");
    assertStatus(() -> goalkeepers.accept(secondKeeper, cancelled.id()), 409);

    GoalkeeperInviteView expiring = invite(organizer, first, 0);
    games.attend(members.get(9), game);
    jdbc.update(
      "UPDATE goalkeeper_invites SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour' WHERE id = ?",
      expiring.id()
    );
    GoalkeeperInviteView expired = goalkeepers
      .received(keeper)
      .stream()
      .filter(item -> item.id().equals(expiring.id()))
      .findFirst()
      .orElseThrow();
    assertThat(expired.status()).isEqualTo("EXPIRED");
    assertThat(expired.statusReason()).isEqualTo("DEADLINE");
    assertThat(expired.canAccept()).isFalse();
    assertThat(status(games.get(organizer, game), members.get(9))).isEqualTo(
      "CONFIRMED"
    );
    assertStatus(() -> goalkeepers.accept(keeper, expiring.id()), 409);

    games.leave(members.get(9), game);
    GoalkeeperInviteView withdrawn = invite(organizer, second, 1);
    goalkeepers.accept(secondKeeper, withdrawn.id());
    games.attend(members.get(9), game);
    assertThat(status(games.get(organizer, game), members.get(9))).isEqualTo(
      "WAITING"
    );
    GoalkeeperInviteView left = goalkeepers.withdraw(
      secondKeeper,
      withdrawn.id()
    );
    assertThat(left.status()).isEqualTo("WITHDRAWN");
    assertThat(left.canOpenGame()).isFalse();
    assertStatus(() -> games.get(secondKeeper, game), 403);
    GameDetail after = games.get(organizer, game);
    assertThat(status(after, members.get(9))).isEqualTo("CONFIRMED");
    assertThat(after.attendees()).noneMatch(a -> a.guestGoalkeeper());

    // Every invite stays in the history; a new invite can use the released goal.
    assertThat(goalkeepers.sent(organizer))
      .extracting(GoalkeeperInviteView::status)
      .containsExactlyInAnyOrder(
        "DECLINED",
        "CANCELLED",
        "EXPIRED",
        "WITHDRAWN"
      );
    games.leave(members.get(9), game);
    assertThat(invite(organizer, first, 1).status()).isEqualTo("PENDING");
  }

  @Test
  void cancelledOrStartedGamesCloseThePendingInviteWithAClearOutcome() {
    UUID profileId = publish(keeper);
    GoalkeeperInviteView pending = invite(organizer, profileId, 0);
    games.cancel(organizer, game);
    GoalkeeperInviteView closed = goalkeepers.received(keeper).getFirst();
    assertThat(closed.status()).isEqualTo("CANCELLED");
    assertThat(closed.statusReason()).isEqualTo("GAME_CANCELLED");
    assertThat(closed.outcome()).contains("pelada foi cancelada");
    assertStatus(() -> goalkeepers.accept(keeper, pending.id()), 409);

    UUID soon = newGame(club, organizer, false);
    GoalkeeperInviteView next = invite(organizer, profileId, soon, 0);
    Instant earlier = Instant.now()
      .plus(Duration.ofHours(2))
      .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    games.update(
      organizer,
      soon,
      new UpdateGame("Pelada antecipada", "Quadra", earlier, "ONE")
    );
    assertThat(goalkeepers.sent(organizer).getFirst().expiresAt()).isEqualTo(
      earlier
    );
    tx.executeWithoutResult(
      s -> store.get(Game.class, soon).startsAt = Instant.now().minusSeconds(1)
    );
    GoalkeeperInviteView late = goalkeepers.accept(keeper, next.id());
    assertThat(late.status()).isEqualTo("EXPIRED");
    assertThat(late.outcome()).isNotBlank();
  }

  private UUID newGame(UUID clubId, UUID owner, boolean charge) {
    return games
      .create(
        owner,
        clubId,
        new CreateGame(
          "Pelada com goleiro",
          "Arena Central",
          Instant.now().plus(Duration.ofDays(3)),
          2,
          5,
          charge,
          charge ? 2000L : null
        )
      )
      .game()
      .id();
  }

  private GoalkeeperProfileInput profile(
    String city,
    String level,
    boolean published
  ) {
    return new GoalkeeperProfileInput(
      city,
      level,
      List.of("SAT", "SUN"),
      List.of("EVENING"),
      "Goleiro de reflexo rápido.",
      published
    );
  }

  private UUID publish(UUID player) {
    goalkeepers.saveProfile(player, profile(SAO_PAULO, "INTERMEDIATE", true));
    return jdbc.queryForObject(
      "select id from goalkeeper_profiles where player_id = ?",
      UUID.class,
      player
    );
  }

  private List<GoalkeeperSearchResult> search(
    UUID user,
    String city,
    int radius,
    String level
  ) {
    return goalkeepers.search(user, city, radius, level, null, null);
  }

  private GoalkeeperInviteView invite(UUID user, UUID profileId, int team) {
    return invite(user, profileId, game, team);
  }

  private GoalkeeperInviteView invite(
    UUID user,
    UUID profileId,
    UUID gameId,
    int team
  ) {
    return goalkeepers.invite(
      user,
      new GoalkeeperInviteInput(
        profileId,
        gameId,
        teams(gameId).get(team).id(),
        "Falta goleiro no sábado."
      )
    );
  }

  private List<TeamView> teams(UUID gameId) {
    return games.get(organizer, gameId).teams();
  }

  private void captain(UUID teamId, UUID player) {
    captain(teamId, player, game);
  }

  private void captain(UUID teamId, UUID player, UUID gameId) {
    if (
      games
        .get(organizer, gameId)
        .attendees()
        .stream()
        .noneMatch(a -> a.id().equals(player))
    ) games.attend(player, gameId);
    games.configure(
      organizer,
      gameId,
      teamId,
      new UpdateTeam("Time", "#d8f36a", player)
    );
  }

  private long version(UUID teamId) {
    return version(teamId, game);
  }

  private long version(UUID teamId, UUID gameId) {
    return games
      .get(organizer, gameId)
      .teams()
      .stream()
      .filter(t -> t.id().equals(teamId))
      .findFirst()
      .orElseThrow()
      .version();
  }

  private static List<UUID> slots(UUID goal, UUID... others) {
    List<UUID> slots = new ArrayList<>(
      Arrays.asList(null, null, null, null, null)
    );
    slots.set(0, goal);
    for (int i = 0; i < others.length; i++) slots.set(i + 1, others[i]);
    return slots;
  }

  private static String status(GameDetail detail, UUID player) {
    return detail
      .attendees()
      .stream()
      .filter(a -> a.id().equals(player))
      .findFirst()
      .orElseThrow()
      .status();
  }

  private static void assertStatus(ThrowingCallable call, int status) {
    assertThatThrownBy(call)
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", status);
  }

  private List<Throwable> race(IntFunction<?> operation) throws Exception {
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
              operation.apply(index);
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
