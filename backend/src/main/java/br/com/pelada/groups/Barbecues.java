package br.com.pelada.groups;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Barbecues {

  private static final int UPCOMING_WINDOW = 6;

  private final Store store;
  private final Groups groups;
  private final Clock clock;

  public Barbecues(Store store, Groups groups, Clock clock) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
  }

  public List<BarbecueView> list(UUID user, UUID clubId) {
    groups.requireMember(user, clubId);
    Club club = store.lock(Club.class, clubId);
    ensureWindow(club);
    return events(clubId)
      .stream()
      .map(event -> view(event, user))
      .toList();
  }

  public List<BarbecueView> startSeries(
    UUID user,
    UUID clubId,
    CreateBarbecueSeries input
  ) {
    Club club = groups.requireOwner(user, clubId);
    club = store.lock(Club.class, club.id);
    if (club.barbecueFrequency.equals("NONE")) throw new ApiException(
      400,
      "Escolha uma frequência de churrasco ao criar o grupo."
    );
    if (!input.startsAt().isAfter(clock.instant())) throw new ApiException(
      400,
      "Escolha a data e o horário da primeira edição no futuro."
    );
    if (
      store
        .first(
          BarbecueSeries.class,
          "from BarbecueSeries where clubId=:club",
          "club",
          clubId
        )
        .isPresent()
    ) throw ApiException.conflict(
      "A série de churrascos deste grupo já foi configurada."
    );

    ZoneId zone;
    try {
      zone = ZoneId.of(input.timeZone());
    } catch (DateTimeException ex) {
      throw new ApiException(400, "Escolha um fuso horário válido.");
    }
    BarbecueSeries series = store.save(
      new BarbecueSeries(
        clubId,
        club.barbecueFrequency,
        input.startsAt(),
        zone.getId(),
        input.location().strip()
      )
    );
    store.flush();
    ensureWindow(club);
    return events(clubId)
      .stream()
      .map(event -> view(event, user))
      .toList();
  }

  public BarbecueView createOneOff(
    UUID user,
    UUID clubId,
    CreateBarbecue input
  ) {
    groups.requireOwner(user, clubId);
    requireFuture(input.startsAt());
    Barbecue event = store.save(
      new Barbecue(clubId, null, 0, input.startsAt(), input.location().strip())
    );
    return view(event, user);
  }

  public List<BarbecueView> pauseSeries(UUID user, UUID clubId) {
    groups.requireOwner(user, clubId);
    Club club = store.lock(Club.class, clubId);
    BarbecueSeries series = series(clubId).orElseThrow(() ->
      ApiException.notFound()
    );
    if (!series.active) throw ApiException.conflict("A série já está pausada.");
    series.active = false;
    return events(club.id)
      .stream()
      .map(event -> view(event, user))
      .toList();
  }

  public List<BarbecueView> resumeSeries(UUID user, UUID clubId) {
    groups.requireOwner(user, clubId);
    Club club = store.lock(Club.class, clubId);
    BarbecueSeries series = series(clubId).orElseThrow(() ->
      ApiException.notFound()
    );
    if (series.active) throw ApiException.conflict("A série já está ativa.");
    series.active = true;
    ensureWindow(club);
    return events(club.id)
      .stream()
      .map(event -> view(event, user))
      .toList();
  }

  public BarbecueView update(UUID user, UUID eventId, UpdateBarbecue input) {
    Barbecue event = store.lock(Barbecue.class, eventId);
    groups.requireOwner(user, event.clubId);
    if (event.cancelled) throw ApiException.conflict(
      "Um churrasco cancelado não pode ser alterado."
    );
    requireFuture(input.startsAt());
    event.startsAt = input.startsAt();
    event.location = input.location().strip();
    return view(event, user);
  }

  public BarbecueView cancel(UUID user, UUID eventId) {
    Barbecue event = store.lock(Barbecue.class, eventId);
    groups.requireOwner(user, event.clubId);
    if (event.cancelled) throw ApiException.conflict(
      "Este churrasco já foi cancelado."
    );
    requireFuture(event.startsAt);
    event.cancelled = true;
    return view(event, user);
  }

  public BarbecueView attendance(UUID user, UUID eventId, boolean attending) {
    Barbecue event = store.lock(Barbecue.class, eventId);
    groups.requireMember(user, event.clubId);
    updateAttendance(user, event, attending);
    return view(event, user);
  }

  public BarbecueView inviteAttendance(
    UUID user,
    UUID token,
    boolean attending
  ) {
    Barbecue event = store
      .first(
        Barbecue.class,
        "from Barbecue where inviteToken=:token",
        "token",
        token
      )
      .orElseThrow(ApiException::notFound);
    event = store.lock(Barbecue.class, event.id);
    if (
      store
        .first(
          Barbecue.class,
          "from Barbecue where id=:event and inviteToken=:token",
          "event",
          event.id,
          "token",
          token
        )
        .isEmpty()
    ) throw ApiException.notFound();
    updateAttendance(user, event, attending);
    return view(event, user);
  }

  public BarbecueView inviteDetails(UUID user, UUID token) {
    Barbecue event = invitedEvent(token);
    return view(event, user);
  }

  private void updateAttendance(UUID user, Barbecue event, boolean attending) {
    if (event.cancelled) throw ApiException.conflict(
      "Este churrasco foi cancelado."
    );
    if (!event.startsAt.isAfter(clock.instant())) throw ApiException.conflict(
      "As confirmações deste churrasco já foram encerradas."
    );
    Optional<BarbecueAttendance> existing = attendance(event.id, user);
    if (attending && existing.isEmpty()) store.save(
      new BarbecueAttendance(event.id, user, clock.instant())
    );
    else if (!attending && existing.isPresent()) {
      store.remove(existing.get());
      store.flush();
    }
  }

  private void ensureWindow(Club club) {
    Optional<BarbecueSeries> found = series(club.id);
    if (found.isEmpty() || !found.get().active) return;
    BarbecueSeries series = store.lock(BarbecueSeries.class, found.get().id);
    Instant now = clock.instant();
    int upcoming = (int) store
      .list(
        Long.class,
        "select count(b) from Barbecue b where b.seriesId=:series and b.startsAt>:now and b.cancelled=false",
        "series",
        series.id,
        "now",
        now
      )
      .getFirst()
      .longValue();
    int interval = intervalMonths(series.frequency);
    ZonedDateTime anchor = ZonedDateTime.ofInstant(
      series.anchorStartsAt,
      ZoneId.of(series.timeZone)
    );
    ZonedDateTime localNow = now.atZone(anchor.getZone());
    long elapsedMonths = Math.max(
      0,
      ChronoUnit.MONTHS.between(
        anchor.withDayOfMonth(1),
        localNow.withDayOfMonth(1)
      )
    );
    int currentIndex = (int) (elapsedMonths / interval);
    series.nextOccurrenceIndex = Math.max(
      series.nextOccurrenceIndex,
      currentIndex
    );

    while (upcoming < UPCOMING_WINDOW) {
      int index = series.nextOccurrenceIndex++;
      Instant start = anchor.plusMonths((long) index * interval).toInstant();
      if (!start.isAfter(now)) continue;
      store.save(
        new Barbecue(club.id, series.id, index, start, series.location)
      );
      upcoming++;
    }
    store.flush();
  }

  private int intervalMonths(String frequency) {
    return switch (frequency) {
      case "MONTHLY" -> 1;
      case "EVERY_2_MONTHS" -> 2;
      case "EVERY_3_MONTHS" -> 3;
      default -> throw new ApiException(
        500,
        "Frequência de churrasco inválida."
      );
    };
  }

  private void requireFuture(Instant start) {
    if (!start.isAfter(clock.instant())) throw new ApiException(
      400,
      "Escolha uma data e um horário no futuro."
    );
  }

  private Optional<BarbecueSeries> series(UUID clubId) {
    return store.first(
      BarbecueSeries.class,
      "from BarbecueSeries where clubId=:club",
      "club",
      clubId
    );
  }

  private Barbecue invitedEvent(UUID token) {
    return store
      .first(
        Barbecue.class,
        "from Barbecue where inviteToken=:token",
        "token",
        token
      )
      .orElseThrow(ApiException::notFound);
  }

  private List<Barbecue> events(UUID clubId) {
    Instant now = clock.instant();
    return store
      .list(
        Barbecue.class,
        "from Barbecue where clubId=:club order by startsAt",
        "club",
        clubId
      )
      .stream()
      .sorted(
        Comparator.comparing(
          (Barbecue event) -> !event.startsAt.isAfter(now)
        ).thenComparing(event -> event.startsAt)
      )
      .toList();
  }

  private Optional<BarbecueAttendance> attendance(UUID eventId, UUID playerId) {
    return store.first(
      BarbecueAttendance.class,
      "from BarbecueAttendance where barbecueId=:event and playerId=:player",
      "event",
      eventId,
      "player",
      playerId
    );
  }

  private BarbecueView view(Barbecue event, UUID viewer) {
    Club club = store.get(Club.class, event.clubId);
    List<BarbecueAttendee> attendees = store
      .list(
        Object[].class,
        "select a,p from BarbecueAttendance a,Player p where a.playerId=p.id and a.barbecueId=:event order by a.createdAt",
        "event",
        event.id
      )
      .stream()
      .map(row -> {
        BarbecueAttendance attendance = (BarbecueAttendance) row[0];
        Player player = (Player) row[1];
        return new BarbecueAttendee(player.id, player.name);
      })
      .toList();
    boolean attending = attendance(event.id, viewer).isPresent();
    return new BarbecueView(
      event.id,
      club.id,
      club.name,
      event.startsAt,
      event.location,
      event.cancelled,
      event.seriesId != null,
      attendees.size(),
      attending,
      club.ownerId.equals(viewer) ? event.inviteToken.toString() : null,
      attendees
    );
  }
}
