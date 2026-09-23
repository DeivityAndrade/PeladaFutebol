package br.com.pelada.groups;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Finance {

  public static final int MAX_RECEIPT_BYTES = 2 * 1024 * 1024;
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
  private static final Duration RECEIPT_RETENTION = Duration.ofDays(90);

  private final Store store;
  private final Groups groups;
  private final Clock clock;

  public Finance(Store store, Groups groups, Clock clock) {
    this.store = store;
    this.groups = groups;
    this.clock = clock;
  }

  public FinanceSummary summary(
    UUID user,
    UUID clubId,
    String requestedPeriod,
    UUID requestedPlayer,
    UUID requestedGame
  ) {
    Club club = store.lock(Club.class, clubId);
    groups.requireMember(user, clubId);
    LocalDate today = today();
    ensureMonthlyCharges(club, today);
    issueElapsedNonLiveGameCharges(club);
    expireReceipts(club.id);

    boolean canManage = club.ownerId.equals(user);
    boolean canViewAll = canManage || isCaptain(user, clubId);
    if (
      !canViewAll && requestedPlayer != null && !requestedPlayer.equals(user)
    ) {
      throw ApiException.forbidden();
    }
    if (requestedGame != null) {
      Game filteredGame = store.get(Game.class, requestedGame);
      if (!filteredGame.clubId.equals(clubId)) throw ApiException.notFound();
    }

    YearMonth period = parsePeriod(requestedPeriod, today);
    List<FinanceCharge> all = store.list(
      FinanceCharge.class,
      "from FinanceCharge where clubId=:club order by dueDate desc,createdAt desc",
      "club",
      clubId
    );
    List<FinanceCharge> filtered = all
      .stream()
      .filter(charge -> canViewAll || charge.playerId.equals(user))
      .filter(
        charge ->
          requestedPlayer == null || charge.playerId.equals(requestedPlayer)
      )
      .filter(
        charge -> requestedGame == null || requestedGame.equals(charge.gameId)
      )
      .filter(charge -> chargePeriod(charge).equals(period))
      .toList();

    List<FinanceChargeView> views = filtered
      .stream()
      .map(charge -> chargeView(charge, user, canManage, canViewAll, today))
      .toList();
    long received = views
      .stream()
      .filter(charge -> charge.status().equals("PAID"))
      .mapToLong(FinanceChargeView::amountCents)
      .sum();
    long pending = views
      .stream()
      .filter(this::outstanding)
      .mapToLong(FinanceChargeView::amountCents)
      .sum();
    long overdue = views
      .stream()
      .filter(FinanceChargeView::overdue)
      .mapToLong(FinanceChargeView::amountCents)
      .sum();

    return new FinanceSummary(
      new FinanceSettingsView(
        club.monthlyAmountCents,
        club.billingDueDay,
        club.occasionalAmountCents,
        club.pixInstructions
      ),
      canManage ? members(clubId) : List.of(),
      canViewAll ? people(clubId) : List.of(),
      views,
      received,
      pending,
      overdue,
      (int) views.stream().filter(FinanceChargeView::overdue).count(),
      (int) views.stream().filter(FinanceChargeView::dueSoon).count(),
      canManage,
      canViewAll
    );
  }

  public FinanceSummary updateSettings(
    UUID user,
    UUID clubId,
    FinanceSettingsInput input
  ) {
    Club club = store.lock(Club.class, clubId);
    groups.requireOwner(user, clubId);
    LocalDate today = today();
    ensureMonthlyCharges(club, today);
    expireReceipts(club.id);

    club.monthlyAmountCents = positive(input.monthlyAmountCents());
    club.billingDueDay = input.billingDueDay();
    club.occasionalAmountCents = positive(input.occasionalAmountCents());
    club.pixInstructions = input.pixInstructions().strip();
    if (club.monthlyAmountCents != null) {
      for (Member member : store.list(
        Member.class,
        "from Member where clubId=:club and billingType='MONTHLY'",
        "club",
        clubId
      )) {
        if (activeThrough(member, YearMonth.from(today).plusMonths(1))) {
          member.monthlyAmountCents = club.monthlyAmountCents;
        }
      }
    }
    return summary(user, clubId, YearMonth.from(today).toString(), null, null);
  }

  public FinanceSummary classifyMember(
    UUID user,
    UUID clubId,
    UUID playerId,
    boolean monthly
  ) {
    Club club = store.lock(Club.class, clubId);
    groups.requireOwner(user, clubId);
    LocalDate today = today();
    YearMonth current = YearMonth.from(today);
    ensureMonthlyCharges(club, today);
    Member member = member(clubId, playerId);
    if (monthly) {
      if (club.monthlyAmountCents == null || club.monthlyAmountCents <= 0) {
        throw new ApiException(
          400,
          "Defina primeiro o valor da mensalidade do grupo."
        );
      }
      member.billingType = "MONTHLY";
      member.monthlyFrom = current.plusMonths(1).atDay(1);
      member.monthlyThrough = null;
      member.monthlyAmountCents = club.monthlyAmountCents;
    } else {
      if (
        member.monthlyFrom != null &&
        YearMonth.from(member.monthlyFrom).isAfter(current)
      ) {
        member.monthlyFrom = null;
        member.monthlyThrough = null;
      } else if (member.monthlyFrom != null) {
        member.monthlyThrough = current.atEndOfMonth();
      }
      member.billingType = "OCCASIONAL";
    }
    return summary(user, clubId, current.toString(), null, null);
  }

  public void createGameCharges(Game game, List<Participation> confirmed) {
    if (!game.chargeOccasional) return;
    if (game.occasionalAmountCents == null || game.occasionalAmountCents <= 0) {
      throw ApiException.conflict(
        "Defina um valor para a cobrança desta pelada."
      );
    }
    LocalDate gameDate = game.startsAt.atZone(BUSINESS_ZONE).toLocalDate();
    YearMonth gameMonth = YearMonth.from(gameDate);
    for (Participation participation : confirmed) {
      if (!participation.status.equals("CONFIRMED")) continue;
      Member member = store
        .first(
          Member.class,
          "from Member where clubId=:club and playerId=:player",
          "club",
          game.clubId,
          "player",
          participation.playerId
        )
        .orElseThrow(ApiException::notFound);
      if (monthlyDuring(member, gameMonth)) continue;
      boolean exists = store
        .first(
          FinanceCharge.class,
          "from FinanceCharge where gameId=:game and playerId=:player and type='GAME'",
          "game",
          game.id,
          "player",
          participation.playerId
        )
        .isPresent();
      if (!exists) store.save(
        new FinanceCharge(
          game.clubId,
          member.id,
          participation.playerId,
          game.id,
          "GAME",
          null,
          game.occasionalAmountCents,
          gameDate,
          clock.instant()
        )
      );
    }
  }

  public void cancelGameCharges(UUID gameId) {
    for (FinanceCharge charge : store.list(
      FinanceCharge.class,
      "from FinanceCharge where gameId=:game and type='GAME' and status<>'PAID'",
      "game",
      gameId
    ))
      charge.status = "CANCELLED";
  }

  private void issueElapsedNonLiveGameCharges(Club club) {
    List<UUID> elapsedGameIds = store.list(
      UUID.class,
      "select id from Game where clubId=:club and cancelled=false and liveEnabled=false and chargeOccasional=true and startsAt<=:now",
      "club",
      club.id,
      "now",
      clock.instant()
    );
    for (UUID gameId : elapsedGameIds) {
      Game game = store.lock(Game.class, gameId);
      if (
        game.cancelled ||
        game.liveEnabled ||
        !game.chargeOccasional ||
        game.startsAt.isAfter(clock.instant())
      ) continue;
      createGameCharges(
        game,
        store.list(
          Participation.class,
          "from Participation where gameId=:game and status='CONFIRMED'",
          "game",
          game.id
        )
      );
    }
  }

  public FinanceChargeView uploadReceipt(
    UUID user,
    UUID chargeId,
    String originalFilename,
    String declaredContentType,
    byte[] data
  ) {
    FinanceCharge charge = store.lock(FinanceCharge.class, chargeId);
    LocalDate today = today();
    boolean canManage = canManage(user, charge.clubId);
    boolean canViewAll = canManage || isCaptain(user, charge.clubId);
    if (
      !canViewAll && !charge.playerId.equals(user)
    ) throw ApiException.forbidden();
    if (charge.status.equals("PAID") || charge.status.equals("CANCELLED")) {
      throw ApiException.conflict(
        "Esta cobrança não aceita novos comprovantes."
      );
    }
    if (data == null || data.length == 0) throw new ApiException(
      400,
      "Escolha um comprovante."
    );
    if (data.length > MAX_RECEIPT_BYTES) throw new ApiException(
      413,
      "O comprovante deve ter no máximo 2 MB."
    );
    String contentType = detectContentType(data);
    if (contentType == null) throw new ApiException(
      400,
      "Envie uma imagem PNG, JPEG, GIF, WebP ou um arquivo PDF válido."
    );
    if (
      declaredContentType != null &&
      !declaredContentType.isBlank() &&
      !declaredContentType.equalsIgnoreCase(contentType) &&
      !declaredContentType.equalsIgnoreCase("application/octet-stream")
    ) throw new ApiException(
      400,
      "O formato do arquivo não corresponde ao conteúdo."
    );

    String filename = safeFilename(originalFilename, contentType);
    Optional<FinanceReceiptFile> existing = store.first(
      FinanceReceiptFile.class,
      "from FinanceReceiptFile where chargeId=:charge",
      "charge",
      chargeId
    );
    existing.ifPresent(store::remove);
    if (existing.isPresent()) store.flush();
    store.save(new FinanceReceiptFile(chargeId, data));
    charge.status = "AWAITING_REVIEW";
    charge.manual = false;
    charge.reviewNote = null;
    charge.reviewedAt = null;
    charge.reviewedBy = null;
    charge.paidAt = null;
    charge.paymentSubmittedAt = clock.instant();
    charge.receiptUploadedAt = clock.instant();
    charge.receiptFilename = filename;
    charge.receiptContentType = contentType;
    charge.receiptDeletedAt = null;
    return chargeView(charge, user, canManage, canViewAll, today);
  }

  public ReceiptDownload receipt(UUID user, UUID chargeId) {
    FinanceCharge charge = store.get(FinanceCharge.class, chargeId);
    boolean canManage = canManage(user, charge.clubId);
    boolean canViewAll = canManage || isCaptain(user, charge.clubId);
    if (
      !canViewAll && !charge.playerId.equals(user)
    ) throw ApiException.forbidden();
    if (
      charge.receiptUploadedAt == null ||
      charge.receiptDeletedAt != null ||
      !clock
        .instant()
        .isBefore(charge.receiptUploadedAt.plus(RECEIPT_RETENTION))
    ) {
      throw new ApiException(
        410,
        "O comprovante expirou e foi removido após 90 dias."
      );
    }
    FinanceReceiptFile file = store
      .first(
        FinanceReceiptFile.class,
        "from FinanceReceiptFile where chargeId=:charge",
        "charge",
        chargeId
      )
      .orElseThrow(() ->
        new ApiException(410, "Este comprovante não está mais disponível.")
      );
    return new ReceiptDownload(
      file.data,
      charge.receiptContentType,
      charge.receiptFilename
    );
  }

  public FinanceChargeView review(
    UUID user,
    UUID chargeId,
    boolean approve,
    FinanceReviewInput input
  ) {
    FinanceCharge charge = store.lock(FinanceCharge.class, chargeId);
    groups.requireOwner(user, charge.clubId);
    if (!charge.status.equals("AWAITING_REVIEW")) throw ApiException.conflict(
      "Este comprovante não está aguardando conferência."
    );
    if (
      approve &&
      (charge.receiptUploadedAt == null ||
        !clock
          .instant()
          .isBefore(charge.receiptUploadedAt.plus(RECEIPT_RETENTION)) ||
        store
          .first(
            FinanceReceiptFile.class,
            "from FinanceReceiptFile where chargeId=:charge",
            "charge",
            chargeId
          )
          .isEmpty())
    ) throw ApiException.conflict(
      "O arquivo expirou ou foi removido. Peça um novo comprovante antes de aprovar."
    );
    charge.status = approve ? "PAID" : "REJECTED";
    charge.reviewNote =
      approve || input == null || input.note() == null
        ? null
        : input.note().strip();
    if (
      charge.reviewNote != null && charge.reviewNote.isBlank()
    ) charge.reviewNote = null;
    charge.reviewedAt = clock.instant();
    charge.reviewedBy = user;
    if (approve) charge.paidAt = clock.instant();
    return chargeView(charge, user, true, true, today());
  }

  public FinanceChargeView markCash(UUID user, UUID chargeId) {
    FinanceCharge charge = store.lock(FinanceCharge.class, chargeId);
    groups.requireOwner(user, charge.clubId);
    if (charge.status.equals("PAID") || charge.status.equals("CANCELLED")) {
      throw ApiException.conflict("Esta cobrança já foi paga ou cancelada.");
    }
    charge.status = "PAID";
    charge.manual = true;
    charge.paidAt = clock.instant();
    charge.paymentSubmittedAt =
      charge.paymentSubmittedAt == null
        ? clock.instant()
        : charge.paymentSubmittedAt;
    charge.reviewedAt = clock.instant();
    charge.reviewedBy = user;
    charge.reviewNote = "Pagamento em dinheiro registrado pelo organizador.";
    return chargeView(charge, user, true, true, today());
  }

  private void ensureMonthlyCharges(Club club, LocalDate today) {
    YearMonth current = YearMonth.from(today);
    List<FinanceCharge> existing = store.list(
      FinanceCharge.class,
      "from FinanceCharge where clubId=:club and type='MONTHLY'",
      "club",
      club.id
    );
    Set<String> unique = existing
      .stream()
      .map(charge -> charge.playerId + ":" + charge.period)
      .collect(Collectors.toSet());
    for (Member member : store.list(
      Member.class,
      "from Member where clubId=:club and monthlyFrom is not null",
      "club",
      club.id
    )) {
      YearMonth start = YearMonth.from(member.monthlyFrom);
      YearMonth through =
        member.monthlyThrough == null
          ? current
          : YearMonth.from(member.monthlyThrough);
      if (through.isAfter(current)) through = current;
      for (
        YearMonth month = start;
        !month.isAfter(through);
        month = month.plusMonths(1)
      ) {
        String key = member.playerId + ":" + month;
        if (unique.contains(key)) continue;
        Long amount =
          member.monthlyAmountCents != null
            ? member.monthlyAmountCents
            : club.monthlyAmountCents;
        if (amount == null || amount <= 0) continue;
        LocalDate due = month.atDay(
          Math.min(club.billingDueDay, month.lengthOfMonth())
        );
        store.save(
          new FinanceCharge(
            club.id,
            member.id,
            member.playerId,
            null,
            "MONTHLY",
            month.toString(),
            amount,
            due,
            clock.instant()
          )
        );
        unique.add(key);
      }
    }
  }

  private List<FinanceMemberView> members(UUID clubId) {
    return store
      .list(
        Object[].class,
        "select m,p from Member m,Player p where m.playerId=p.id and m.clubId=:club",
        "club",
        clubId
      )
      .stream()
      .map(row -> {
        Member member = (Member) row[0];
        Player player = (Player) row[1];
        return new FinanceMemberView(
          player.id,
          player.name,
          member.billingType,
          member.monthlyFrom,
          member.monthlyThrough,
          member.monthlyAmountCents
        );
      })
      .sorted(
        Comparator.comparing(
          FinanceMemberView::playerName,
          String.CASE_INSENSITIVE_ORDER
        )
      )
      .toList();
  }

  private List<Person> people(UUID clubId) {
    return store
      .list(
        Object[].class,
        "select m,p from Member m,Player p where m.playerId=p.id and m.clubId=:club",
        "club",
        clubId
      )
      .stream()
      .map(row -> {
        Player player = (Player) row[1];
        return new Person(player.id, player.name);
      })
      .sorted(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER))
      .toList();
  }

  private FinanceChargeView chargeView(
    FinanceCharge charge,
    UUID user,
    boolean canManage,
    boolean canViewAll,
    LocalDate today
  ) {
    Player player = store.get(Player.class, charge.playerId);
    Game game =
      charge.gameId == null ? null : store.get(Game.class, charge.gameId);
    boolean expired =
      charge.receiptUploadedAt != null &&
      !clock
        .instant()
        .isBefore(charge.receiptUploadedAt.plus(RECEIPT_RETENTION));
    boolean hasReceipt =
      charge.receiptUploadedAt != null &&
      charge.receiptDeletedAt == null &&
      !expired;
    boolean overdue = outstanding(charge) && charge.dueDate.isBefore(today);
    boolean dueSoon =
      outstanding(charge) &&
      !charge.dueDate.isBefore(today) &&
      !charge.dueDate.isAfter(today.plusDays(5));
    boolean canUpload =
      charge.playerId.equals(user) &&
      !charge.status.equals("PAID") &&
      !charge.status.equals("CANCELLED");
    return new FinanceChargeView(
      charge.id,
      charge.playerId,
      player.name,
      charge.type,
      charge.period,
      charge.gameId,
      game == null ? null : game.title,
      game == null ? null : game.startsAt,
      charge.amountCents,
      charge.dueDate,
      charge.status,
      overdue,
      dueSoon,
      charge.manual,
      charge.reviewNote,
      hasReceipt,
      charge.receiptFilename,
      charge.receiptContentType,
      charge.receiptUploadedAt,
      charge.receiptUploadedAt == null
        ? null
        : charge.receiptUploadedAt.plus(RECEIPT_RETENTION),
      canUpload,
      canManage && charge.status.equals("AWAITING_REVIEW"),
      canViewAll || charge.playerId.equals(user)
    );
  }

  private void expireReceipts(UUID clubId) {
    Instant cutoff = clock.instant().minus(RECEIPT_RETENTION);
    List<UUID> expiredIds = store.list(
      UUID.class,
      "select id from FinanceCharge where clubId=:club and receiptUploadedAt is not null and receiptDeletedAt is null and receiptUploadedAt<=:cutoff",
      "club",
      clubId,
      "cutoff",
      cutoff
    );
    for (UUID id : expiredIds) {
      FinanceCharge charge = store.lock(FinanceCharge.class, id);
      if (
        charge.receiptUploadedAt == null ||
        charge.receiptDeletedAt != null ||
        charge.receiptUploadedAt.isAfter(cutoff)
      ) continue;
      store
        .first(
          FinanceReceiptFile.class,
          "from FinanceReceiptFile where chargeId=:charge",
          "charge",
          charge.id
        )
        .ifPresent(store::remove);
      charge.receiptDeletedAt = clock.instant();
    }
  }

  private boolean canManage(UUID user, UUID clubId) {
    return store.get(Club.class, clubId).ownerId.equals(user);
  }

  private boolean isCaptain(UUID user, UUID clubId) {
    return store
      .first(
        Team.class,
        "select t from Team t,Game g where t.gameId=g.id and g.clubId=:club and t.captainId=:player",
        "club",
        clubId,
        "player",
        user
      )
      .isPresent();
  }

  private Member member(UUID clubId, UUID playerId) {
    return store
      .first(
        Member.class,
        "from Member where clubId=:club and playerId=:player",
        "club",
        clubId,
        "player",
        playerId
      )
      .orElseThrow(ApiException::notFound);
  }

  private boolean monthlyDuring(Member member, YearMonth month) {
    if (member.monthlyFrom == null) return false;
    YearMonth from = YearMonth.from(member.monthlyFrom);
    YearMonth through =
      member.monthlyThrough == null
        ? null
        : YearMonth.from(member.monthlyThrough);
    return (
      !month.isBefore(from) && (through == null || !month.isAfter(through))
    );
  }

  private boolean activeThrough(Member member, YearMonth month) {
    return (
      member.monthlyFrom != null &&
      !YearMonth.from(member.monthlyFrom).isAfter(month) &&
      (member.monthlyThrough == null ||
        !YearMonth.from(member.monthlyThrough).isBefore(month))
    );
  }

  private boolean outstanding(FinanceChargeView charge) {
    return (
      !charge.status().equals("PAID") && !charge.status().equals("CANCELLED")
    );
  }

  private boolean outstanding(FinanceCharge charge) {
    return !charge.status.equals("PAID") && !charge.status.equals("CANCELLED");
  }

  private YearMonth chargePeriod(FinanceCharge charge) {
    return charge.type.equals("MONTHLY")
      ? YearMonth.parse(charge.period)
      : YearMonth.from(charge.dueDate);
  }

  private YearMonth parsePeriod(String value, LocalDate today) {
    if (value == null || value.isBlank()) return YearMonth.from(today);
    try {
      return YearMonth.parse(value);
    } catch (DateTimeException ex) {
      throw new ApiException(
        400,
        "Escolha um mês válido para consultar o financeiro."
      );
    }
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(BUSINESS_ZONE));
  }

  private Long positive(Long amount) {
    return amount == null || amount <= 0 ? null : amount;
  }

  private String detectContentType(byte[] data) {
    if (
      data.length >= 8 &&
      data[0] == (byte) 0x89 &&
      data[1] == 0x50 &&
      data[2] == 0x4e &&
      data[3] == 0x47 &&
      data[4] == 0x0d &&
      data[5] == 0x0a &&
      data[6] == 0x1a &&
      data[7] == 0x0a
    ) return "image/png";
    if (
      data.length >= 3 &&
      data[0] == (byte) 0xff &&
      data[1] == (byte) 0xd8 &&
      data[2] == (byte) 0xff
    ) return "image/jpeg";
    if (
      data.length >= 12 &&
      new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") &&
      new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
    ) return "image/webp";
    if (
      data.length >= 6 &&
      (new String(data, 0, 6, StandardCharsets.US_ASCII).equals("GIF87a") ||
        new String(data, 0, 6, StandardCharsets.US_ASCII).equals("GIF89a"))
    ) return "image/gif";
    if (
      data.length >= 5 &&
      new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
    ) return "application/pdf";
    return null;
  }

  private String safeFilename(String filename, String contentType) {
    String candidate = filename == null ? "" : filename.replace('\\', '/');
    candidate = candidate
      .substring(candidate.lastIndexOf('/') + 1)
      .replaceAll("[\\p{Cntrl}]", "")
      .strip();
    if (candidate.isBlank()) candidate = "comprovante" + extension(contentType);
    return candidate.length() <= 180
      ? candidate
      : candidate.substring(candidate.length() - 180);
  }

  private String extension(String contentType) {
    return switch (contentType) {
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      case "image/webp" -> ".webp";
      case "image/gif" -> ".gif";
      default -> ".pdf";
    };
  }

  public record ReceiptDownload(
    byte[] data,
    String contentType,
    String filename
  ) {}
}
