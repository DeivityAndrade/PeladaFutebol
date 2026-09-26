package br.com.pelada.games;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Games {

  private static final int UPCOMING_WINDOW = 8;

  private final Store store;
  private final Groups groups;
  private final Clock clock;
  private final Matches matches;
  private final Finance finance;
  private final GoalkeeperReservations reservations;

  public Games(
    Store store,
    Groups groups,
    Clock clock,
    Matches matches,
    Finance finance,
    GoalkeeperReservations reservations
  ) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
    this.matches = matches;
    this.finance = finance;
    this.reservations = reservations;
  }

  public GameDetail create(UUID user, UUID clubId, CreateGame input) {
    Club club = groups.requireOwner(user, clubId);
    club = store.lock(Club.class, club.id);
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "Escolha uma data e um horário no futuro."
    );
    ZoneId zone = groupZone(club);
    LocalDate firstDate = input.startsAt().atZone(zone).toLocalDate();
    if (
      input.recurrenceEndsOn() != null &&
      (!input.recurring() || input.recurrenceEndsOn().isBefore(firstDate))
    ) throw new ApiException(
      400,
      "O término da recorrência precisa ser no dia da primeira pelada ou depois dele."
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
    Game game = createGame(
      clubId,
      input.title().strip(),
      input.location().strip(),
      input.startsAt(),
      input.teamCount(),
      input.teamSize(),
      input.chargeOccasional(),
      input.chargeOccasional() ? gameCharge : null
    );
    if (input.recurring()) {
      GameSeries series = store.save(
        new GameSeries(
          clubId,
          zone.getId(),
          input.startsAt(),
          input.recurrenceEndsOn(),
          game.title,
          game.location,
          game.teamCount,
          game.teamSize,
          game.chargeOccasional,
          game.occasionalAmountCents,
          clock.instant()
        )
      );
      game.seriesId = series.id;
      game.seriesOccurrenceIndex = 1;
      ensureSeriesWindow(club);
    }
    return detail(game);
  }

  public List<GameView> list(UUID user, UUID clubId) {
    groups.requireMember(user, clubId);
    Club club = store.lock(Club.class, clubId);
    ensureSeriesWindow(club);
    settleStaleReservations(club.id);
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

  private Game createGame(
    UUID clubId,
    String title,
    String location,
    Instant startsAt,
    int teamCount,
    int teamSize,
    boolean chargeOccasional,
    Long occasionalAmountCents
  ) {
    Game game = store.save(
      new Game(clubId, title, location, startsAt, teamCount, teamSize)
    );
    game.chargeOccasional = chargeOccasional;
    game.occasionalAmountCents = chargeOccasional
      ? occasionalAmountCents
      : null;
    String[] colors = {
      "#d8f36a",
      "#8d9dff",
      "#ffa96b",
      "#70d9cb",
      "#f198c8",
      "#79b7f3",
    };
    for (int i = 0; i < teamCount; i++) store.save(
      new Team(game.id, i, "Time " + (i + 1), colors[i])
    );
    return game;
  }

  private void ensureSeriesWindow(Club club) {
    Instant now = clock.instant();
    List<GameSeries> seriesList = store.list(
      GameSeries.class,
      "from GameSeries where clubId=:club and active=true order by createdAt",
      "club",
      club.id
    );
    for (GameSeries loaded : seriesList) {
      GameSeries series = store.lock(GameSeries.class, loaded.id);
      if (!series.active) continue;
      int upcoming = store
        .list(
          Long.class,
          "select count(g) from Game g where g.seriesId=:series and g.startsAt>:now and g.cancelled=false",
          "series",
          series.id,
          "now",
          now
        )
        .getFirst()
        .intValue();
      int maximumIndex = store
        .list(
          Integer.class,
          "select coalesce(max(g.seriesOccurrenceIndex),0) from Game g where g.seriesId=:series",
          "series",
          series.id
        )
        .getFirst();
      series.nextOccurrenceIndex = Math.max(
        series.nextOccurrenceIndex,
        maximumIndex + 1
      );
      ZoneId zone = seriesZone(series);
      ZonedDateTime anchor = ZonedDateTime.ofInstant(
        series.anchorStartsAt,
        zone
      );
      int skipped = 0;
      while (upcoming < UPCOMING_WINDOW && series.active && skipped < 20_000) {
        int index = series.nextOccurrenceIndex++;
        long weeks = (long) index - series.anchorOccurrenceIndex;
        if (weeks < 0) continue;
        ZonedDateTime scheduled = anchor
          .toLocalDate()
          .plusWeeks(weeks)
          .atTime(anchor.toLocalTime())
          .atZone(zone);
        if (
          series.endsOn != null &&
          scheduled.toLocalDate().isAfter(series.endsOn)
        ) {
          series.active = false;
          break;
        }
        if (!scheduled.toInstant().isAfter(now)) {
          skipped++;
          continue;
        }
        Game occurrence = createGame(
          series.clubId,
          series.title,
          series.location,
          scheduled.toInstant(),
          series.teamCount,
          series.teamSize,
          series.chargeOccasional,
          series.occasionalAmountCents
        );
        occurrence.seriesId = series.id;
        occurrence.seriesOccurrenceIndex = index;
        upcoming++;
      }
    }
    store.flush();
  }

  private void cancelOccurrence(Game game) {
    game.cancelled = true;
    finance.cancelGameCharges(game.id);
    reservations.settle(game);
  }

  /** Closes pending goalkeeper invites whose deadline or game passed, freeing their spots. */
  private void settleStaleReservations(UUID clubId) {
    List<UUID> stale = store.list(
      UUID.class,
      "select distinct g.id from GoalkeeperInvite i, Game g where i.gameId=g.id and g.clubId=:club and i.status='PENDING' and (i.expiresAt<=:now or g.startsAt<=:now or g.cancelled=true or g.matchStartedAt is not null)",
      "club",
      clubId,
      "now",
      clock.instant()
    );
    for (UUID gameId : stale)
      reservations.settle(store.lock(Game.class, gameId));
  }

  private ZoneId groupZone(Club club) {
    try {
      return ZoneId.of(club.timeZone);
    } catch (DateTimeException ex) {
      throw new ApiException(
        500,
        "O fuso horário configurado para o grupo é inválido."
      );
    }
  }

  private ZoneId seriesZone(GameSeries series) {
    try {
      return ZoneId.of(series.timeZone);
    } catch (DateTimeException ex) {
      throw new ApiException(
        500,
        "O fuso horário salvo para esta série é inválido."
      );
    }
  }

  @Transactional(readOnly = true)
  public GameDetail get(UUID user, UUID id) {
    Game game = store.get(Game.class, id);
    requireViewer(user, game);
    return detail(game, user);
  }

  public GameDetail attend(UUID user, UUID id) {
    Game game = editable(user, id, false);
    if (participation(game.id, user).isEmpty()) {
      long confirmed = attendees(id)
        .stream()
        .filter(p -> p.status.equals("CONFIRMED"))
        .count();
      // Pending goalkeeper invites keep their spots until the goalkeeper answers.
      long taken = confirmed + reservations.active(game).size();
      store.save(
        new Participation(
          id,
          user,
          taken < game.teamCount * game.teamSize ? "CONFIRMED" : "WAITING"
        )
      );
    }
    return detail(game);
  }

  public GameDetail leave(UUID user, UUID id) {
    Game game = editable(user, id, false, true);
    Optional<Participation> existing = participation(id, user);
    if (existing.isEmpty()) return detail(game, user);
    Participation p = existing.get();
    boolean hadSpot = p.status.equals("CONFIRMED");
    if (p.teamId != null) {
      Team team = store.get(Team.class, p.teamId);
      if (user.equals(team.captainId)) team.captainId = null;
      team.version++;
    }
    if (p.goalkeeperInviteId != null) reservations.finish(
      store.lock(GoalkeeperInvite.class, p.goalkeeperInviteId),
      "WITHDRAWN",
      "GOALKEEPER"
    );
    store.remove(p);
    store.flush();
    if (hadSpot) reservations.promoteWaiting(game);
    return detail(game, user);
  }

  public GameDetail cancel(UUID user, UUID id) {
    return cancel(user, id, "ONE");
  }

  public GameDetail cancel(UUID user, UUID id, String scope) {
    Game game = store.lock(Game.class, id);
    groups.requireOwner(user, game.clubId);
    if (game.matchStartedAt != null) throw ApiException.conflict(
      "Uma partida iniciada não pode ser cancelada."
    );
    if (game.cancelled) throw ApiException.conflict(
      "Esta pelada já foi cancelada."
    );
    if (
      !scope.equals("ONE") && !scope.equals("THIS_AND_FUTURE")
    ) throw new ApiException(
      400,
      "Escolha se deseja cancelar esta ocorrência ou esta e as futuras."
    );
    if (
      game.seriesId != null && !game.startsAt.isAfter(clock.instant())
    ) throw ApiException.conflict(
      "Ocorrências passadas da série são preservadas e não podem ser canceladas."
    );
    if (scope.equals("THIS_AND_FUTURE") && game.seriesId != null) {
      GameSeries series = store.lock(GameSeries.class, game.seriesId);
      List<Game> future = store.list(
        Game.class,
        "from Game where seriesId=:series and seriesOccurrenceIndex>=:index order by seriesOccurrenceIndex",
        "series",
        series.id,
        "index",
        game.seriesOccurrenceIndex
      );
      Instant now = clock.instant();
      for (Game occurrence : future)
        if (
          occurrence.startsAt.isAfter(now) && !occurrence.cancelled
        ) cancelOccurrence(occurrence);
      series.active = false;
    } else {
      cancelOccurrence(game);
      if (game.seriesId != null) game.seriesException = true;
    }
    return detail(game);
  }

  public GameDetail update(UUID user, UUID id, UpdateGame input) {
    Game game = store.lock(Game.class, id);
    groups.requireOwner(user, game.clubId);
    if (game.cancelled) throw ApiException.conflict(
      "Uma pelada cancelada não pode ser alterada."
    );
    if (
      game.matchStartedAt != null || !game.startsAt.isAfter(clock.instant())
    ) throw ApiException.conflict(
      "Só é possível editar uma pelada antes do horário marcado."
    );
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "Escolha uma data e um horário no futuro."
    );
    if (
      !input.scope().equals("ONE") && !input.scope().equals("THIS_AND_FUTURE")
    ) throw new ApiException(
      400,
      "Escolha se deseja editar esta ocorrência ou esta e as futuras."
    );

    if (game.seriesId == null || input.scope().equals("ONE")) {
      game.title = input.title().strip();
      game.location = input.location().strip();
      game.startsAt = input.startsAt();
      if (game.seriesId != null) game.seriesException = true;
      reservations.clampDeadlines(game);
      return detail(game);
    }

    GameSeries series = store.lock(GameSeries.class, game.seriesId);
    ZoneId zone = seriesZone(series);
    ZonedDateTime newAnchor = input.startsAt().atZone(zone);
    ZonedDateTime oldAnchor = game.startsAt.atZone(zone);
    if (
      series.endsOn != null && newAnchor.toLocalDate().isAfter(series.endsOn)
    ) throw new ApiException(
      400,
      "A nova data fica depois do término configurado para esta série."
    );
    long shiftDays = ChronoUnit.DAYS.between(
      oldAnchor.toLocalDate(),
      newAnchor.toLocalDate()
    );
    series.anchorStartsAt = input.startsAt();
    series.anchorOccurrenceIndex = game.seriesOccurrenceIndex;
    series.title = input.title().strip();
    series.location = input.location().strip();
    if (series.endsOn != null && shiftDays != 0) series.endsOn =
      series.endsOn.plusDays(shiftDays);

    List<Game> future = store.list(
      Game.class,
      "from Game where seriesId=:series and seriesOccurrenceIndex>=:index order by seriesOccurrenceIndex",
      "series",
      series.id,
      "index",
      game.seriesOccurrenceIndex
    );
    Instant now = clock.instant();
    for (Game occurrence : future) {
      if (
        !occurrence.id.equals(game.id) &&
        (occurrence.seriesException || !occurrence.startsAt.isAfter(now))
      ) continue;
      long weeks =
        occurrence.seriesOccurrenceIndex - game.seriesOccurrenceIndex;
      occurrence.startsAt = newAnchor.plusWeeks(weeks).toInstant();
      occurrence.title = series.title;
      occurrence.location = series.location;
      occurrence.seriesException = false;
      reservations.clampDeadlines(occurrence);
    }
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
      if (p.goalkeeperInviteId != null) throw new ApiException(
        400,
        "O goleiro convidado participa só desta partida e não pode ser capitão."
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
    if (p.goalkeeperInviteId != null) throw ApiException.conflict(
      "O goleiro convidado fica no gol deste time até sair da partida."
    );
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
    Set<UUID> pinned = new HashSet<>();
    // A reserved goal already takes one spot of its team.
    Set<UUID> reservedTeams = reservations
      .active(game)
      .stream()
      .map(invite -> invite.teamId)
      .collect(Collectors.toSet());
    teams.forEach(team ->
      counts.put(team, reservedTeams.contains(team.id) ? 1 : 0)
    );

    // Guest goalkeepers keep their team and the goal; everyone else is redrawn.
    for (Participation participation : confirmed)
      if (participation.goalkeeperInviteId == null) participation.slot = null;
    store.flush();

    for (Participation guest : confirmed) {
      if (guest.goalkeeperInviteId == null || guest.teamId == null) continue;
      teams
        .stream()
        .filter(team -> team.id.equals(guest.teamId))
        .findFirst()
        .ifPresent(team -> counts.put(team, counts.get(team) + 1));
      pinned.add(guest.playerId);
    }

    for (Team team : teams) {
      team.version++;
      if (team.captainId == null) continue;
      Participation captain = players.get(team.captainId);
      if (captain == null) {
        team.captainId = null;
        continue;
      }
      captain.teamId = team.id;
      pinned.add(captain.playerId);
      counts.put(team, counts.get(team) + 1);
    }

    List<Participation> remaining = confirmed
      .stream()
      .filter(p -> !pinned.contains(p.playerId))
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
    UUID goal = input.slots().getFirst();
    Optional<Participation> guest = roster
      .values()
      .stream()
      .filter(p -> p.goalkeeperInviteId != null)
      .findFirst();
    if (
      guest.isPresent() && !guest.get().playerId.equals(goal)
    ) throw ApiException.conflict(
      "O goleiro convidado fica no gol deste time até sair da partida."
    );
    if (
      guest.isEmpty() &&
      goal != null &&
      reservations.activeFor(game, teamId).isPresent()
    ) throw ApiException.conflict(
      "O gol deste time está reservado para o goleiro convidado até a resposta."
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
    return editable(user, id, teamChange, false);
  }

  private Game editable(
    UUID user,
    UUID id,
    boolean teamChange,
    boolean allowGuest
  ) {
    // All game mutations serialize on this row: capacity, FIFO queue and team picks stay atomic.
    Game game = store.lock(Game.class, id);
    Club club = store.get(Club.class, game.clubId);
    if (
      !groups.isMember(user, club.id) &&
      !(allowGuest && acceptedGuest(user, game.id))
    ) throw ApiException.forbidden();
    groups.writable(club);
    reservations.settle(game);
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
    long roster = attendees(game.id)
      .stream()
      .filter(a -> teamId.equals(a.teamId))
      .count();
    boolean reserved = reservations.activeFor(game, teamId).isPresent();
    if (
      roster + (reserved ? 1 : 0) >= game.teamSize
    ) throw ApiException.conflict(
      reserved
        ? "O elenco deste time está completo: uma vaga está reservada para o goleiro convidado."
        : "O elenco deste time está completo."
    );
  }

  /** Members see every game of the group; a guest goalkeeper sees only the accepted match. */
  private void requireViewer(UUID user, Game game) {
    store.get(Club.class, game.clubId);
    if (
      !groups.isMember(user, game.clubId) && !acceptedGuest(user, game.id)
    ) throw ApiException.forbidden();
  }

  private boolean acceptedGuest(UUID user, UUID gameId) {
    return store
      .first(
        GoalkeeperInvite.class,
        "from GoalkeeperInvite where gameId=:game and goalkeeperId=:user and status='ACCEPTED'",
        "game",
        gameId,
        "user",
        user
      )
      .isPresent();
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
      clock.instant(),
      game.seriesId != null,
      game.seriesOccurrenceIndex,
      game.seriesException
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
        return new Attendee(
          u.id,
          u.name,
          p.status,
          p.teamId,
          p.slot,
          p.goalkeeperInviteId != null
        );
      })
      .toList();
    Club club = store.get(Club.class, game.clubId);
    boolean guest = viewer != null && !groups.isMember(viewer, club.id);
    List<GoalkeeperReservationView> reserved = reservations
      .active(game)
      .stream()
      .map(invite ->
        new GoalkeeperReservationView(
          invite.teamId,
          store.get(Player.class, invite.goalkeeperId).name,
          invite.expiresAt
        )
      )
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
      guest ? withoutCharges(view(game)) : view(game),
      guest ? groups.guestView(club) : groups.view(club),
      people,
      teams,
      score,
      goals,
      matches.publishedRatings(game),
      matches.ownRatings(game, viewer),
      game.matchEndedAt == null ? null : game.matchEndedAt.plusSeconds(86400),
      reserved,
      guest
    );
  }

  private GameView withoutCharges(GameView g) {
    return new GameView(
      g.id(),
      g.clubId(),
      g.title(),
      g.location(),
      g.startsAt(),
      g.teamCount(),
      g.teamSize(),
      g.confirmed(),
      g.waiting(),
      false,
      null,
      g.cancelled(),
      g.editable(),
      g.teamEditable(),
      g.liveEnabled(),
      g.matchStatus(),
      g.matchStartedAt(),
      g.matchEndedAt(),
      g.matchDurationSeconds(),
      g.correctionOpen(),
      g.serverNow(),
      g.recurring(),
      g.occurrenceIndex(),
      g.seriesException()
    );
  }
}
