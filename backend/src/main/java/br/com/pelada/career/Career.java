package br.com.pelada.career;

import br.com.pelada.career.CareerContracts.*;
import br.com.pelada.career.CareerEntities.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.groups.Groups;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Career {

  private record Milestone(
    String code,
    String name,
    int threshold,
    String reward
  ) {}

  private static final List<Milestone> CATALOG = List.of(
    new Milestone("FIRST_APPEARANCE", "Tô dentro", 1, "Selo de estreia"),
    new Milestone(
      "FIVE_APPEARANCES",
      "Já é de casa",
      5,
      "Título para a figurinha"
    ),
    new Milestone(
      "TEN_APPEARANCES",
      "Figurinha carimbada",
      10,
      "Moldura para a figurinha"
    ),
    new Milestone(
      "TWENTY_FIVE_APPEARANCES",
      "Parte da história",
      25,
      "Selo e título para a figurinha"
    )
  );
  private static final Set<String> TITLES = Set.of(
    "FIVE_APPEARANCES",
    "TWENTY_FIVE_APPEARANCES"
  );
  private static final Set<String> BADGES = Set.of(
    "FIRST_APPEARANCE",
    "TWENTY_FIVE_APPEARANCES"
  );
  private final Store store;
  private final Groups groups;
  private final Clock clock;

  public Career(Store store, Groups groups, Clock clock) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<CareerGroup> directory(UUID user) {
    Set<UUID> memberships = store
      .list(Member.class, "from Member where playerId=:user", "user", user)
      .stream()
      .map(m -> m.clubId)
      .collect(Collectors.toSet());
    return store
      .list(
        Club.class,
        "select c from Club c where c.demo=false and (c.id in (select m.clubId from Member m where m.playerId=:user) or c.id in (select g.clubId from Game g, Participation p where p.gameId=g.id and p.playerId=:user and p.status='CONFIRMED') or c.id in (select f.clubId from CareerCard f where f.playerId=:user)) order by c.name",
        "user",
        user
      )
      .stream()
      .map(c -> new CareerGroup(c.id, c.name, memberships.contains(c.id)))
      .toList();
  }

  public ProgramView configure(UUID user, UUID clubId, boolean active) {
    groups.requireOwner(user, clubId);
    Club club = store.refreshLocked(Club.class, clubId);
    Optional<CareerPeriod> open = periods(clubId)
      .stream()
      .filter(p -> p.endedAt == null)
      .findFirst();
    if (active && open.isEmpty()) {
      CareerPeriod period = new CareerPeriod();
      period.clubId = clubId;
      period.startedAt = clock.instant();
      store.save(period);
    } else if (!active && open.isPresent()) open.get().endedAt =
      clock.instant();
    store.flush();
    return program(user, club);
  }

  private List<CareerPeriod> periods(UUID clubId) {
    return store.list(
      CareerPeriod.class,
      "from CareerPeriod where clubId=:club order by startedAt,id",
      "club",
      clubId
    );
  }

  private ProgramView program(UUID user, Club club) {
    List<CareerPeriod> all = periods(club.id);
    return new ProgramView(
      all.stream().anyMatch(p -> p.endedAt == null),
      all.isEmpty() ? null : all.getFirst().startedAt,
      club.ownerId.equals(user) && !club.demo && groups.isMember(user, club.id)
    );
  }

  private boolean eligible(Game game) {
    return periods(game.clubId)
      .stream()
      .anyMatch(
        p ->
          !game.startsAt.isBefore(p.startedAt) &&
          (p.endedAt == null || game.startsAt.isBefore(p.endedAt))
      );
  }

  private Optional<AttendanceReview> reviewed(UUID gameId) {
    return store.first(
      AttendanceReview.class,
      "from AttendanceReview where id=:game",
      "game",
      gameId
    );
  }

  private List<VerifiedAttendance> verified(UUID gameId) {
    return store.list(
      VerifiedAttendance.class,
      "from VerifiedAttendance where gameId=:game order by playerId",
      "game",
      gameId
    );
  }

  private List<Participation> roster(UUID gameId) {
    return store.list(
      Participation.class,
      "from Participation where gameId=:game and status='CONFIRMED' order by playerId",
      "game",
      gameId
    );
  }

  @Transactional(readOnly = true)
  public ReviewView review(UUID user, UUID gameId) {
    Game game = store.get(Game.class, gameId);
    groups.requireOwner(user, game.clubId);
    return reviewView(game);
  }

  private ReviewView reviewView(Game game) {
    Optional<AttendanceReview> saved = reviewed(game.id);
    List<VerifiedAttendance> attendance = verified(game.id);
    Map<UUID, Boolean> answers = attendance
      .stream()
      .collect(Collectors.toMap(a -> a.playerId, a -> a.present));
    Map<UUID, Participation> candidates = roster(game.id)
      .stream()
      .collect(Collectors.toMap(p -> p.playerId, p -> p));
    Set<UUID> ids = saved.isPresent() ? answers.keySet() : candidates.keySet();
    List<ReviewPlayer> players = ids
      .stream()
      .map(id ->
        new ReviewPlayer(
          id,
          store.get(Player.class, id).name,
          candidates.containsKey(id) &&
            candidates.get(id).goalkeeperInviteId != null,
          answers.get(id)
        )
      )
      .sorted(
        Comparator.comparing(ReviewPlayer::name).thenComparing(
          ReviewPlayer::playerId
        )
      )
      .toList();
    boolean qualifies = saved
      .map(r -> r.eligible)
      .orElseGet(() -> eligible(game));
    String message = game.cancelled
      ? "Esta pelada foi cancelada e não gera conquistas."
      : game.startsAt.isAfter(clock.instant())
        ? "Confira as presenças depois do horário da pelada."
        : game.matchStartedAt != null && game.matchEndedAt == null
          ? "Encerre a partida ao vivo antes de conferir as presenças."
          : !qualifies
            ? "Esta pelada aconteceu fora do período ativo das conquistas."
            : players.isEmpty()
              ? "Não há jogadores com vaga confirmada para conferir."
              : "";
    List<AuditView> history = store
      .list(
        AttendanceAudit.class,
        "from AttendanceAudit where gameId=:game order by version desc",
        "game",
        game.id
      )
      .stream()
      .map(a ->
        new AuditView(
          a.version,
          store.get(Player.class, a.reviewedBy).name,
          a.createdAt
        )
      )
      .toList();
    return new ReviewView(
      game.id,
      qualifies,
      message.isEmpty(),
      message,
      saved.map(r -> r.version).orElse(0L),
      saved.map(r -> r.reviewedAt).orElse(null),
      players,
      history
    );
  }

  public ReviewView saveReview(UUID user, UUID gameId, ReviewInput input) {
    Game found = store.get(Game.class, gameId);
    groups.requireOwner(user, found.clubId);
    // Match the group -> game order used by recurring games and reservation settlement.
    store.refreshLocked(Club.class, found.clubId);
    Game game = store.refreshLocked(Game.class, gameId);
    ReviewView view = reviewView(game);
    if (!view.canReview()) throw ApiException.conflict(view.message());
    if (!Boolean.TRUE.equals(input.happened())) throw new ApiException(
      400,
      "Confirme que esta pelada aconteceu."
    );
    if (
      input.players() == null ||
      input
        .players()
        .stream()
        .anyMatch(p -> p == null || p.playerId() == null || p.present() == null)
    ) throw new ApiException(400, "Confira a presença de todos os jogadores.");
    Map<UUID, Boolean> choices = new HashMap<>();
    for (AttendanceChoice choice : input.players()) {
      if (
        choices.putIfAbsent(choice.playerId(), choice.present()) != null
      ) throw new ApiException(
        400,
        "Cada jogador deve aparecer uma única vez na conferência."
      );
    }
    Set<UUID> expected = view
      .players()
      .stream()
      .map(ReviewPlayer::playerId)
      .collect(Collectors.toSet());
    if (!expected.equals(choices.keySet())) throw ApiException.conflict(
      "Confira todos os jogadores com vaga. Atualize a lista antes de salvar."
    );
    List<VerifiedAttendance> existing = verified(gameId);
    Map<UUID, Boolean> before = existing
      .stream()
      .collect(Collectors.toMap(a -> a.playerId, a -> a.present));
    // A retried identical submission is harmless, including when its response was lost.
    if (view.reviewedAt() != null && before.equals(choices)) return view;
    if (view.version() != input.version()) throw ApiException.conflict(
      "As presenças foram atualizadas. Recarregue a conferência antes de salvar."
    );
    AttendanceReview review = reviewed(gameId).orElseGet(() -> {
      AttendanceReview created = new AttendanceReview();
      created.id = gameId;
      created.eligible = view.eligible();
      created.reviewedAt = clock.instant();
      created.reviewedBy = user;
      return store.save(created);
    });
    review.version++;
    review.reviewedAt = clock.instant();
    review.reviewedBy = user;
    Map<UUID, VerifiedAttendance> rows = existing
      .stream()
      .collect(Collectors.toMap(a -> a.playerId, a -> a));
    for (Map.Entry<UUID, Boolean> choice : choices.entrySet()) {
      VerifiedAttendance row = rows.get(choice.getKey());
      if (row == null) {
        row = new VerifiedAttendance();
        row.gameId = gameId;
        row.playerId = choice.getKey();
        store.save(row);
      }
      row.present = choice.getValue();
    }
    AttendanceAudit audit = new AttendanceAudit();
    audit.gameId = gameId;
    audit.version = review.version;
    audit.reviewedBy = user;
    audit.createdAt = review.reviewedAt;
    audit.beforeSnapshot = snapshot(before);
    audit.afterSnapshot = snapshot(choices);
    store.save(audit);
    store.flush();
    for (UUID playerId : expected) reconcile(game.clubId, playerId);
    return reviewView(game);
  }

  private String snapshot(Map<UUID, Boolean> values) {
    return values
      .entrySet()
      .stream()
      .sorted(Map.Entry.comparingByKey())
      .map(e -> e.getKey() + "=" + e.getValue())
      .collect(Collectors.joining("\n"));
  }

  private Club requireOwnAccess(UUID user, UUID clubId) {
    Club club = store.get(Club.class, clubId);
    if (club.demo) throw ApiException.forbidden();
    boolean known =
      groups.isMember(user, clubId) ||
      card(clubId, user).isPresent() ||
      store
        .first(
          Participation.class,
          "select p from Participation p, Game g where p.gameId=g.id and g.clubId=:club and p.playerId=:user and p.status='CONFIRMED'",
          "club",
          clubId,
          "user",
          user
        )
        .isPresent();
    if (!known) throw ApiException.forbidden();
    return club;
  }

  private Optional<CareerCard> card(UUID clubId, UUID playerId) {
    return store.first(
      CareerCard.class,
      "from CareerCard where clubId=:club and playerId=:player",
      "club",
      clubId,
      "player",
      playerId
    );
  }

  private List<Game> appearances(UUID clubId, UUID playerId) {
    return store.list(
      Game.class,
      "select g from Game g, AttendanceReview r, VerifiedAttendance a where g.id=r.id and a.gameId=g.id and g.clubId=:club and a.playerId=:player and a.present=true and r.eligible=true and g.cancelled=false order by g.startsAt,g.id",
      "club",
      clubId,
      "player",
      playerId
    );
  }

  private List<CareerAchievement> achievements(UUID clubId, UUID playerId) {
    return store.list(
      CareerAchievement.class,
      "from CareerAchievement where clubId=:club and playerId=:player",
      "club",
      clubId,
      "player",
      playerId
    );
  }

  private CareerCard reconcile(UUID clubId, UUID playerId) {
    CareerCard card = card(clubId, playerId).orElseGet(() -> {
      CareerCard created = new CareerCard();
      created.clubId = clubId;
      created.playerId = playerId;
      return store.save(created);
    });
    int count = appearances(clubId, playerId).size();
    if (!groups.isMember(playerId, clubId)) card.shared = false;
    if (count < card.observedCount) card.correctedAt = clock.instant();
    card.observedCount = count;
    Map<String, CareerAchievement> awards = achievements(clubId, playerId)
      .stream()
      .collect(Collectors.toMap(a -> a.code, a -> a));
    for (Milestone milestone : CATALOG) {
      CareerAchievement award = awards.get(milestone.code());
      if (count >= milestone.threshold() && award == null) {
        CareerAchievement created = new CareerAchievement();
        created.clubId = clubId;
        created.playerId = playerId;
        created.code = milestone.code();
        created.awardedAt = clock.instant();
        store.save(created);
      } else if (count < milestone.threshold() && award != null) store.remove(
        award
      );
    }
    Set<String> unlocked = CATALOG.stream()
      .filter(m -> count >= m.threshold())
      .map(Milestone::code)
      .collect(Collectors.toSet());
    if (card.title != null && !unlocked.contains(card.title)) card.title = null;
    if (card.frame != null && !unlocked.contains(card.frame)) card.frame = null;
    card.badges = String.join(
      ",",
      splitBadges(card.badges).stream().filter(unlocked::contains).toList()
    );
    store.flush();
    return card;
  }

  public CareerView mine(UUID user, UUID clubId) {
    requireOwnAccess(user, clubId);
    Club club = store.refreshLocked(Club.class, clubId);
    CareerCard card = reconcile(clubId, user);
    Map<String, CareerAchievement> awards = achievements(clubId, user)
      .stream()
      .collect(Collectors.toMap(a -> a.code, a -> a));
    List<AchievementView> catalog = CATALOG.stream()
      .map(m -> {
        CareerAchievement a = awards.get(m.code());
        return new AchievementView(
          m.code(),
          m.name(),
          m.threshold(),
          m.reward(),
          a == null ? null : a.awardedAt,
          a != null && a.seenAt == null
        );
      })
      .toList();
    List<AppearanceView> history = appearances(clubId, user)
      .stream()
      .sorted(
        Comparator.comparing((Game g) -> g.startsAt)
          .reversed()
          .thenComparing(g -> g.id)
      )
      .map(g -> new AppearanceView(g.id, g.title, g.startsAt))
      .toList();
    List<CareerPeriod> activePeriods = periods(clubId);
    List<PendingView> pending = store
      .list(
        Game.class,
        "select g from Game g, Participation p where p.gameId=g.id and p.playerId=:user and g.clubId=:club and p.status='CONFIRMED' and g.cancelled=false and g.startsAt<=:now and g.id not in (select r.id from AttendanceReview r) order by g.startsAt desc",
        "user",
        user,
        "club",
        clubId,
        "now",
        clock.instant()
      )
      .stream()
      .filter(g ->
        activePeriods
          .stream()
          .anyMatch(
            p ->
              !g.startsAt.isBefore(p.startedAt) &&
              (p.endedAt == null || g.startsAt.isBefore(p.endedAt))
          )
      )
      .map(g -> new PendingView(g.id, g.title, g.startsAt))
      .toList();
    return new CareerView(
      clubId,
      club.name,
      club.timeZone,
      program(user, club),
      groups.isMember(user, clubId),
      cardView(card),
      history.size(),
      catalog,
      history,
      pending,
      card.correctedAt != null &&
        (card.correctionSeenAt == null ||
          card.correctedAt.isAfter(card.correctionSeenAt))
    );
  }

  private List<String> splitBadges(String value) {
    return value.isBlank() ? List.of() : List.of(value.split(","));
  }

  private CardView cardView(CareerCard card) {
    return new CardView(
      card.playerId,
      store.get(Player.class, card.playerId).name,
      card.title,
      card.frame,
      splitBadges(card.badges),
      card.shared,
      br.com.pelada.auth.AccountProfile.photoUrl(
        store.get(Player.class, card.playerId)
      )
    );
  }

  public CardView customize(UUID user, UUID clubId, CardInput input) {
    requireOwnAccess(user, clubId);
    store.refreshLocked(Club.class, clubId);
    if (
      Boolean.TRUE.equals(input.shared()) && !groups.isMember(user, clubId)
    ) throw new ApiException(
      403,
      "A figurinha de convidados e de quem saiu do grupo permanece privada."
    );
    CareerCard card = reconcile(clubId, user);
    Set<String> unlocked = achievements(clubId, user)
      .stream()
      .map(a -> a.code)
      .collect(Collectors.toSet());
    if (
      input.shared() == null ||
      input.badges() == null ||
      input.badges().size() > 3 ||
      new HashSet<>(input.badges()).size() != input.badges().size() ||
      (input.title() != null &&
        (!TITLES.contains(input.title()) ||
          !unlocked.contains(input.title()))) ||
      (input.frame() != null &&
        (!input.frame().equals("TEN_APPEARANCES") ||
          !unlocked.contains(input.frame()))) ||
      input
        .badges()
        .stream()
        .anyMatch(b -> !BADGES.contains(b) || !unlocked.contains(b))
    ) throw new ApiException(
      400,
      "Escolha apenas títulos, molduras e selos que você já conquistou."
    );
    card.shared = input.shared();
    card.title = input.title();
    card.frame = input.frame();
    card.badges = String.join(",", input.badges());
    return cardView(card);
  }

  public void seen(UUID user, UUID clubId) {
    requireOwnAccess(user, clubId);
    store.refreshLocked(Club.class, clubId);
    CareerCard card = reconcile(clubId, user);
    Instant now = clock.instant();
    for (CareerAchievement award : achievements(clubId, user))
      if (award.seenAt == null) award.seenAt = now;
    card.correctionSeenAt = now;
  }

  public CardView shared(UUID user, UUID clubId, UUID playerId) {
    groups.requireMember(user, clubId);
    store.refreshLocked(Club.class, clubId);
    if (!groups.isMember(playerId, clubId)) throw ApiException.notFound();
    CareerCard existing = card(clubId, playerId).orElseThrow(
      ApiException::notFound
    );
    if (!existing.shared && !user.equals(playerId)) throw new ApiException(
      404,
      "Esta figurinha não foi compartilhada com o grupo."
    );
    CareerCard current = reconcile(clubId, playerId);
    if (!current.shared && !user.equals(playerId)) throw new ApiException(
      404,
      "Esta figurinha não foi compartilhada com o grupo."
    );
    return cardView(current);
  }
}
