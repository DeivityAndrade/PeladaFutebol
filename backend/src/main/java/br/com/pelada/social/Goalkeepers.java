package br.com.pelada.social;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.domain.Store;
import br.com.pelada.games.Games;
import br.com.pelada.games.GoalkeeperReservations;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Groups;
import br.com.pelada.social.MunicipalityCatalog.Municipality;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goalkeeper profiles and single-match invitations. Any player manages only their own profile;
 * only group organizers search and invite. An accepted goalkeeper joins one match, never the group.
 */
@Service
@Transactional
public class Goalkeepers {

  private static final Duration RESPONSE_WINDOW = Duration.ofHours(24);
  private static final int MAX_OPTION_GAMES = 20;

  private final Store store;
  private final Groups groups;
  private final Games games;
  private final Matches matches;
  private final GoalkeeperReservations reservations;
  private final MunicipalityCatalog municipalities;
  private final Clock clock;

  public Goalkeepers(
    Store store,
    Groups groups,
    Games games,
    Matches matches,
    GoalkeeperReservations reservations,
    MunicipalityCatalog municipalities,
    Clock clock
  ) {
    this.store = store;
    this.groups = groups;
    this.games = games;
    this.matches = matches;
    this.reservations = reservations;
    this.municipalities = municipalities;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public MyGoalkeeperProfile mine(UUID user) {
    return new MyGoalkeeperProfile(
      profileOf(user).map(this::profileView).orElse(null),
      isOrganizer(user)
    );
  }

  public GoalkeeperProfileView saveProfile(
    UUID user,
    GoalkeeperProfileInput input
  ) {
    Municipality municipality = municipalities
      .find(input.municipalityCode())
      .orElseThrow(() -> new ApiException(400, "Selecione uma cidade válida."));
    if (!Social.LEVELS.contains(input.skillLevel())) throw new ApiException(
      400,
      "Selecione um nível de jogo válido."
    );
    List<String> days = Social.normalizedFilter(
      input.preferredDays(),
      Social.DAYS,
      "Revise os dias preferidos."
    );
    List<String> periods = Social.normalizedFilter(
      input.preferredPeriods(),
      Social.PERIODS,
      "Revise os períodos preferidos."
    );
    Instant now = clock.instant();
    GoalkeeperProfile profile = profileOf(user).orElse(null);
    boolean created = profile == null;
    if (created) profile = new GoalkeeperProfile(user, now);
    profile.published = input.published();
    profile.municipalityCode = municipality.code();
    profile.skillLevel = input.skillLevel();
    profile.preferredDays = String.join(",", days);
    profile.preferredPeriods = String.join(",", periods);
    profile.description = input.description().strip();
    profile.updatedAt = now;
    if (created) store.save(profile);
    return profileView(profile);
  }

  public void removeProfile(UUID user) {
    profileOf(user).ifPresent(store::remove);
  }

  @Transactional(readOnly = true)
  public List<GoalkeeperSearchResult> search(
    UUID user,
    String cityCode,
    int radiusKm,
    String skillLevel,
    List<String> days,
    List<String> periods
  ) {
    requireOrganizer(user);
    if (!Social.RADII.contains(radiusKm)) throw new ApiException(
      400,
      "Selecione um raio de busca válido."
    );
    boolean levelFilter = skillLevel != null && !skillLevel.isBlank();
    if (
      levelFilter && !Social.LEVELS.contains(skillLevel)
    ) throw new ApiException(400, "Selecione um nível de jogo válido.");
    List<String> requestedDays = Social.normalizedFilter(
      days,
      Social.DAYS,
      "Revise os dias da busca."
    );
    List<String> requestedPeriods = Social.normalizedFilter(
      periods,
      Social.PERIODS,
      "Revise os períodos da busca."
    );
    Municipality origin = municipalities
      .find(cityCode)
      .orElseThrow(() -> new ApiException(400, "Selecione uma cidade válida."));

    record Candidate(GoalkeeperProfile profile, Municipality city, double km) {}
    return store
      .list(
        GoalkeeperProfile.class,
        "from GoalkeeperProfile where published=true and playerId<>:user",
        "user",
        user
      )
      .stream()
      .filter(p -> !levelFilter || p.skillLevel.equals(skillLevel))
      .filter(p -> Social.values(p.preferredDays).containsAll(requestedDays))
      .filter(p ->
        Social.values(p.preferredPeriods).containsAll(requestedPeriods)
      )
      .map(p -> {
        Municipality city = municipalities
          .find(p.municipalityCode)
          .orElse(null);
        if (city == null) return null;
        double km = Social.distanceKm(origin, city);
        return km > radiusKm ? null : new Candidate(p, city, km);
      })
      .filter(Objects::nonNull)
      .sorted(Comparator.comparingDouble(Candidate::km))
      .limit(100)
      .map(c -> {
        PublicRating rating = matches.publicRating(c.profile().playerId);
        return new GoalkeeperSearchResult(
          c.profile().id,
          store.get(Player.class, c.profile().playerId).name,
          c.city().name(),
          c.city().uf(),
          Math.round(c.km() * 10d) / 10d,
          c.profile().skillLevel,
          Social.values(c.profile().preferredDays),
          Social.values(c.profile().preferredPeriods),
          c.profile().description,
          rating.average(),
          rating.ratedGames(),
          !requestedDays.isEmpty() || !requestedPeriods.isEmpty()
        );
      })
      .toList();
  }

  @Transactional(readOnly = true)
  public List<GoalkeeperInviteGroupOption> inviteOptions(UUID user) {
    requireOrganizer(user);
    return ownedGroups(user)
      .stream()
      .map(club -> {
        List<GoalkeeperInviteGameOption> upcoming = store
          .list(
            Game.class,
            "from Game where clubId=:club and cancelled=false and matchStartedAt is null and startsAt>:now order by startsAt",
            "club",
            club.id,
            "now",
            clock.instant()
          )
          .stream()
          .limit(MAX_OPTION_GAMES)
          .map(game ->
            new GoalkeeperInviteGameOption(
              game.id,
              game.title,
              game.location,
              game.startsAt,
              teams(game.id)
                .stream()
                .map(team -> {
                  String blocker = goalBlocker(game, team);
                  return new GoalkeeperInviteTeamOption(
                    team.id,
                    team.name,
                    team.color,
                    blocker == null,
                    blocker
                  );
                })
                .toList()
            )
          )
          .toList();
        return new GoalkeeperInviteGroupOption(
          club.id,
          club.name,
          club.timeZone,
          upcoming
        );
      })
      .toList();
  }

  public GoalkeeperInviteView invite(UUID user, GoalkeeperInviteInput input) {
    requireOrganizer(user);
    // Invites and every roster change serialize on the game row.
    Game game = store.lock(Game.class, input.gameId());
    groups.requireOwner(user, game.clubId);
    reservations.settle(game);
    if (!reservations.open(game)) throw ApiException.conflict(
      "Escolha uma pelada futura que ainda aceite alterações."
    );
    Team team = store.get(Team.class, input.teamId());
    if (!team.gameId.equals(game.id)) throw new ApiException(
      400,
      "Escolha um time desta pelada."
    );
    GoalkeeperProfile profile = store
      .first(
        GoalkeeperProfile.class,
        "from GoalkeeperProfile where id=:id and published=true",
        "id",
        input.profileId()
      )
      .orElseThrow(ApiException::notFound);
    if (profile.playerId.equals(user)) throw new ApiException(
      400,
      "Escolha outro goleiro para convidar."
    );
    boolean playing = store
      .first(
        Participation.class,
        "from Participation where gameId=:game and playerId=:player",
        "game",
        game.id,
        "player",
        profile.playerId
      )
      .isPresent();
    if (playing) throw ApiException.conflict(
      "Esse goleiro já está na lista desta pelada."
    );
    boolean invited = store
      .first(
        GoalkeeperInvite.class,
        "from GoalkeeperInvite where gameId=:game and goalkeeperId=:player and status in ('PENDING','ACCEPTED')",
        "game",
        game.id,
        "player",
        profile.playerId
      )
      .isPresent();
    if (invited) throw ApiException.conflict(
      "Esse goleiro já tem um convite ativo para esta pelada."
    );
    String blocker = goalBlocker(game, team);
    if (blocker != null) throw ApiException.conflict(blocker);

    Instant now = clock.instant();
    Instant deadline = now.plus(RESPONSE_WINDOW);
    GoalkeeperInvite invite = new GoalkeeperInvite(
      user,
      profile.playerId,
      game.id,
      team.id,
      input.message().strip(),
      now,
      game.startsAt.isBefore(deadline) ? game.startsAt : deadline
    );
    try {
      store.save(invite);
      store.flush();
    } catch (DataIntegrityViolationException error) {
      throw ApiException.conflict(
        "Outro convite já reservou este gol. Atualize a lista e tente novamente."
      );
    }
    return view(invite, user);
  }

  public GoalkeeperInviteView accept(UUID user, UUID inviteId) {
    Locked locked = lock(inviteId);
    GoalkeeperInvite invite = locked.invite();
    Game game = locked.game();
    if (!invite.goalkeeperId.equals(user)) throw ApiException.forbidden();
    if (closeStale(invite, game)) return view(invite, user);
    if (!invite.status.equals("PENDING")) throw ApiException.conflict(
      "Este convite não está mais aguardando resposta."
    );
    boolean playing = store
      .first(
        Participation.class,
        "from Participation where gameId=:game and playerId=:player",
        "game",
        game.id,
        "player",
        user
      )
      .isPresent();
    if (playing) throw ApiException.conflict(
      "Você já está na lista desta pelada. Recuse o convite para liberar o gol."
    );
    Team team = store.get(Team.class, invite.teamId);
    List<Participation> roster = roster(game.id, team.id);
    // The reservation should keep the goal free; close the invite if it no longer does.
    if (
      roster.size() >= game.teamSize ||
      roster.stream().anyMatch(p -> Integer.valueOf(0).equals(p.slot))
    ) {
      reservations.finish(invite, "CANCELLED", "GAME_UNAVAILABLE");
      reservations.promoteWaiting(game);
      return view(invite, user);
    }
    Participation goalkeeper = new Participation(game.id, user, "CONFIRMED");
    goalkeeper.teamId = team.id;
    goalkeeper.slot = 0;
    goalkeeper.goalkeeperInviteId = invite.id;
    store.save(goalkeeper);
    team.version++;
    invite.status = "ACCEPTED";
    invite.statusReason = null;
    invite.respondedAt = clock.instant();
    return view(invite, user);
  }

  public GoalkeeperInviteView decline(UUID user, UUID inviteId) {
    Locked locked = lock(inviteId);
    GoalkeeperInvite invite = locked.invite();
    if (!invite.goalkeeperId.equals(user)) throw ApiException.forbidden();
    if (closeStale(invite, locked.game())) return view(invite, user);
    if (!invite.status.equals("PENDING")) throw ApiException.conflict(
      "Este convite não está mais aguardando resposta."
    );
    reservations.finish(invite, "DECLINED", "GOALKEEPER");
    invite.respondedAt = invite.closedAt;
    reservations.promoteWaiting(locked.game());
    return view(invite, user);
  }

  public GoalkeeperInviteView cancel(UUID user, UUID inviteId) {
    Locked locked = lock(inviteId);
    GoalkeeperInvite invite = locked.invite();
    if (!canManage(user, invite, locked.game())) throw ApiException.forbidden();
    if (closeStale(invite, locked.game())) return view(invite, user);
    if (!invite.status.equals("PENDING")) throw ApiException.conflict(
      "Só é possível cancelar convites que aguardam resposta."
    );
    reservations.finish(invite, "CANCELLED", "ORGANIZER");
    reservations.promoteWaiting(locked.game());
    return view(invite, user);
  }

  public GoalkeeperInviteView withdraw(UUID user, UUID inviteId) {
    GoalkeeperInvite invite = store.get(GoalkeeperInvite.class, inviteId);
    if (!invite.goalkeeperId.equals(user)) throw ApiException.forbidden();
    if (!invite.status.equals("ACCEPTED")) throw ApiException.conflict(
      "Você só pode sair de uma partida aceita."
    );
    // Leaving follows the normal attendance rules and marks the invite as withdrawn.
    games.leave(user, invite.gameId);
    return view(invite, user);
  }

  public List<GoalkeeperInviteView> received(UUID user) {
    settleStale("goalkeeperId", user);
    return store
      .list(
        GoalkeeperInvite.class,
        "from GoalkeeperInvite where goalkeeperId=:user order by createdAt desc",
        "user",
        user
      )
      .stream()
      .map(invite -> view(invite, user))
      .toList();
  }

  public List<GoalkeeperInviteView> sent(UUID user) {
    requireOrganizer(user);
    settleStale("organizerId", user);
    return store
      .list(
        GoalkeeperInvite.class,
        "from GoalkeeperInvite where organizerId=:user order by createdAt desc",
        "user",
        user
      )
      .stream()
      .map(invite -> view(invite, user))
      .toList();
  }

  private record Locked(Game game, GoalkeeperInvite invite) {}

  // Lock order matches every roster change: the game row first, then the invite.
  private Locked lock(UUID inviteId) {
    UUID gameId = store
      .first(
        UUID.class,
        "select i.gameId from GoalkeeperInvite i where i.id=:id",
        "id",
        inviteId
      )
      .orElseThrow(ApiException::notFound);
    Game game = store.lock(Game.class, gameId);
    return new Locked(game, store.lock(GoalkeeperInvite.class, inviteId));
  }

  private boolean closeStale(GoalkeeperInvite invite, Game game) {
    if (!reservations.closeIfStale(invite, game)) return false;
    reservations.promoteWaiting(game);
    return true;
  }

  private void settleStale(String party, UUID user) {
    List<UUID> stale = store.list(
      UUID.class,
      "select distinct g.id from GoalkeeperInvite i, Game g where i.gameId=g.id and i." +
        party +
        "=:user and i.status='PENDING' and (i.expiresAt<=:now or g.startsAt<=:now or g.cancelled=true or g.matchStartedAt is not null)",
      "user",
      user,
      "now",
      clock.instant()
    );
    for (UUID gameId : stale)
      reservations.settle(store.lock(Game.class, gameId));
  }

  /** Why a team cannot receive a goalkeeper invite now, or null when the goal is available. */
  private String goalBlocker(Game game, Team team) {
    if (!reservations.open(game)) return "A pelada não aceita mais alterações.";
    List<Participation> attendees = store.list(
      Participation.class,
      "from Participation where gameId=:game",
      "game",
      game.id
    );
    List<GoalkeeperInvite> active = reservations.active(game);
    if (
      active.stream().anyMatch(invite -> invite.teamId.equals(team.id))
    ) return "Já existe um convite pendente para o gol deste time.";
    List<Participation> roster = attendees
      .stream()
      .filter(p -> team.id.equals(p.teamId))
      .toList();
    if (
      roster.stream().anyMatch(p -> Integer.valueOf(0).equals(p.slot))
    ) return "O gol deste time já tem goleiro escalado.";
    if (
      roster.size() >= game.teamSize
    ) return "O elenco deste time está completo.";
    long confirmed = attendees
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .count();
    if (
      confirmed + active.size() >= (long) game.teamCount * game.teamSize
    ) return "A pelada já está com todas as vagas preenchidas.";
    return null;
  }

  private List<Team> teams(UUID gameId) {
    return store.list(
      Team.class,
      "from Team where gameId=:game order by ordinal",
      "game",
      gameId
    );
  }

  private List<Participation> roster(UUID gameId, UUID teamId) {
    return store.list(
      Participation.class,
      "from Participation where gameId=:game and teamId=:team",
      "game",
      gameId,
      "team",
      teamId
    );
  }

  private boolean canManage(UUID user, GoalkeeperInvite invite, Game game) {
    return (
      invite.organizerId.equals(user) ||
      store.get(Club.class, game.clubId).ownerId.equals(user)
    );
  }

  private List<Club> ownedGroups(UUID user) {
    return store.list(
      Club.class,
      "from Club where ownerId=:user and demo=false order by name",
      "user",
      user
    );
  }

  private boolean isOrganizer(UUID user) {
    return !ownedGroups(user).isEmpty();
  }

  private void requireOrganizer(UUID user) {
    if (!isOrganizer(user)) throw ApiException.forbidden();
  }

  private Optional<GoalkeeperProfile> profileOf(UUID user) {
    return store.first(
      GoalkeeperProfile.class,
      "from GoalkeeperProfile where playerId=:user",
      "user",
      user
    );
  }

  private GoalkeeperProfileView profileView(GoalkeeperProfile profile) {
    Municipality city = municipalities
      .find(profile.municipalityCode)
      .orElse(null);
    PublicRating rating = matches.publicRating(profile.playerId);
    return new GoalkeeperProfileView(
      profile.published,
      profile.municipalityCode,
      city == null ? "" : city.name(),
      city == null ? "" : city.uf(),
      profile.skillLevel,
      Social.values(profile.preferredDays),
      Social.values(profile.preferredPeriods),
      profile.description,
      rating.average(),
      rating.ratedGames(),
      profile.updatedAt
    );
  }

  private GoalkeeperInviteView view(GoalkeeperInvite invite, UUID user) {
    Game game = store.get(Game.class, invite.gameId);
    Club club = store.get(Club.class, game.clubId);
    Team team = store.get(Team.class, invite.teamId);
    boolean outgoing = canManage(user, invite, game);
    boolean mine = invite.goalkeeperId.equals(user);
    Instant now = clock.instant();
    boolean waiting =
      invite.status.equals("PENDING") &&
      invite.expiresAt.isAfter(now) &&
      reservations.open(game);
    boolean accepted = invite.status.equals("ACCEPTED");
    return new GoalkeeperInviteView(
      invite.id,
      invite.status,
      invite.statusReason,
      outcome(invite, team),
      outgoing,
      store.get(Player.class, invite.goalkeeperId).name,
      store.get(Player.class, invite.organizerId).name,
      club.id,
      club.name,
      game.id,
      game.title,
      game.startsAt,
      game.location,
      club.timeZone,
      team.id,
      team.name,
      team.color,
      invite.message,
      invite.createdAt,
      invite.expiresAt,
      invite.respondedAt,
      invite.closedAt,
      mine && waiting,
      mine && waiting,
      outgoing && waiting,
      mine && accepted && !game.cancelled && game.startsAt.isAfter(now),
      (mine && accepted) || (outgoing && groups.isMember(user, club.id))
    );
  }

  private static String outcome(GoalkeeperInvite invite, Team team) {
    String reason = invite.statusReason == null ? "" : invite.statusReason;
    return switch (invite.status) {
      case "PENDING" -> null;
      case "ACCEPTED" -> "Goleiro confirmado no gol de " + team.name + ".";
      case "DECLINED" -> "O goleiro recusou o convite.";
      case "WITHDRAWN" -> "O goleiro saiu da partida e a vaga foi liberada.";
      case "CANCELLED" -> switch (reason) {
        case "GAME_CANCELLED" -> "A pelada foi cancelada antes da resposta.";
        case "GAME_UNAVAILABLE" -> "A vaga no gol deixou de estar disponível.";
        default -> "O organizador cancelou o convite.";
      };
      case "EXPIRED" -> reason.equals("GAME_STARTED")
        ? "A pelada começou antes da resposta."
        : "O prazo de resposta terminou sem aceite.";
      default -> null;
    };
  }
}
