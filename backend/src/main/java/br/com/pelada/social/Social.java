package br.com.pelada.social;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.ApiException;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.domain.Store;
import br.com.pelada.groups.Groups;
import br.com.pelada.social.MunicipalityCatalog.Municipality;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Social {

  static final Set<Integer> RADII = Set.of(10, 25, 50, 100, 200);
  private static final Set<String> CATEGORIES = Set.of("PICKUP", "FIXED_TEAM");
  static final Set<String> DAYS = Set.of(
    "MON",
    "TUE",
    "WED",
    "THU",
    "FRI",
    "SAT",
    "SUN"
  );
  static final Set<String> PERIODS = Set.of("MORNING", "AFTERNOON", "EVENING");
  static final List<String> LEVELS = List.of(
    "RECREATIONAL",
    "INTERMEDIATE",
    "COMPETITIVE"
  );
  private static final Set<String> TERMINAL = Set.of(
    "DECLINED",
    "EXPIRED",
    "CANCELLED"
  );

  private final Store store;
  private final Groups groups;
  private final MunicipalityCatalog municipalities;
  private final Clock clock;

  public Social(
    Store store,
    Groups groups,
    MunicipalityCatalog municipalities,
    Clock clock
  ) {
    this.store = store;
    this.groups = groups;
    this.municipalities = municipalities;
    this.clock = clock;
  }

  // The public municipal catalog also serves goalkeeper profiles, so any signed-in player may use it.
  @Transactional(readOnly = true)
  public List<MunicipalityView> cities(String query) {
    return municipalities
      .search(query)
      .stream()
      .map(m -> new MunicipalityView(m.code(), m.name(), m.uf(), m.label()))
      .toList();
  }

  @Transactional(readOnly = true)
  public List<SocialOwnedGroupView> mine(UUID user) {
    return ownedGroups(user)
      .stream()
      .map(club ->
        new SocialOwnedGroupView(
          club.id,
          club.name,
          store
            .first(
              SocialListing.class,
              "from SocialListing where clubId=:club",
              "club",
              club.id
            )
            .map(listing -> listingView(club, listing, true))
            .orElse(null)
        )
      )
      .toList();
  }

  public SocialListingView saveListing(
    UUID user,
    UUID clubId,
    SocialListingInput input
  ) {
    Club club = groups.requireOwner(user, clubId);
    Municipality municipality = municipalities
      .find(input.municipalityCode())
      .orElseThrow(() -> new ApiException(400, "Selecione uma cidade válida."));
    List<String> categories = sorted(input.categories());
    List<String> days = sorted(input.preferredDays());
    List<String> periods = sorted(input.preferredPeriods());
    requireValues(
      categories,
      CATEGORIES,
      "Selecione ao menos um tipo de equipe."
    );
    requireValues(days, DAYS, "Revise os dias preferidos.");
    requireValues(periods, PERIODS, "Revise os períodos preferidos.");
    if (!LEVELS.contains(input.skillLevel())) throw new ApiException(
      400,
      "Selecione um nível de jogo válido."
    );

    Instant now = clock.instant();
    SocialListing listing = store
      .first(
        SocialListing.class,
        "from SocialListing where clubId=:club",
        "club",
        club.id
      )
      .orElse(null);
    boolean created = listing == null;
    if (created) listing = new SocialListing(club.id, now);
    listing.published = input.published();
    listing.categories = String.join(",", categories);
    listing.municipalityCode = municipality.code();
    listing.courtName = input.courtName().strip();
    listing.neighborhood = input.neighborhood().strip();
    listing.description = input.description().strip();
    listing.skillLevel = input.skillLevel();
    listing.preferredDays = String.join(",", days);
    listing.preferredPeriods = String.join(",", periods);
    listing.updatedAt = now;
    if (created) store.save(listing);
    return listingView(club, listing, true);
  }

  public void removeListing(UUID user, UUID clubId) {
    Club club = groups.requireOwner(user, clubId);
    store
      .first(
        SocialListing.class,
        "from SocialListing where clubId=:club",
        "club",
        club.id
      )
      .ifPresent(store::remove);
  }

  @Transactional(readOnly = true)
  public List<SocialSearchResult> search(
    UUID user,
    String cityCode,
    int radiusKm,
    String category,
    String skillLevel,
    List<String> days,
    List<String> periods
  ) {
    requireOrganizer(user);
    if (!RADII.contains(radiusKm)) throw new ApiException(
      400,
      "Selecione um raio de busca válido."
    );
    if (
      category != null && !category.isBlank() && !CATEGORIES.contains(category)
    ) throw new ApiException(400, "Selecione um tipo de equipe válido.");
    if (
      skillLevel != null &&
      !skillLevel.isBlank() &&
      !LEVELS.contains(skillLevel)
    ) throw new ApiException(400, "Selecione um nível de jogo válido.");
    List<String> requestedDays = normalizedFilter(
      days,
      DAYS,
      "Revise os dias da busca."
    );
    List<String> requestedPeriods = normalizedFilter(
      periods,
      PERIODS,
      "Revise os períodos da busca."
    );
    Municipality origin = municipalities
      .find(cityCode)
      .orElseThrow(() -> new ApiException(400, "Selecione uma cidade válida."));
    Set<UUID> ownClubs = ownedGroups(user)
      .stream()
      .map(club -> club.id)
      .collect(Collectors.toSet());

    return store
      .list(SocialListing.class, "from SocialListing where published=true")
      .stream()
      .filter(listing -> !ownClubs.contains(listing.clubId))
      .filter(
        listing ->
          category == null ||
          category.isBlank() ||
          values(listing.categories).contains(category)
      )
      .filter(
        listing ->
          skillLevel == null ||
          skillLevel.isBlank() ||
          listing.skillLevel.equals(skillLevel)
      )
      .filter(listing ->
        values(listing.preferredDays).containsAll(requestedDays)
      )
      .filter(listing ->
        values(listing.preferredPeriods).containsAll(requestedPeriods)
      )
      .map(listing -> {
        Club club = store.get(Club.class, listing.clubId);
        Municipality target = municipalities
          .find(listing.municipalityCode)
          .orElse(null);
        if (target == null) return null;
        double distance = distanceKm(origin, target);
        if (distance > radiusKm) return null;
        return new SocialSearchResult(
          listingView(club, listing, false),
          Math.round(distance * 10d) / 10d,
          !requestedDays.isEmpty() || !requestedPeriods.isEmpty(),
          skillLevel != null && !skillLevel.isBlank()
        );
      })
      .filter(Objects::nonNull)
      .sorted(Comparator.comparingDouble(SocialSearchResult::distanceKm))
      .limit(100)
      .toList();
  }

  public SocialMatchView invite(UUID user, SocialInviteInput input) {
    Club host = groups.requireOwner(user, input.senderClubId());
    Club guest = store.get(Club.class, input.targetClubId());
    if (
      host.id.equals(guest.id) || host.ownerId.equals(guest.ownerId)
    ) throw new ApiException(400, "Escolha outro grupo para o amistoso.");
    if (guest.demo || !isPublished(guest.id)) throw ApiException.notFound();
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "A data do amistoso precisa estar no futuro."
    );
    expirePair(host.id, guest.id);
    boolean open = store
      .first(
        SocialMatch.class,
        "from SocialMatch where ((hostClubId=:host and guestClubId=:guest) or (hostClubId=:guest and guestClubId=:host)) and status in ('PENDING','NEGOTIATING','CHANGE_PENDING')",
        "host",
        host.id,
        "guest",
        guest.id
      )
      .isPresent();
    if (open) throw ApiException.conflict(
      "Já existe um convite em aberto entre esses grupos."
    );

    Instant now = clock.instant();
    SocialMatch match = new SocialMatch(
      host.id,
      guest.id,
      input.startsAt(),
      input.location().strip(),
      input.message().strip(),
      now,
      now.plus(Duration.ofDays(14))
    );
    try {
      store.save(match);
      store.flush();
    } catch (DataIntegrityViolationException error) {
      throw ApiException.conflict(
        "Já existe um convite em aberto entre esses grupos."
      );
    }
    return matchView(match, user);
  }

  public SocialMatchView accept(UUID user, UUID matchId) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requireGuestOwner(user, match);
    expireIfNeeded(match);
    if (match.status.equals("EXPIRED")) return matchView(match, user);
    if (!match.status.equals("PENDING")) throw ApiException.conflict(
      "Este convite não está mais aguardando resposta."
    );
    match.status = "NEGOTIATING";
    match.acceptedAt = clock.instant();
    match.updatedAt = match.acceptedAt;
    addMessage(
      match,
      user,
      "Convite aceito. Vamos combinar os detalhes do amistoso."
    );
    return matchView(match, user);
  }

  public SocialMatchView decline(UUID user, UUID matchId) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requireGuestOwner(user, match);
    expireIfNeeded(match);
    if (match.status.equals("EXPIRED")) return matchView(match, user);
    if (!match.status.equals("PENDING")) throw ApiException.conflict(
      "Este convite não está mais aguardando resposta."
    );
    match.status = "DECLINED";
    match.updatedAt = clock.instant();
    return matchView(match, user);
  }

  public SocialMatchView confirm(UUID user, UUID matchId) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requirePartyOwner(user, match);
    if (
      !Set.of("NEGOTIATING", "CHANGE_PENDING").contains(match.status)
    ) throw ApiException.conflict(
      "Não há uma proposta aguardando confirmação."
    );
    if (isHostOwner(user, match)) match.hostConfirmedRevision = match.revision;
    else match.guestConfirmedRevision = match.revision;
    match.updatedAt = clock.instant();
    addMessage(match, user, "Confirmou os detalhes desta proposta.");
    if (
      match.hostConfirmedRevision == match.revision &&
      match.guestConfirmedRevision == match.revision
    ) {
      match.startsAt = match.proposedStartsAt;
      match.location = match.proposedLocation;
      match.status = "SCHEDULED";
      match.updatedAt = clock.instant();
      addMessage(
        match,
        user,
        "O amistoso foi confirmado e entrou nas agendas dos dois grupos."
      );
    }
    return matchView(match, user);
  }

  public SocialMatchView propose(
    UUID user,
    UUID matchId,
    SocialProposalInput input
  ) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requirePartyOwner(user, match);
    if (
      !Set.of("NEGOTIATING", "SCHEDULED", "CHANGE_PENDING").contains(
        match.status
      )
    ) throw ApiException.conflict(
      "Este amistoso não pode receber uma nova proposta."
    );
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "A data do amistoso precisa estar no futuro."
    );
    match.revision++;
    match.proposedStartsAt = input.startsAt();
    match.proposedLocation = input.location().strip();
    match.proposedBy = user;
    if (isHostOwner(user, match)) {
      match.hostConfirmedRevision = match.revision;
      match.guestConfirmedRevision = 0;
    } else {
      match.guestConfirmedRevision = match.revision;
      match.hostConfirmedRevision = 0;
    }
    match.status = match.startsAt == null ? "NEGOTIATING" : "CHANGE_PENDING";
    match.updatedAt = clock.instant();
    addMessage(
      match,
      user,
      "Propôs uma nova data, horário ou local. A mudança depende de confirmação dos dois."
    );
    return matchView(match, user);
  }

  public SocialMatchView cancel(UUID user, UUID matchId) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requirePartyOwner(user, match);
    if (TERMINAL.contains(match.status)) throw ApiException.conflict(
      "Este convite ou amistoso já foi encerrado."
    );
    match.status = "CANCELLED";
    match.cancelledBy = user;
    match.cancelledAt = clock.instant();
    match.updatedAt = match.cancelledAt;
    if (match.acceptedAt != null) addMessage(
      match,
      user,
      "Cancelou o convite ou amistoso."
    );
    return matchView(match, user);
  }

  public List<SocialMatchView> invitations(UUID user) {
    requireOrganizer(user);
    List<SocialMatch> matches = store.list(
      SocialMatch.class,
      "select m from SocialMatch m, Club h, Club g where m.hostClubId=h.id and m.guestClubId=g.id and (h.ownerId=:user or g.ownerId=:user) order by m.updatedAt desc",
      "user",
      user
    );
    return matches
      .stream()
      .map(match -> matchView(match, user))
      .toList();
  }

  public List<SocialMessageView> messages(UUID user, UUID matchId) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requirePartyOwner(user, match);
    if (match.acceptedAt == null) throw ApiException.forbidden();
    Instant now = clock.instant();
    if (isHostOwner(user, match)) match.hostLastReadAt = now;
    else match.guestLastReadAt = now;
    return store
      .list(
        SocialMessage.class,
        "from SocialMessage where matchId=:match order by createdAt, id",
        "match",
        match.id
      )
      .stream()
      .map(message -> messageView(message, user))
      .toList();
  }

  public SocialMessageView sendMessage(
    UUID user,
    UUID matchId,
    SocialMessageInput input
  ) {
    SocialMatch match = store.lock(SocialMatch.class, matchId);
    requirePartyOwner(user, match);
    if (match.acceptedAt == null) throw ApiException.forbidden();
    SocialMessage message = addMessage(match, user, input.body().strip());
    match.updatedAt = message.createdAt;
    return messageView(message, user);
  }

  @Transactional(readOnly = true)
  public List<SocialScheduleView> schedule(UUID user, UUID clubId) {
    groups.requireMember(user, clubId);
    return store
      .list(
        SocialMatch.class,
        "from SocialMatch where (hostClubId=:club or guestClubId=:club) and status in ('SCHEDULED','CHANGE_PENDING','CANCELLED') and startsAt is not null order by startsAt",
        "club",
        clubId
      )
      .stream()
      .map(match -> {
        boolean hostSide = match.hostClubId.equals(clubId);
        Club opponent = store.get(
          Club.class,
          hostSide ? match.guestClubId : match.hostClubId
        );
        return new SocialScheduleView(
          match.id,
          clubId,
          opponent.name,
          match.startsAt,
          match.location,
          match.status
        );
      })
      .toList();
  }

  private List<Club> ownedGroups(UUID user) {
    List<Club> clubs = store.list(
      Club.class,
      "from Club where ownerId=:user and demo=false order by name",
      "user",
      user
    );
    if (clubs.isEmpty()) throw ApiException.forbidden();
    return clubs;
  }

  private void requireOrganizer(UUID user) {
    ownedGroups(user);
  }

  private boolean isPublished(UUID clubId) {
    return store
      .first(
        SocialListing.class,
        "from SocialListing where clubId=:club and published=true",
        "club",
        clubId
      )
      .isPresent();
  }

  private void expirePair(UUID first, UUID second) {
    List<SocialMatch> pair = store.list(
      SocialMatch.class,
      "from SocialMatch where ((hostClubId=:first and guestClubId=:second) or (hostClubId=:second and guestClubId=:first)) and status='PENDING'",
      "first",
      first,
      "second",
      second
    );
    pair.forEach(this::expireIfNeeded);
  }

  private void expireIfNeeded(SocialMatch match) {
    if (
      match.status.equals("PENDING") &&
      !match.expiresAt.isAfter(clock.instant())
    ) {
      match.status = "EXPIRED";
      match.updatedAt = clock.instant();
    }
  }

  private void requireGuestOwner(UUID user, SocialMatch match) {
    Club guest = store.get(Club.class, match.guestClubId);
    if (!guest.ownerId.equals(user)) throw ApiException.forbidden();
  }

  private void requirePartyOwner(UUID user, SocialMatch match) {
    if (!isHostOwner(user, match)) {
      Club guest = store.get(Club.class, match.guestClubId);
      if (!guest.ownerId.equals(user)) throw ApiException.forbidden();
    }
  }

  private boolean isHostOwner(UUID user, SocialMatch match) {
    return store.get(Club.class, match.hostClubId).ownerId.equals(user);
  }

  private SocialMatchView matchView(SocialMatch match, UUID user) {
    expireIfNeeded(match);
    Club host = store.get(Club.class, match.hostClubId);
    Club guest = store.get(Club.class, match.guestClubId);
    boolean outgoing = host.ownerId.equals(user);
    boolean hostConfirmed = match.hostConfirmedRevision == match.revision;
    boolean guestConfirmed = match.guestConfirmedRevision == match.revision;
    Instant readAt = outgoing ? match.hostLastReadAt : match.guestLastReadAt;
    Long unread = store
      .list(
        Long.class,
        "select count(m) from SocialMessage m where m.matchId=:match and m.senderId<>:user and m.createdAt>:readAt",
        "match",
        match.id,
        "user",
        user,
        "readAt",
        readAt == null ? Instant.EPOCH : readAt
      )
      .getFirst();
    boolean pending = match.status.equals("PENDING");
    boolean canRespond =
      pending && !outgoing && match.expiresAt.isAfter(clock.instant());
    boolean canConfirm =
      Set.of("NEGOTIATING", "CHANGE_PENDING").contains(match.status) &&
      (outgoing ? !hostConfirmed : !guestConfirmed);
    boolean canPropose = Set.of(
      "NEGOTIATING",
      "SCHEDULED",
      "CHANGE_PENDING"
    ).contains(match.status);
    boolean canCancel = !TERMINAL.contains(match.status);
    return new SocialMatchView(
      match.id,
      host.id,
      host.name,
      guest.id,
      guest.name,
      match.status,
      outgoing,
      match.acceptedAt != null,
      match.proposedStartsAt,
      match.proposedLocation,
      match.note,
      match.expiresAt,
      match.revision,
      hostConfirmed,
      guestConfirmed,
      unread.intValue(),
      match.startsAt,
      match.location,
      canRespond,
      canRespond,
      canConfirm,
      canPropose,
      canCancel
    );
  }

  private SocialMessage addMessage(SocialMatch match, UUID user, String body) {
    return store.save(new SocialMessage(match.id, user, body, clock.instant()));
  }

  private SocialMessageView messageView(SocialMessage message, UUID user) {
    Player sender = store.get(Player.class, message.senderId);
    return new SocialMessageView(
      message.id,
      message.senderId,
      sender.name,
      message.body,
      message.createdAt,
      message.senderId.equals(user)
    );
  }

  private SocialListingView listingView(
    Club club,
    SocialListing listing,
    boolean canManage
  ) {
    Municipality municipality = municipalities
      .find(listing.municipalityCode)
      .orElse(null);
    return new SocialListingView(
      club.id,
      club.name,
      listing.published,
      values(listing.categories),
      listing.municipalityCode,
      municipality == null ? "" : municipality.name(),
      municipality == null ? "" : municipality.uf(),
      listing.courtName,
      listing.neighborhood,
      listing.description,
      listing.skillLevel,
      values(listing.preferredDays),
      values(listing.preferredPeriods),
      canManage
    );
  }

  static List<String> values(String encoded) {
    if (encoded == null || encoded.isBlank()) return List.of();
    return Arrays.stream(encoded.split(","))
      .filter(value -> !value.isBlank())
      .toList();
  }

  static List<String> sorted(List<String> input) {
    if (input == null) return List.of();
    return input.stream().filter(Objects::nonNull).distinct().sorted().toList();
  }

  static void requireValues(
    List<String> values,
    Set<String> allowed,
    String message
  ) {
    if (
      values.stream().anyMatch(value -> !allowed.contains(value))
    ) throw new ApiException(400, message);
  }

  static List<String> normalizedFilter(
    List<String> input,
    Set<String> allowed,
    String message
  ) {
    List<String> values = sorted(input);
    requireValues(values, allowed, message);
    return values;
  }

  static double distanceKm(Municipality first, Municipality second) {
    double lat1 = Math.toRadians(first.latitude());
    double lat2 = Math.toRadians(second.latitude());
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(second.longitude() - first.longitude());
    double a =
      Math.pow(Math.sin(deltaLat / 2), 2) +
      Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
    return 6371d * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
}
