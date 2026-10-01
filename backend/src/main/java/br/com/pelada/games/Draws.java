package br.com.pelada.games;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Groups;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Proposals are immutable server snapshots. Only apply changes the actual rosters. */
@Service
@Transactional
public class Draws {

  private final Store store;
  private final Groups groups;
  private final Games games;
  private final GoalkeeperReservations reservations;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  public Draws(
    Store store,
    Groups groups,
    Games games,
    GoalkeeperReservations reservations,
    Clock clock
  ) {
    this.store = store;
    this.groups = groups;
    this.games = games;
    this.reservations = reservations;
    this.clock = clock;
  }

  private record Context(
    Game game,
    List<Team> teams,
    List<Participation> people,
    Map<UUID, Member> members,
    Set<UUID> reservedTeams,
    String fingerprint
  ) {}

  private Context context(UUID user, UUID id) {
    Game game = games.editable(user, id, true);
    groups.requireOwner(user, game.clubId);
    List<Team> teams = store.list(
      Team.class,
      "from Team where gameId=:game order by ordinal",
      "game",
      id
    );
    List<Participation> people = store.list(
      Participation.class,
      "from Participation where gameId=:game order by id",
      "game",
      id
    );
    Map<UUID, Member> members = new HashMap<>();
    // Lock classifications in a stable order; edits cannot slip between validation and application.
    for (Member m : store.list(
      Member.class,
      "from Member where clubId=:club order by id",
      "club",
      game.clubId
    )) {
      Member locked = store.refreshLocked(Member.class, m.id);
      members.put(locked.playerId, locked);
    }
    StringBuilder state = new StringBuilder()
      .append(game.id)
      .append(game.teamSize);
    for (Team t : teams)
      state.append('|').append(t.id).append(t.version).append(t.captainId);
    for (Participation p : people)
      state
        .append('|')
        .append(p.id)
        .append(p.status)
        .append(p.teamId)
        .append(p.slot)
        .append(p.goalkeeperInviteId);
    members
      .values()
      .stream()
      .sorted(Comparator.comparing(m -> m.id))
      .forEach(m ->
        state
          .append('|')
          .append(m.id)
          .append(m.primaryPosition)
          .append(m.secondaryPosition)
          .append(m.skillLevel)
      );
    Set<UUID> reserved = new HashSet<>();
    reservations
      .active(game)
      .stream()
      .sorted(Comparator.comparing(i -> i.id))
      .forEach(i -> {
        reserved.add(i.teamId);
        state.append('|').append(i.id).append(i.teamId).append(i.expiresAt);
      });
    try {
      return new Context(
        game,
        teams,
        people,
        members,
        reserved,
        HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(
            state.toString().getBytes(StandardCharsets.UTF_8)
          )
        )
      );
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public DrawPreview preview(UUID user, UUID id, DrawInput input) {
    if (
      !Set.of("RANDOM", "BALANCED").contains(input.mode())
    ) throw new ApiException(400, "Escolha o tipo de sorteio.");
    Context c = context(user, id);
    List<Participation> confirmed = c.people
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .toList();
    if (confirmed.isEmpty()) throw new ApiException(
      400,
      "Confirme ao menos uma presença antes de sortear os times."
    );
    Map<UUID, UUID> fixed = new HashMap<>();
    for (Participation p : confirmed)
      if (p.goalkeeperInviteId != null && p.teamId != null) fixed.put(
        p.playerId,
        p.teamId
      );
    for (Team t : c.teams)
      if (
        confirmed.stream().anyMatch(p -> p.playerId.equals(t.captainId))
      ) fixed.put(t.captainId, t.id);
    Map<UUID, DrawPlayer> players = new LinkedHashMap<>();
    for (Participation p : confirmed) {
      Member m = c.members.get(p.playerId);
      players.put(
        p.playerId,
        new DrawPlayer(
          p.playerId,
          store.get(Player.class, p.playerId).name,
          p.goalkeeperInviteId != null
            ? "GOALKEEPER"
            : m == null
              ? null
              : m.primaryPosition,
          m == null ? null : m.secondaryPosition,
          m == null ? null : m.skillLevel,
          fixed.containsKey(p.playerId),
          p.goalkeeperInviteId != null
        )
      );
    }
    List<List<DrawPlayer>> best = null;
    double bestCost = Double.POSITIVE_INFINITY;
    int attempts = input.mode().equals("BALANCED") ? 160 : 1;
    for (int attempt = 0; attempt < attempts; attempt++) {
      List<List<DrawPlayer>> rosters = new ArrayList<>();
      int[] counts = new int[c.teams.size()];
      for (int i = 0; i < counts.length; i++) {
        Team t = c.teams.get(i);
        rosters.add(
          new ArrayList<>(
            players
              .values()
              .stream()
              .filter(p -> t.id.equals(fixed.get(p.playerId())))
              .toList()
          )
        );
        counts[i] =
          rosters.get(i).size() + (c.reservedTeams.contains(t.id) ? 1 : 0);
        if (counts[i] > c.game.teamSize) throw ApiException.conflict(
          "Os jogadores fixos excedem a capacidade de um time."
        );
      }
      List<DrawPlayer> remaining = new ArrayList<>(
        players
          .values()
          .stream()
          .filter(p -> !p.fixed())
          .toList()
      );
      Collections.shuffle(remaining);
      for (DrawPlayer p : remaining) {
        int min = Arrays.stream(counts).min().orElseThrow();
        if (min >= c.game.teamSize) throw ApiException.conflict(
          "Não há vagas para todos os confirmados."
        );
        List<Integer> choices = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) if (
          counts[i] == min
        ) choices.add(i);
        Collections.shuffle(choices);
        int index = choices.getFirst();
        rosters.get(index).add(p);
        counts[index]++;
      }
      double cost = cost(rosters, c);
      if (cost < bestCost) {
        bestCost = cost;
        best = rosters;
      }
    }
    List<DrawTeam> result = new ArrayList<>();
    for (int i = 0; i < c.teams.size(); i++) {
      Team t = c.teams.get(i);
      List<DrawPlayer> roster = best.get(i);
      result.add(
        new DrawTeam(
          t.id,
          t.name,
          t.color,
          c.reservedTeams.contains(t.id) ? 1 : 0,
          roster.stream().mapToInt(Draws::level).average().orElse(0),
          List.copyOf(roster)
        )
      );
    }
    // Retain applied history, but replace abandoned previews rather than growing without limit.
    store
      .list(
        DrawAttempt.class,
        "from DrawAttempt where gameId=:game and appliedAt is null",
        "game",
        id
      )
      .forEach(store::remove);
    DrawAttempt attempt = new DrawAttempt();
    attempt.gameId = id;
    attempt.organizerId = user;
    attempt.fingerprint = c.fingerprint;
    attempt.mode = input.mode();
    attempt.createdAt = clock.instant();
    attempt.expiresAt = attempt.createdAt.plusSeconds(900);
    DrawPreview view = new DrawPreview(
      attempt.id,
      input.mode(),
      attempt.expiresAt,
      (int) players
        .values()
        .stream()
        .filter(p -> p.skillLevel() == null)
        .count(),
      result
    );
    attempt.snapshot = json.writeValueAsString(view);
    store.save(attempt);
    return view;
  }

  private static int level(DrawPlayer p) {
    return p.skillLevel() == null ? 3 : p.skillLevel();
  }

  private static double cost(List<List<DrawPlayer>> rosters, Context c) {
    double result = 0;
    for (int i = 0; i < rosters.size(); i++) for (
      int j = i + 1;
      j < rosters.size();
      j++
    ) {
      // Strength comparison is average, so a team with one extra player is not automatically penalized.
      double a = rosters
        .get(i)
        .stream()
        .mapToInt(Draws::level)
        .average()
        .orElse(3);
      double b = rosters
        .get(j)
        .stream()
        .mapToInt(Draws::level)
        .average()
        .orElse(3);
      result += 8 * (a - b) * (a - b);
      for (String position : List.of(
        "GOALKEEPER",
        "DEFENSE",
        "MIDFIELD",
        "ATTACK"
      )) {
        double pa =
          coverage(rosters.get(i), position) +
          (position.equals("GOALKEEPER") &&
          c.reservedTeams.contains(c.teams.get(i).id)
            ? 1
            : 0);
        double pb =
          coverage(rosters.get(j), position) +
          (position.equals("GOALKEEPER") &&
          c.reservedTeams.contains(c.teams.get(j).id)
            ? 1
            : 0);
        result +=
          (position.equals("GOALKEEPER") ? 3 : 0.6) * Math.pow(pa - pb, 2);
      }
    }
    return result;
  }

  private static double coverage(List<DrawPlayer> roster, String position) {
    return roster
      .stream()
      .mapToDouble(p ->
        position.equals(p.primaryPosition())
          ? 1
          : position.equals(p.secondaryPosition())
            ? 0.5
            : "VERSATILE".equals(p.primaryPosition()) &&
                !position.equals("GOALKEEPER")
              ? 0.2
              : 0
      )
      .sum();
  }

  public GameDetail apply(UUID user, UUID gameId, UUID previewId) {
    Context c = context(user, gameId);
    DrawAttempt attempt = store.get(DrawAttempt.class, previewId);
    if (
      !attempt.gameId.equals(gameId) || !attempt.organizerId.equals(user)
    ) throw ApiException.forbidden();
    if (attempt.appliedAt != null) throw ApiException.conflict(
      "Este sorteio já foi aplicado."
    );
    if (
      !attempt.expiresAt.isAfter(clock.instant()) ||
      !attempt.fingerprint.equals(c.fingerprint)
    ) throw ApiException.conflict(
      "A prévia mudou ou expirou. Gere uma nova combinação antes de aplicar."
    );
    DrawPreview view = json.readValue(attempt.snapshot, DrawPreview.class);
    Map<UUID, Participation> people = c.people
      .stream()
      .filter(p -> p.status.equals("CONFIRMED"))
      .collect(Collectors.toMap(p -> p.playerId, p -> p));
    for (Participation p : people.values())
      if (p.goalkeeperInviteId == null) p.slot = null;
    store.flush(); // Avoid collisions in the unique lineup slot constraint.
    for (DrawTeam t : view.teams())
      for (DrawPlayer p : t.players())
        people.get(p.playerId()).teamId = t.teamId();
    for (Team t : c.teams) {
      t.version++;
      if (t.captainId != null && !people.containsKey(t.captainId)) t.captainId =
        null;
    }
    attempt.appliedAt = clock.instant();
    return games.get(user, gameId);
  }

  public List<DrawHistory> history(UUID user, UUID gameId) {
    Game game = store.get(Game.class, gameId);
    groups.requireOwner(user, game.clubId);
    return store
      .list(
        DrawAttempt.class,
        "from DrawAttempt where gameId=:game and appliedAt is not null order by appliedAt desc",
        "game",
        gameId
      )
      .stream()
      .map(a ->
        new DrawHistory(
          a.appliedAt,
          json.readValue(a.snapshot, DrawPreview.class)
        )
      )
      .toList();
  }
}
