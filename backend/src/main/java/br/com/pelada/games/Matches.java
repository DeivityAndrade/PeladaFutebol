package br.com.pelada.games;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Matches {

  private final Store store;
  private final Groups groups;
  private final Clock clock;
  private final Finance finance;
  private final GoalkeeperReservations reservations;

  public Matches(
    Store store,
    Groups groups,
    Clock clock,
    Finance finance,
    GoalkeeperReservations reservations
  ) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
    this.finance = finance;
    this.reservations = reservations;
  }

  public Game start(UUID user, UUID id) {
    Game game = lockedMemberGame(user, id);
    requireLiveGame(game);
    reservations.settle(game);
    if (
      game.cancelled || game.matchStartedAt != null
    ) throw ApiException.conflict("A partida já foi iniciada ou cancelada.");
    if (clock.instant().isBefore(game.startsAt)) throw ApiException.conflict(
      "A partida só pode começar no horário marcado."
    );
    List<Participation> players = participations(id);
    if (
      players.stream().noneMatch(p -> p.status.equals("CONFIRMED"))
    ) throw ApiException.conflict("Confirme jogadores antes de começar.");
    if (
      players
        .stream()
        .anyMatch(p -> p.status.equals("CONFIRMED") && p.teamId == null)
    ) throw ApiException.conflict(
      "Distribua todos os confirmados entre os dois times."
    );
    List<Team> teams = teams(id);
    if (
      teams.size() != 2 ||
      teams
        .stream()
        .anyMatch(t ->
          players
            .stream()
            .noneMatch(
              p -> p.status.equals("CONFIRMED") && t.id.equals(p.teamId)
            )
        )
    ) throw ApiException.conflict("Cada time precisa de ao menos um jogador.");
    finance.createGameCharges(
      game,
      players
        .stream()
        .filter(p -> p.status.equals("CONFIRMED"))
        .toList()
    );
    game.matchStartedAt = clock.instant();
    return game;
  }

  public Game finish(UUID user, UUID id) {
    Game game = lockedMemberGame(user, id);
    requireConfirmed(user, id);
    if (
      game.matchStartedAt == null || game.matchEndedAt != null
    ) throw ApiException.conflict(
      "A partida precisa estar ao vivo para ser encerrada."
    );
    game.matchEndedAt = clock.instant();
    game.matchDurationSeconds = Math.toIntExact(
      Duration.between(game.matchStartedAt, game.matchEndedAt).toSeconds()
    );
    return game;
  }

  public Game correction(UUID user, UUID id, boolean open) {
    Game game = lockedMemberGame(user, id);
    groups.requireOwner(user, game.clubId);
    if (game.matchEndedAt == null) throw ApiException.conflict(
      "Encerre a partida antes de corrigir."
    );
    game.correctionOpen = open;
    return game;
  }

  public Game duration(UUID user, UUID id, int seconds) {
    Game game = lockedMemberGame(user, id);
    groups.requireOwner(user, game.clubId);
    if (!game.correctionOpen) throw ApiException.conflict(
      "Abra o modo de correção para ajustar a duração."
    );
    game.matchDurationSeconds = seconds;
    return game;
  }

  public Game goal(UUID user, UUID id, NewGoal input) {
    Game game = lockedMemberGame(user, id);
    requireGoalEditor(user, game);
    Team team = store.get(Team.class, input.teamId());
    if (!team.gameId.equals(id)) throw new ApiException(
      400,
      "O time informado não pertence a esta partida."
    );
    Participation scorer = participation(id, input.scorerId()).orElseThrow(() ->
      new ApiException(400, "O autor precisa estar confirmado nesta partida.")
    );
    if (
      !scorer.status.equals("CONFIRMED") || scorer.teamId == null
    ) throw new ApiException(400, "O autor precisa integrar um dos times.");
    if (
      input.ownGoal() == team.id.equals(scorer.teamId)
    ) throw new ApiException(
      400,
      "Confira o time beneficiado e a opção de gol contra."
    );
    int currentMinute =
      game.matchEndedAt == null
        ? Math.toIntExact(
            Duration.between(game.matchStartedAt, clock.instant()).toMinutes()
          )
        : game.matchDurationSeconds / 60;
    int minute = input.minute() == null ? currentMinute : input.minute();
    if (
      game.matchEndedAt == null && input.minute() != null
    ) throw new ApiException(
      400,
      "O minuto é calculado automaticamente durante a partida."
    );
    if (minute < 0 || minute > 1440) throw new ApiException(
      400,
      "Minuto inválido."
    );
    store.save(
      new Goal(
        id,
        team.id,
        scorer.playerId,
        minute,
        input.ownGoal(),
        clock.instant()
      )
    );
    return game;
  }

  public Game voidGoal(UUID user, UUID id, UUID goalId) {
    Game game = lockedMemberGame(user, id);
    requireGoalEditor(user, game);
    Goal goal = store.get(Goal.class, goalId);
    if (!goal.gameId.equals(id)) throw ApiException.notFound();
    if (goal.voidedAt != null) throw ApiException.conflict(
      "Este gol já foi anulado."
    );
    goal.voidedAt = clock.instant();
    return game;
  }

  public Game rate(UUID user, UUID id, SaveRating input) {
    Game game = lockedMemberGame(user, id);
    requireLiveGame(game);
    if (game.matchEndedAt == null) throw ApiException.conflict(
      "As notas abrem após o encerramento."
    );
    if (
      !clock.instant().isBefore(game.matchEndedAt.plus(Duration.ofHours(24)))
    ) throw ApiException.conflict("O prazo de 24 horas para avaliar terminou.");
    Participation rater = requireConfirmed(user, id);
    Participation target = participation(id, input.playerId()).orElseThrow(
      ApiException::notFound
    );
    if (
      !target.status.equals("CONFIRMED") ||
      target.teamId == null ||
      !target.teamId.equals(rater.teamId) ||
      user.equals(input.playerId())
    ) throw ApiException.forbidden();
    Rating rating = store
      .first(
        Rating.class,
        "from Rating where gameId=:game and raterId=:rater and playerId=:player",
        "game",
        id,
        "rater",
        user,
        "player",
        input.playerId()
      )
      .orElse(null);
    if (rating == null) store.save(
      new Rating(id, user, input.playerId(), input.stars(), clock.instant())
    );
    else {
      rating.stars = input.stars();
      rating.updatedAt = clock.instant();
    }
    return game;
  }

  @Transactional(readOnly = true)
  public List<GoalView> goals(Game game) {
    return store
      .list(
        Object[].class,
        "select g,p from Goal g,Player p where g.scorerId=p.id and g.gameId=:game order by g.createdAt,g.id",
        "game",
        game.id
      )
      .stream()
      .map(row -> {
        Goal g = (Goal) row[0];
        Player p = (Player) row[1];
        return new GoalView(
          g.id,
          g.teamId,
          g.scorerId,
          p.name,
          g.minute,
          g.ownGoal,
          g.voidedAt != null
        );
      })
      .toList();
  }

  @Transactional(readOnly = true)
  public List<RatingView> publishedRatings(Game game) {
    if (!published(game)) return List.of();
    Map<UUID, DoubleSummaryStatistics> grouped = store
      .list(Rating.class, "from Rating where gameId=:game", "game", game.id)
      .stream()
      .collect(
        Collectors.groupingBy(
          r -> r.playerId,
          Collectors.summarizingDouble(r -> r.stars)
        )
      );
    return participations(game.id)
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .map(p -> {
        var stats = grouped.get(p.playerId);
        return new RatingView(
          p.playerId,
          stats == null ? null : stats.getAverage(),
          stats == null ? 0 : (int) stats.getCount()
        );
      })
      .toList();
  }

  @Transactional(readOnly = true)
  public List<OwnRating> ownRatings(Game game, UUID user) {
    if (user == null || game.matchEndedAt == null) return List.of();
    return store
      .list(
        Rating.class,
        "from Rating where gameId=:game and raterId=:rater",
        "game",
        game.id,
        "rater",
        user
      )
      .stream()
      .map(r -> new OwnRating(r.playerId, r.stars))
      .toList();
  }

  @Transactional(readOnly = true)
  public PlayerProfile profile(UUID viewer, UUID playerId) {
    Player player = store.get(Player.class, playerId);
    Set<UUID> shared = store
      .list(
        Member.class,
        "from Member where playerId=:player",
        "player",
        viewer
      )
      .stream()
      .map(m -> m.clubId)
      .collect(Collectors.toSet());
    Set<UUID> targetClubs = store
      .list(
        Member.class,
        "from Member where playerId=:player",
        "player",
        playerId
      )
      .stream()
      .map(m -> m.clubId)
      .collect(Collectors.toSet());
    if (
      shared.stream().noneMatch(targetClubs::contains)
    ) throw ApiException.forbidden();
    List<PlayerGameRating> all = ratedGames(playerId);
    List<PlayerGameRating> history = all
      .stream()
      .filter(g -> shared.contains(store.get(Game.class, g.gameId()).clubId))
      .toList();
    return new PlayerProfile(
      player.id,
      player.name,
      overall(all),
      all.size(),
      history
    );
  }

  /** Same overall average as the profile, limited to the average and the rated game count. */
  @Transactional(readOnly = true)
  public PublicRating publicRating(UUID playerId) {
    List<PlayerGameRating> all = ratedGames(playerId);
    return new PublicRating(overall(all), all.size());
  }

  private List<PlayerGameRating> ratedGames(UUID playerId) {
    Map<UUID, DoubleSummaryStatistics> byGame = store
      .list(
        Rating.class,
        "from Rating where playerId=:player",
        "player",
        playerId
      )
      .stream()
      .collect(
        Collectors.groupingBy(
          r -> r.gameId,
          Collectors.summarizingDouble(r -> r.stars)
        )
      );
    return byGame
      .entrySet()
      .stream()
      .map(entry -> {
        Game game = store.get(Game.class, entry.getKey());
        if (!published(game)) return null;
        var stats = entry.getValue();
        return new PlayerGameRating(
          game.id,
          game.title,
          game.startsAt,
          stats.getAverage(),
          (int) stats.getCount()
        );
      })
      .filter(Objects::nonNull)
      .sorted(Comparator.comparing(PlayerGameRating::startsAt).reversed())
      .toList();
  }

  // Every rated game weighs the same in the overall average.
  private static Double overall(List<PlayerGameRating> games) {
    return games.isEmpty()
      ? null
      : games
          .stream()
          .mapToDouble(PlayerGameRating::average)
          .average()
          .orElseThrow();
  }

  public boolean published(Game game) {
    return (
      game.matchEndedAt != null &&
      !clock.instant().isBefore(game.matchEndedAt.plus(Duration.ofHours(24)))
    );
  }

  private Game lockedMemberGame(UUID user, UUID id) {
    Game game = store.lock(Game.class, id);
    groups.writable(groups.requireMember(user, game.clubId));
    return game;
  }

  private void requireLiveGame(Game game) {
    if (!game.liveEnabled || game.teamCount != 2) throw ApiException.conflict(
      "Partida ao vivo disponível apenas para peladas de dois times."
    );
  }

  private void requireGoalEditor(UUID user, Game game) {
    if (
      game.matchStartedAt == null ||
      (game.matchEndedAt != null && !game.correctionOpen)
    ) throw ApiException.conflict(
      "Os gols só podem mudar durante a partida ou no modo de correção."
    );
    if (game.matchEndedAt != null) groups.requireOwner(user, game.clubId);
    else requireConfirmed(user, game.id);
  }

  private Participation requireConfirmed(UUID user, UUID id) {
    return participation(id, user)
      .filter(p -> p.status.equals("CONFIRMED") && p.teamId != null)
      .orElseThrow(ApiException::forbidden);
  }

  private Optional<Participation> participation(UUID game, UUID player) {
    return store.first(
      Participation.class,
      "from Participation where gameId=:game and playerId=:player",
      "game",
      game,
      "player",
      player
    );
  }

  private List<Participation> participations(UUID game) {
    return store.list(
      Participation.class,
      "from Participation where gameId=:game",
      "game",
      game
    );
  }

  private List<Team> teams(UUID game) {
    return store.list(
      Team.class,
      "from Team where gameId=:game order by ordinal",
      "game",
      game
    );
  }
}
