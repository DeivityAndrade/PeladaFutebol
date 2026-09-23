package br.com.pelada.games;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Games {

  private final Store store;
  private final Groups groups;
  private final Clock clock;
  private final Matches matches;
  private final Finance finance;

  public Games(
    Store store,
    Groups groups,
    Clock clock,
    Matches matches,
    Finance finance
  ) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
    this.matches = matches;
    this.finance = finance;
  }

  public GameDetail create(UUID user, UUID clubId, CreateGame input) {
    Club club = groups.requireOwner(user, clubId);
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "Escolha uma data e um horário no futuro."
    );
    Long gameCharge =
      input.occasionalAmountCents() == null
        ? club.occasionalAmountCents
        : input.occasionalAmountCents();
    if (input.chargeOccasional() && (gameCharge == null || gameCharge <= 0)) {
      throw new ApiException(
        400,
        "Defina o valor avulso do grupo ou desta pelada."
      );
    }
    Game game = store.save(
      new Game(
        clubId,
        input.title().strip(),
        input.location().strip(),
        input.startsAt(),
        input.teamCount(),
        input.teamSize()
      )
    );
    game.chargeOccasional = input.chargeOccasional();
    game.occasionalAmountCents = input.chargeOccasional() ? gameCharge : null;
    String[] colors = {
      "#d8f36a",
      "#8d9dff",
      "#ffa96b",
      "#70d9cb",
      "#f198c8",
      "#79b7f3",
    };
    for (int i = 0; i < input.teamCount(); i++) store.save(
      new Team(game.id, i, "Time " + (i + 1), colors[i])
    );
    return detail(game);
  }

  @Transactional(readOnly = true)
  public List<GameView> list(UUID user, UUID clubId) {
    groups.requireMember(user, clubId);
    return store
      .list(
        Game.class,
        "from Game where clubId=:club order by startsAt desc",
        "club",
        clubId
      )
      .stream()
      .map(this::view)
      .toList();
  }

  @Transactional(readOnly = true)
  public GameDetail get(UUID user, UUID id) {
    Game game = store.get(Game.class, id);
    groups.requireMember(user, game.clubId);
    return detail(game, user);
  }

  public GameDetail attend(UUID user, UUID id) {
    Game game = editable(user, id, false);
    if (participation(game.id, user).isEmpty()) {
      long confirmed = attendees(id)
        .stream()
        .filter(p -> p.status.equals("CONFIRMED"))
        .count();
      store.save(
        new Participation(
          id,
          user,
          confirmed < game.teamCount * game.teamSize ? "CONFIRMED" : "WAITING"
        )
      );
    }
    return detail(game);
  }

  public GameDetail leave(UUID user, UUID id) {
    Game game = editable(user, id, false);
    Optional<Participation> existing = participation(id, user);
    if (existing.isEmpty()) return detail(game);
    Participation p = existing.get();
    boolean hadSpot = p.status.equals("CONFIRMED");
    if (p.teamId != null) {
      Team team = store.get(Team.class, p.teamId);
      if (user.equals(team.captainId)) team.captainId = null;
      team.version++;
    }
    store.remove(p);
    store.flush();
    if (hadSpot) attendees(id)
      .stream()
      .filter(a -> a.status.equals("WAITING"))
      .findFirst()
      .ifPresent(a -> a.status = "CONFIRMED");
    return detail(game);
  }

  public GameDetail cancel(UUID user, UUID id) {
    Game game = store.lock(Game.class, id);
    groups.requireOwner(user, game.clubId);
    if (game.matchStartedAt != null) throw ApiException.conflict(
      "Uma partida iniciada não pode ser cancelada."
    );
    if (game.cancelled) throw ApiException.conflict(
      "Esta pelada já foi cancelada."
    );
    game.cancelled = true;
    finance.cancelGameCharges(game.id);
    return detail(game);
  }

  public GameDetail configure(
    UUID user,
    UUID gameId,
    UUID teamId,
    UpdateTeam input
  ) {
    Game game = editable(user, gameId, true);
    groups.requireOwner(user, game.clubId);
    Team team = team(gameId, teamId);
    if (input.captainId() != null) {
      Participation p = participation(gameId, input.captainId())
        .filter(a -> a.status.equals("CONFIRMED"))
        .orElseThrow(() ->
          new ApiException(400, "O capitão precisa estar confirmado na pelada.")
        );
      if (
        p.teamId != null && !p.teamId.equals(teamId)
      ) throw ApiException.conflict("Esse jogador já pertence a outro time.");
      if (p.teamId == null) {
        checkRoom(game, teamId);
        p.teamId = teamId;
      }
    }
    team.name = input.name().strip();
    team.color = input.color();
    team.captainId = input.captainId();
    team.version++;
    return detail(game);
  }

  public GameDetail pick(UUID user, UUID gameId, UUID teamId, UUID playerId) {
    Game game = editable(user, gameId, true);
    Team team = captain(user, gameId, teamId);
    Participation p = participation(gameId, playerId)
      .filter(a -> a.status.equals("CONFIRMED"))
      .orElseThrow(() ->
        new ApiException(400, "Escolha um jogador confirmado.")
      );
    if (p.teamId != null) throw ApiException.conflict(
      "Esse jogador já foi escolhido. A lista será atualizada."
    );
    checkRoom(game, teamId);
    p.teamId = teamId;
    team.version++;
    return detail(game);
  }

  public GameDetail release(
    UUID user,
    UUID gameId,
    UUID teamId,
    UUID playerId
  ) {
    Game game = editable(user, gameId, true);
    Team team = captain(user, gameId, teamId);
    if (playerId.equals(team.captainId)) throw new ApiException(
      400,
      "O capitão não pode remover a si mesmo. Peça ao organizador para trocar o capitão."
    );
    Participation p = participation(gameId, playerId)
      .filter(a -> teamId.equals(a.teamId))
      .orElseThrow(ApiException::notFound);
    p.slot = null;
    p.teamId = null;
    team.version++;
    return detail(game);
  }

  public GameDetail draw(UUID user, UUID gameId) {
    Game game = editable(user, gameId, true);
    groups.requireOwner(user, game.clubId);
    List<Participation> confirmed = attendees(gameId)
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .toList();
    if (confirmed.isEmpty()) throw new ApiException(
      400,
      "Confirme ao menos uma presença antes de sortear os times."
    );

    List<Team> teams = store.list(
      Team.class,
      "from Team where gameId=:game order by ordinal",
      "game",
      gameId
    );
    Map<UUID, Participation> players = confirmed
      .stream()
      .collect(Collectors.toMap(p -> p.playerId, Function.identity()));
    Map<Team, Integer> counts = new LinkedHashMap<>();
    Set<UUID> pinnedCaptains = new HashSet<>();
    teams.forEach(team -> counts.put(team, 0));

    for (Participation participation : confirmed) participation.slot = null;
    store.flush();

    for (Team team : teams) {
      team.version++;
      if (team.captainId == null) continue;
      Participation captain = players.get(team.captainId);
      if (captain == null) {
        team.captainId = null;
        continue;
      }
      captain.teamId = team.id;
      pinnedCaptains.add(captain.playerId);
      counts.put(team, 1);
    }

    List<Participation> remaining = confirmed
      .stream()
      .filter(p -> !pinnedCaptains.contains(p.playerId))
      .collect(Collectors.toCollection(ArrayList::new));
    Collections.shuffle(remaining);
    for (Participation player : remaining) {
      int smallest = counts
        .values()
        .stream()
        .filter(count -> count < game.teamSize)
        .mapToInt(Integer::intValue)
        .min()
        .orElseThrow(() ->
          ApiException.conflict(
            "Não há vagas suficientes para sortear os times."
          )
        );
      List<Team> candidates = counts
        .entrySet()
        .stream()
        .filter(entry -> entry.getValue() == smallest)
        .map(Map.Entry::getKey)
        .toList();
      Team destination = candidates.get(
        java.util.concurrent.ThreadLocalRandom.current().nextInt(
          candidates.size()
        )
      );
      player.teamId = destination.id;
      counts.put(destination, counts.get(destination) + 1);
    }
    return detail(game);
  }

  public GameDetail lineup(UUID user, UUID gameId, UUID teamId, Lineup input) {
    Game game = editable(user, gameId, true);
    Team team = captain(user, gameId, teamId);
    if (team.version != input.version()) throw ApiException.conflict(
      "O time mudou enquanto você editava. Confira a escalação atualizada."
    );
    List<UUID> selected = input
      .slots()
      .stream()
      .filter(Objects::nonNull)
      .toList();
    if (
      new HashSet<>(selected).size() != selected.size()
    ) throw new ApiException(400, "Um jogador não pode ocupar duas posições.");
    Map<UUID, Participation> roster = attendees(gameId)
      .stream()
      .filter(p -> teamId.equals(p.teamId))
      .collect(Collectors.toMap(p -> p.playerId, Function.identity()));
    if (!roster.keySet().containsAll(selected)) throw new ApiException(
      400,
      "Você só pode escalar jogadores do próprio elenco."
    );
    // Release unique slots before swapping positions, inside the same transaction.
    roster.values().forEach(p -> p.slot = null);
    store.flush();
    for (int i = 0; i < 5; i++) if (input.slots().get(i) != null) roster
      .get(input.slots().get(i))
      .slot = i;
    team.formation = input.formation();
    team.version++;
    return detail(game);
  }

  private Game editable(UUID user, UUID id, boolean teamChange) {
    // All game mutations serialize on this row: capacity, FIFO queue and team picks stay atomic.
    Game game = store.lock(Game.class, id);
    Club club = groups.requireMember(user, game.clubId);
    groups.writable(club);
    if (game.cancelled) throw ApiException.conflict(
      "Esta pelada foi cancelada."
    );
    if (teamChange && game.liveEnabled) {
      if (game.matchStartedAt != null) throw ApiException.conflict(
        "A partida já começou. Os times estão disponíveis apenas para consulta."
      );
    } else if (
      !game.startsAt.isAfter(clock.instant())
    ) throw ApiException.conflict(
      "As presenças e alterações desta pelada já foram encerradas."
    );
    return game;
  }

  private Team team(UUID gameId, UUID id) {
    Team team = store.get(Team.class, id);
    if (!team.gameId.equals(gameId)) throw ApiException.notFound();
    return team;
  }

  private Team captain(UUID user, UUID gameId, UUID teamId) {
    Team team = team(gameId, teamId);
    if (!user.equals(team.captainId)) throw ApiException.forbidden();
    return team;
  }

  private void checkRoom(Game game, UUID teamId) {
    if (
      attendees(game.id)
        .stream()
        .filter(a -> teamId.equals(a.teamId))
        .count() >= game.teamSize
    ) throw ApiException.conflict("O elenco deste time está completo.");
  }

  private Optional<Participation> participation(UUID gameId, UUID user) {
    return store.first(
      Participation.class,
      "from Participation where gameId=:game and playerId=:user",
      "game",
      gameId,
      "user",
      user
    );
  }

  private List<Participation> attendees(UUID id) {
    return store.list(
      Participation.class,
      "from Participation where gameId=:game order by id",
      "game",
      id
    );
  }

  public GameView view(Game game) {
    List<Participation> list = attendees(game.id);
    int confirmed = (int) list
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .count();
    return new GameView(
      game.id,
      game.clubId,
      game.title,
      game.location,
      game.startsAt,
      game.teamCount,
      game.teamSize,
      confirmed,
      list.size() - confirmed,
      game.chargeOccasional,
      game.occasionalAmountCents,
      game.cancelled,
      !game.cancelled && game.startsAt.isAfter(clock.instant()),
      !game.cancelled &&
        (game.liveEnabled
          ? game.matchStartedAt == null
          : game.startsAt.isAfter(clock.instant())),
      game.liveEnabled,
      status(game),
      game.matchStartedAt,
      game.matchEndedAt,
      game.matchDurationSeconds,
      game.correctionOpen,
      clock.instant()
    );
  }

  private String status(Game game) {
    if (game.cancelled) return "CANCELLED";
    if (!game.liveEnabled) return "LEGACY";
    if (game.matchEndedAt != null) return "FINISHED";
    if (game.matchStartedAt != null) return "LIVE";
    return game.startsAt.isAfter(clock.instant()) ? "SCHEDULED" : "READY";
  }

  public GameDetail detail(Game game) {
    return detail(game, null);
  }

  public GameDetail detail(Game game, UUID viewer) {
    List<Attendee> people = store
      .list(
        Object[].class,
        "select p,u from Participation p,Player u where p.playerId=u.id and p.gameId=:game order by p.id",
        "game",
        game.id
      )
      .stream()
      .map(row -> {
        Participation p = (Participation) row[0];
        Player u = (Player) row[1];
        return new Attendee(u.id, u.name, p.status, p.teamId, p.slot);
      })
      .toList();
    List<TeamView> teams = store
      .list(
        Team.class,
        "from Team where gameId=:game order by ordinal",
        "game",
        game.id
      )
      .stream()
      .map(t ->
        new TeamView(t.id, t.name, t.color, t.captainId, t.formation, t.version)
      )
      .toList();
    List<GoalView> goals = matches.goals(game);
    List<TeamScore> score = teams
      .stream()
      .map(t ->
        new TeamScore(
          t.id(),
          (int) goals
            .stream()
            .filter(g -> !g.voided() && g.teamId().equals(t.id()))
            .count()
        )
      )
      .toList();
    return new GameDetail(
      view(game),
      groups.view(store.get(Club.class, game.clubId)),
      people,
      teams,
      score,
      goals,
      matches.publishedRatings(game),
      matches.ownRatings(game, viewer),
      game.matchEndedAt == null ? null : game.matchEndedAt.plusSeconds(86400)
    );
  }
}
