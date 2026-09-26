package br.com.pelada.games;

import br.com.pelada.domain.Domain.*;
import br.com.pelada.domain.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * A pending goalkeeper invite holds the goal of one team, and one spot of the game, until the
 * goalkeeper answers. Every caller must hold the game row lock before changing invites or spots.
 */
@Component
public class GoalkeeperReservations {

  private final Store store;
  private final Clock clock;

  public GoalkeeperReservations(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Whether the game still accepts presence and roster changes before kickoff. */
  public boolean open(Game game) {
    return (
      !game.cancelled &&
      game.matchStartedAt == null &&
      game.startsAt.isAfter(clock.instant())
    );
  }

  public List<GoalkeeperInvite> active(Game game) {
    if (!open(game)) return List.of();
    return store.list(
      GoalkeeperInvite.class,
      "from GoalkeeperInvite where gameId=:game and status='PENDING' and expiresAt>:now order by createdAt",
      "game",
      game.id,
      "now",
      clock.instant()
    );
  }

  public Optional<GoalkeeperInvite> activeFor(Game game, UUID teamId) {
    return active(game)
      .stream()
      .filter(invite -> invite.teamId.equals(teamId))
      .findFirst();
  }

  /** Closes stale pending invites of a locked game and hands freed spots to the waiting list. */
  public void settle(Game game) {
    boolean changed = false;
    for (GoalkeeperInvite invite : store.list(
      GoalkeeperInvite.class,
      "from GoalkeeperInvite where gameId=:game and status='PENDING'",
      "game",
      game.id
    ))
      changed |= closeIfStale(invite, game);
    if (changed) promoteWaiting(game);
  }

  public boolean closeIfStale(GoalkeeperInvite invite, Game game) {
    if (!invite.status.equals("PENDING")) return false;
    Instant now = clock.instant();
    if (game.cancelled) {
      finish(invite, "CANCELLED", "GAME_CANCELLED");
    } else if (!invite.expiresAt.isAfter(now)) {
      finish(invite, "EXPIRED", "DEADLINE");
    } else if (game.matchStartedAt != null || !game.startsAt.isAfter(now)) {
      finish(invite, "EXPIRED", "GAME_STARTED");
    } else {
      return false;
    }
    return true;
  }

  public void finish(GoalkeeperInvite invite, String status, String reason) {
    invite.status = status;
    invite.statusReason = reason;
    invite.closedAt = clock.instant();
  }

  /** Keeps pending deadlines at or before kickoff when the organizer moves the game earlier. */
  public void clampDeadlines(Game game) {
    for (GoalkeeperInvite invite : store.list(
      GoalkeeperInvite.class,
      "from GoalkeeperInvite where gameId=:game and status='PENDING'",
      "game",
      game.id
    ))
      if (invite.expiresAt.isAfter(game.startsAt)) invite.expiresAt =
        game.startsAt;
  }

  /** Promotes the waiting list in FIFO order while confirmed players and reservations fit. */
  public void promoteWaiting(Game game) {
    if (!open(game)) return;
    List<Participation> attendees = store.list(
      Participation.class,
      "from Participation where gameId=:game order by id",
      "game",
      game.id
    );
    long taken =
      attendees
        .stream()
        .filter(p -> p.status.equals("CONFIRMED"))
        .count() + active(game).size();
    int capacity = game.teamCount * game.teamSize;
    for (Participation waiting : attendees) {
      if (taken >= capacity) break;
      if (!waiting.status.equals("WAITING")) continue;
      waiting.status = "CONFIRMED";
      taken++;
    }
  }
}
