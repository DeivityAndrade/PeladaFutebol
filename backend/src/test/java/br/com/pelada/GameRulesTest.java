package br.com.pelada;

import static org.assertj.core.api.Assertions.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.*;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Barbecues;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class GameRulesTest {

  @Autowired
  Games games;

  @Autowired
  Matches matches;

  @Autowired
  Groups groups;

  @Autowired
  Barbecues barbecues;

  @Autowired
  Finance finance;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  List<UUID> players;
  UUID owner, club, game;

  @BeforeEach
  void setup() {
    jdbc.execute(
      "TRUNCATE spring_session,password_reset_tokens,password_reset_limits,finance_receipt_files,finance_charges,barbecue_attendance,barbecues,barbecue_series,participations,teams,game_series,games,members,clubs,players CASCADE"
    );
    players = tx.execute(status -> {
      List<UUID> result = new ArrayList<>();
      for (int i = 0; i < 15; i++) result.add(
        store
          .save(
            new Player("Jogador " + i, "p" + i + "@test.invalid", "!disabled")
          )
          .id
      );
      return result;
    });
    owner = players.getFirst();
    ClubView c = groups.create(
      owner,
      new CreateClub("Grupo de teste", "Futebol entre amigos")
    );
    club = c.id();
    for (UUID player : players) groups.join(player, c.invite());
    game = games
      .create(
        owner,
        club,
        new CreateGame(
          "Pelada teste",
          "Quadra 1",
          Instant.now().plusSeconds(86400),
          2,
          5
        )
      )
      .game()
      .id();
  }

  void confirmed(int count) {
    for (int i = 0; i < count; i++) games.attend(players.get(i), game);
  }

  List<TeamView> captains() {
    var teams = games.get(owner, game).teams();
    games.configure(
      owner,
      game,
      teams.get(0).id(),
      new UpdateTeam("Verde", "#d8f36a", owner)
    );
    games.configure(
      owner,
      game,
      teams.get(1).id(),
      new UpdateTeam("Roxo", "#a69aff", players.get(1))
    );
    return games.get(owner, game).teams();
  }

  @Test
  void capacityAndFifoPromotionRemainCorrect() {
    confirmed(12);
    var full = games.get(owner, game);
    assertThat(full.game().confirmed()).isEqualTo(10);
    assertThat(full.game().waiting()).isEqualTo(2);
    games.leave(players.get(2), game);
    var next = games.get(owner, game);
    assertThat(
      next
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(10)))
        .findFirst()
        .orElseThrow()
        .status()
    ).isEqualTo("CONFIRMED");
    assertThat(
      next
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(11)))
        .findFirst()
        .orElseThrow()
        .status()
    ).isEqualTo("WAITING");
    assertThat(next.game().confirmed()).isEqualTo(10);
  }

  @Test
  void simultaneousLastSpotDoesNotOverbook() throws Exception {
    confirmed(9);
    race(i -> games.attend(players.get(9 + i), game));
    var result = games.get(owner, game);
    assertThat(result.game().confirmed()).isEqualTo(10);
    assertThat(result.game().waiting()).isEqualTo(1);
  }

  @Test
  void repeatedConfirmationAndWithdrawalAreIdempotent() {
    games.attend(owner, game);
    games.attend(owner, game);
    assertThat(games.get(owner, game).game().confirmed()).isEqualTo(1);
    games.leave(owner, game);
    games.leave(owner, game);
    assertThat(games.get(owner, game).attendees()).isEmpty();
  }

  @Test
  void weeklySeriesUsesGroupTimeZoneAndGeneratesOnlyUniqueUpcomingOccurrences() {
    ZoneId zone = ZoneId.of("America/Manaus");
    ClubView localGroup = groups.create(
      owner,
      new CreateClub(
        "Grupo no Amazonas",
        "",
        "NONE",
        null,
        1,
        null,
        "",
        zone.getId()
      )
    );
    ZonedDateTime first = LocalDate.now(zone)
      .plusDays(8)
      .atTime(19, 30)
      .atZone(zone);
    var created = games.create(
      owner,
      localGroup.id(),
      new CreateGame(
        "Pelada toda semana",
        "Quadra do grupo",
        first.toInstant(),
        2,
        5,
        false,
        null,
        true,
        first.toLocalDate().plusWeeks(2)
      )
    );

    assertThat(created.game().recurring()).isTrue();
    assertThat(created.game().occurrenceIndex()).isEqualTo(1);
    assertThat(created.club().timeZone()).isEqualTo(zone.getId());
    List<GameView> occurrences = games.list(owner, localGroup.id());
    assertThat(occurrences).hasSize(3);
    for (int index = 1; index <= 3; index++) {
      int occurrenceIndex = index;
      GameView occurrence = occurrences
        .stream()
        .filter(item -> item.occurrenceIndex() == occurrenceIndex)
        .findFirst()
        .orElseThrow();
      ZonedDateTime local = occurrence.startsAt().atZone(zone);
      assertThat(local.toLocalDate()).isEqualTo(
        first.toLocalDate().plusWeeks(index - 1)
      );
      assertThat(local.toLocalTime()).isEqualTo(first.toLocalTime());
      assertThat(
        store.list(
          Team.class,
          "from Team where gameId=:game",
          "game",
          occurrence.id()
        )
      ).hasSize(2);
    }

    UUID firstId = occurrences
      .stream()
      .filter(item -> item.occurrenceIndex() == 1)
      .findFirst()
      .orElseThrow()
      .id();
    UUID secondId = occurrences
      .stream()
      .filter(item -> item.occurrenceIndex() == 2)
      .findFirst()
      .orElseThrow()
      .id();
    games.attend(owner, firstId);
    assertThat(games.get(owner, firstId).game().confirmed()).isEqualTo(1);
    assertThat(games.get(owner, secondId).game().confirmed()).isZero();
    games.list(owner, localGroup.id());
    assertThat(games.list(owner, localGroup.id())).hasSize(3);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from games where series_id is not null",
        Integer.class
      )
    ).isEqualTo(3);
  }

  @Test
  void editingAndCancellingRecurringGamesPreservesExceptionsAndPastData() {
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    ZonedDateTime first = LocalDate.now(zone)
      .plusDays(8)
      .atTime(20, 0)
      .atZone(zone);
    games
      .create(
        owner,
        club,
        new CreateGame(
          "Série original",
          "Quadra A",
          first.toInstant(),
          2,
          5,
          false,
          null,
          true,
          null
        )
      )
      .game()
      .id();
    List<GameView> initial = games.list(owner, club);
    Map<Integer, GameView> before = new HashMap<>();
    initial
      .stream()
      .filter(GameView::recurring)
      .forEach(item -> before.put(item.occurrenceIndex(), item));
    GameView firstOccurrence = before.get(1);
    games.attend(owner, firstOccurrence.id());
    Instant historicalStart = Instant.now().minusSeconds(86_400);
    tx.executeWithoutResult(status -> {
      Game historical = store.lock(Game.class, firstOccurrence.id());
      historical.startsAt = historicalStart;
      historical.matchStartedAt = historicalStart.minusSeconds(3600);
      historical.matchEndedAt = historicalStart.minusSeconds(1800);
      historical.matchDurationSeconds = 1800;
      historical.seriesException = true;
    });
    initial = games.list(owner, club);
    before.clear();
    initial
      .stream()
      .filter(GameView::recurring)
      .forEach(item -> before.put(item.occurrenceIndex(), item));
    assertThat(
      initial
        .stream()
        .filter(GameView::recurring)
        .filter(item -> item.startsAt().isAfter(Instant.now()))
    ).hasSize(8);

    GameView oneOff = before.get(4);
    Instant exceptionTime = oneOff.startsAt().plusSeconds(3600);
    games.update(
      owner,
      oneOff.id(),
      new UpdateGame("Exceção da série", "Quadra B", exceptionTime, "ONE")
    );

    GameView target = before.get(2);
    Instant movedStart = target.startsAt().plusSeconds(86_400);
    games.update(
      owner,
      target.id(),
      new UpdateGame("Novo horário", "Quadra C", movedStart, "THIS_AND_FUTURE")
    );
    GameDetail changedTarget = games.get(owner, target.id());
    assertThat(changedTarget.game().title()).isEqualTo("Novo horário");
    assertThat(changedTarget.game().startsAt()).isEqualTo(movedStart);
    assertThat(changedTarget.game().seriesException()).isFalse();
    assertThat(
      games.get(owner, before.get(1).id()).game().startsAt()
    ).isEqualTo(before.get(1).startsAt());
    assertThat(
      games.get(owner, before.get(1).id()).game().matchStatus()
    ).isEqualTo("FINISHED");
    assertThat(games.get(owner, before.get(1).id()).attendees()).hasSize(1);
    assertThat(
      games.get(owner, before.get(3).id()).game().startsAt()
    ).isEqualTo(movedStart.plusSeconds(7 * 86_400));
    GameDetail preservedException = games.get(owner, oneOff.id());
    assertThat(preservedException.game().startsAt()).isEqualTo(exceptionTime);
    assertThat(preservedException.game().title()).isEqualTo("Exceção da série");
    assertThat(preservedException.game().seriesException()).isTrue();

    assertThatThrownBy(() ->
      games.update(
        players.get(1),
        target.id(),
        new UpdateGame("Sem permissão", "Quadra", movedStart, "ONE")
      )
    ).isInstanceOf(ApiException.class);

    games.cancel(owner, before.get(3).id(), "ONE");
    assertThat(
      games.get(owner, before.get(3).id()).game().cancelled()
    ).isTrue();
    assertThat(
      games.get(owner, before.get(1).id()).game().cancelled()
    ).isFalse();
    int occurrencesBeforeStop = jdbc.queryForObject(
      "select count(*) from games where series_id is not null",
      Integer.class
    );
    games.cancel(owner, target.id(), "THIS_AND_FUTURE");
    assertThat(games.get(owner, target.id()).game().cancelled()).isTrue();
    assertThat(
      games.get(owner, before.get(1).id()).game().cancelled()
    ).isFalse();
    assertThatThrownBy(() ->
      games.cancel(players.get(1), oneOff.id(), "ONE")
    ).isInstanceOf(ApiException.class);
    games.list(owner, club);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from games where series_id = (select id from game_series where club_id = ?)",
        Integer.class,
        club
      )
    ).isEqualTo(occurrencesBeforeStop);
    assertThat(
      store
        .first(
          GameSeries.class,
          "from GameSeries where clubId=:club",
          "club",
          club
        )
        .orElseThrow()
        .active
    ).isFalse();
  }

  @Test
  void monthlyPlansStartNextCycleAndInvoicesAreGeneratedOnlyOnce() {
    YearMonth current = YearMonth.from(
      LocalDate.now(Clock.systemUTC().withZone(ZoneId.of("America/Sao_Paulo")))
    );
    finance.updateSettings(
      owner,
      club,
      new FinanceSettingsInput(5000L, 31, 1800L, "pix@exemplo.com")
    );
    FinanceSummary assigned = finance.classifyMember(
      owner,
      club,
      players.get(1),
      true
    );
    FinanceMemberView member = assigned
      .members()
      .stream()
      .filter(item -> item.playerId().equals(players.get(1)))
      .findFirst()
      .orElseThrow();
    assertThat(member.monthlyFrom()).isEqualTo(current.plusMonths(1).atDay(1));
    assertThat(
      finance
        .summary(owner, club, current.toString(), null, null)
        .charges()
        .stream()
        .noneMatch(
          c -> c.type().equals("MONTHLY") && c.playerId().equals(players.get(1))
        )
    ).isTrue();

    jdbc.update(
      "update members set monthly_from=?,monthly_through=null where club_id=? and player_id=?",
      java.sql.Date.valueOf(current.atDay(1)),
      club,
      players.get(1)
    );
    FinanceSummary generated = finance.summary(
      owner,
      club,
      current.toString(),
      null,
      null
    );
    List<FinanceChargeView> invoices = generated
      .charges()
      .stream()
      .filter(
        c -> c.type().equals("MONTHLY") && c.playerId().equals(players.get(1))
      )
      .toList();
    assertThat(invoices).hasSize(1);
    assertThat(invoices.getFirst().amountCents()).isEqualTo(5000L);
    assertThat(invoices.getFirst().dueDate()).isEqualTo(current.atEndOfMonth());
    assertThat(
      finance
        .summary(owner, club, current.toString(), null, null)
        .charges()
        .stream()
        .filter(
          c -> c.type().equals("MONTHLY") && c.playerId().equals(players.get(1))
        )
    ).hasSize(1);
  }

  @Test
  void gameStartChargesConfirmedOccasionalPlayersOnceAndLeavesMonthlyAndWaitingOut() {
    YearMonth current = YearMonth.from(
      LocalDate.now(Clock.systemUTC().withZone(ZoneId.of("America/Sao_Paulo")))
    );
    confirmed(12);
    var teams = captains();
    games.draw(owner, game);
    tx.executeWithoutResult(status -> {
      Game scheduled = store.get(Game.class, game);
      scheduled.startsAt = Instant.now().minusSeconds(60);
      scheduled.chargeOccasional = true;
      scheduled.occasionalAmountCents = 1800L;
      Member monthly = store
        .first(
          Member.class,
          "from Member where clubId=:club and playerId=:player",
          "club",
          club,
          "player",
          players.get(1)
        )
        .orElseThrow();
      monthly.billingType = "MONTHLY";
      monthly.monthlyFrom = current.atDay(1);
      monthly.monthlyAmountCents = 5000L;
    });

    matches.start(owner, game);
    List<FinanceChargeView> gameCharges = finance
      .summary(owner, club, current.toString(), null, game)
      .charges()
      .stream()
      .filter(charge -> charge.type().equals("GAME"))
      .toList();
    assertThat(gameCharges).hasSize(9);
    assertThat(gameCharges).allMatch(charge -> charge.amountCents() == 1800L);
    assertThat(gameCharges).noneMatch(charge ->
      charge.playerId().equals(players.get(1))
    );
    assertThat(gameCharges).noneMatch(
      charge ->
        charge.playerId().equals(players.get(10)) ||
        charge.playerId().equals(players.get(11))
    );
    assertThatThrownBy(() -> matches.start(owner, game)).isInstanceOf(
      ApiException.class
    );
    assertThat(
      finance
        .summary(owner, club, current.toString(), null, game)
        .charges()
        .stream()
        .filter(charge -> charge.type().equals("GAME"))
    ).hasSize(9);
  }

  @Test
  void cancellingPeladaCancelsItsOutstandingChargesAndKeepsRecordedPayments() {
    LocalDate gameDate = tx.execute(status ->
      store
        .get(Game.class, game)
        .startsAt.atZone(ZoneId.of("America/Sao_Paulo"))
        .toLocalDate()
    );
    FinanceCharge pending = tx.execute(status ->
      store.save(
        new FinanceCharge(
          club,
          store
            .first(
              Member.class,
              "from Member where clubId=:club and playerId=:player",
              "club",
              club,
              "player",
              players.get(1)
            )
            .orElseThrow()
            .id,
          players.get(1),
          game,
          "GAME",
          null,
          1800L,
          gameDate,
          Instant.now()
        )
      )
    );
    FinanceCharge paid = tx.execute(status ->
      store.save(
        new FinanceCharge(
          club,
          store
            .first(
              Member.class,
              "from Member where clubId=:club and playerId=:player",
              "club",
              club,
              "player",
              players.get(2)
            )
            .orElseThrow()
            .id,
          players.get(2),
          game,
          "GAME",
          null,
          1800L,
          gameDate,
          Instant.now()
        )
      )
    );
    finance.markCash(owner, paid.id);

    games.cancel(owner, game);

    YearMonth gamePeriod = YearMonth.from(gameDate);
    List<FinanceChargeView> charges = finance
      .summary(owner, club, gamePeriod.toString(), null, game)
      .charges();
    assertThat(charges)
      .filteredOn(charge -> charge.id().equals(pending.id))
      .singleElement()
      .extracting(FinanceChargeView::status)
      .isEqualTo("CANCELLED");
    assertThat(charges)
      .filteredOn(charge -> charge.id().equals(paid.id))
      .singleElement()
      .extracting(FinanceChargeView::status)
      .isEqualTo("PAID");
  }

  @Test
  void proofReviewPrivacyCashPaymentsAndNinetyDayExpiryAreEnforced() {
    LocalDate today = LocalDate.now(
      Clock.systemUTC().withZone(ZoneId.of("America/Sao_Paulo"))
    );
    confirmed(2);
    captains();
    FinanceCharge proofCharge = tx.execute(status ->
      store.save(
        new FinanceCharge(
          club,
          store
            .first(
              Member.class,
              "from Member where clubId=:club and playerId=:player",
              "club",
              club,
              "player",
              players.get(2)
            )
            .orElseThrow()
            .id,
          players.get(2),
          null,
          "MONTHLY",
          YearMonth.from(today).toString(),
          2400L,
          today,
          Instant.now()
        )
      )
    );
    byte[] pngSignature = new byte[] {
      (byte) 0x89,
      0x50,
      0x4e,
      0x47,
      0x0d,
      0x0a,
      0x1a,
      0x0a,
    };
    assertThatThrownBy(() ->
      finance.uploadReceipt(
        players.get(2),
        proofCharge.id,
        "grande.png",
        "image/png",
        new byte[Finance.MAX_RECEIPT_BYTES + 1]
      )
    )
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 413);
    assertThatThrownBy(() ->
      finance.uploadReceipt(
        players.get(2),
        proofCharge.id,
        "arquivo.txt",
        "text/plain",
        new byte[] { 1, 2, 3 }
      )
    )
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 400);
    FinanceChargeView uploaded = finance.uploadReceipt(
      players.get(2),
      proofCharge.id,
      "..\\comprovante.png",
      "image/png",
      pngSignature
    );
    assertThat(uploaded.status()).isEqualTo("AWAITING_REVIEW");
    assertThat(finance.receipt(owner, proofCharge.id).data()).containsExactly(
      pngSignature
    );
    assertThatThrownBy(() -> finance.receipt(players.get(3), proofCharge.id))
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 403);
    FinanceSummary captainSummary = finance.summary(
      players.get(1),
      club,
      YearMonth.from(today).toString(),
      null,
      null
    );
    assertThat(captainSummary.canViewAll()).isTrue();
    assertThat(captainSummary.canManage()).isFalse();
    assertThat(captainSummary.charges()).anyMatch(c ->
      c.id().equals(proofCharge.id)
    );
    assertThatThrownBy(() ->
      finance.updateSettings(
        players.get(1),
        club,
        new FinanceSettingsInput(4000L, 5, 1500L, "")
      )
    ).isInstanceOf(ApiException.class);

    assertThat(
      finance.review(owner, proofCharge.id, true, null).status()
    ).isEqualTo("PAID");
    FinanceCharge cashCharge = tx.execute(status ->
      store.save(
        new FinanceCharge(
          club,
          store
            .first(
              Member.class,
              "from Member where clubId=:club and playerId=:player",
              "club",
              club,
              "player",
              players.get(4)
            )
            .orElseThrow()
            .id,
          players.get(4),
          game,
          "GAME",
          null,
          1200L,
          today,
          Instant.now()
        )
      )
    );
    FinanceChargeView cashPayment = finance.markCash(owner, cashCharge.id);
    assertThat(cashPayment.status()).isEqualTo("PAID");
    assertThat(cashPayment.manual()).isTrue();

    FinanceCharge expiredCharge = tx.execute(status -> {
      Member member = store
        .first(
          Member.class,
          "from Member where clubId=:club and playerId=:player",
          "club",
          club,
          "player",
          players.get(5)
        )
        .orElseThrow();
      FinanceCharge charge = store.save(
        new FinanceCharge(
          club,
          member.id,
          players.get(5),
          null,
          "MONTHLY",
          YearMonth.from(today).toString(),
          1200L,
          today.minusDays(30),
          Instant.now()
        )
      );
      charge.status = "AWAITING_REVIEW";
      charge.receiptUploadedAt = Instant.now().minus(Duration.ofDays(91));
      charge.receiptFilename = "antigo.png";
      charge.receiptContentType = "image/png";
      store.save(new FinanceReceiptFile(charge.id, pngSignature));
      return charge;
    });
    FinanceSummary afterExpiry = finance.summary(
      owner,
      club,
      YearMonth.from(today).toString(),
      null,
      null
    );
    assertThat(afterExpiry.charges()).anyMatch(
      c -> c.id().equals(expiredCharge.id) && !c.receiptAvailable()
    );
    assertThat(
      store.first(
        FinanceReceiptFile.class,
        "from FinanceReceiptFile where chargeId=:charge",
        "charge",
        expiredCharge.id
      )
    ).isEmpty();
    assertThatThrownBy(() -> finance.receipt(owner, expiredCharge.id))
      .isInstanceOf(ApiException.class)
      .hasFieldOrPropertyWithValue("status", 410);
  }

  @Test
  void simultaneousCaptainPicksHaveOnlyOneWinner() throws Exception {
    confirmed(3);
    var teams = captains();
    var results = race(i ->
      games.pick(players.get(i), game, teams.get(i).id(), players.get(2))
    );
    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(
      results.stream().filter(Objects::nonNull).findFirst().orElseThrow()
    ).isInstanceOf(ApiException.class);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(2)))
        .findFirst()
        .orElseThrow()
        .teamId()
    ).isNotNull();
  }

  @Test
  void lineupSupportsSwapsAllFormationsAndEmptySlots() {
    confirmed(4);
    UUID team = captains().getFirst().id();
    games.pick(owner, game, team, players.get(2));
    for (String formation : List.of("2-2", "1-2-1", "3-1")) {
      long version = games.get(owner, game).teams().getFirst().version();
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          formation,
          Arrays.asList(owner, players.get(2), null, null, null),
          version
        )
      );
      version = games.get(owner, game).teams().getFirst().version();
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          formation,
          Arrays.asList(players.get(2), owner, null, null, null),
          version
        )
      );
      var result = games.get(owner, game);
      assertThat(result.teams().getFirst().formation()).isEqualTo(formation);
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> p.id().equals(owner))
          .findFirst()
          .orElseThrow()
          .slot()
      ).isEqualTo(1);
    }
  }

  @Test
  void duplicateForeignAndStaleLineupsAreRejected() {
    confirmed(3);
    UUID team = captains().getFirst().id();
    long version = games.get(owner, game).teams().getFirst().version();
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          "2-2",
          Arrays.asList(owner, owner, null, null, null),
          version
        )
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup(
          "2-2",
          Arrays.asList(players.get(1), null, null, null, null),
          version
        )
      )
    ).isInstanceOf(ApiException.class);
    games.lineup(
      owner,
      game,
      team,
      new Lineup("2-2", Arrays.asList(owner, null, null, null, null), version)
    );
    assertThatThrownBy(() ->
      games.lineup(
        owner,
        game,
        team,
        new Lineup("2-2", Arrays.asList(owner, null, null, null, null), version)
      )
    )
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("mudou");
  }

  @Test
  void participantCannotManageAndCaptainCannotEditOtherTeam() {
    confirmed(3);
    var teams = captains();
    assertThatThrownBy(() -> games.draw(players.get(2), game)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() ->
      games.configure(
        players.get(2),
        game,
        teams.getFirst().id(),
        new UpdateTeam("Outro", "#ffffff", null)
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      games.pick(owner, game, teams.get(1).id(), players.get(2))
    ).isInstanceOf(ApiException.class);
    UUID outsider = tx.execute(
      s -> store.save(new Player("Fora", "fora@test.invalid", "!disabled")).id
    );
    assertThatThrownBy(() -> games.get(outsider, game)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() -> games.attend(outsider, game)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void captainDepartureClearsRosterAndAllowsReplacement() {
    confirmed(11);
    UUID team = captains().getFirst().id();
    games.lineup(
      owner,
      game,
      team,
      new Lineup(
        "2-2",
        Arrays.asList(owner, null, null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );
    games.leave(owner, game);
    var result = games.get(owner, game);
    assertThat(result.teams().getFirst().captainId()).isNull();
    assertThat(result.attendees()).noneMatch(p -> p.id().equals(owner));
    games.configure(
      owner,
      game,
      team,
      new UpdateTeam("Verde", "#d8f36a", players.get(2))
    );
    assertThat(games.get(owner, game).teams().getFirst().captainId()).isEqualTo(
      players.get(2)
    );
  }

  @Test
  void releaseClearsPositionAndDoesNotCancelAttendance() {
    confirmed(3);
    UUID team = captains().getFirst().id();
    games.pick(owner, game, team, players.get(2));
    games.lineup(
      owner,
      game,
      team,
      new Lineup(
        "2-2",
        Arrays.asList(owner, players.get(2), null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );
    games.release(owner, game, team, players.get(2));
    var p = games
      .get(owner, game)
      .attendees()
      .stream()
      .filter(a -> a.id().equals(players.get(2)))
      .findFirst()
      .orElseThrow();
    assertThat(p.teamId()).isNull();
    assertThat(p.slot()).isNull();
    assertThat(p.status()).isEqualTo("CONFIRMED");
  }

  @Test
  void fullRosterRejectsAnotherPick() {
    confirmed(7);
    UUID team = captains().getFirst().id();
    for (int i = 2; i < 6; i++) games.pick(owner, game, team, players.get(i));
    assertThatThrownBy(() -> games.pick(owner, game, team, players.get(6)))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("completo");
  }

  @Test
  void drawPinsCaptainsBalancesTeamsAndLeavesWaitlistOut() {
    confirmed(12);
    List<TeamView> teams = captains();
    UUID first = teams.getFirst().id();
    games.pick(owner, game, first, players.get(2));
    games.lineup(
      owner,
      game,
      first,
      new Lineup(
        "2-2",
        Arrays.asList(owner, players.get(2), null, null, null),
        games.get(owner, game).teams().getFirst().version()
      )
    );

    var result = games.draw(owner, game);

    assertThat(result.teams().get(0).captainId()).isEqualTo(owner);
    assertThat(result.teams().get(1).captainId()).isEqualTo(players.get(1));
    for (TeamView team : result.teams())
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> team.id().equals(p.teamId()))
      ).hasSize(5);
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> {
      assertThat(p.teamId()).isNotNull();
      assertThat(p.slot()).isNull();
    });
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("WAITING"))
    ).allSatisfy(p -> {
      assertThat(p.teamId()).isNull();
      assertThat(p.slot()).isNull();
    });
    games.leave(players.get(2), game);
    var promoted = games
      .get(owner, game)
      .attendees()
      .stream()
      .filter(p -> p.id().equals(players.get(10)))
      .findFirst()
      .orElseThrow();
    assertThat(promoted.status()).isEqualTo("CONFIRMED");
    assertThat(promoted.teamId()).isNull();
    games.draw(owner, game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
  }

  @Test
  void newConfirmationsWaitForManualRedrawAndStartedGameCannotBeRedrawn() {
    confirmed(3);
    List<TeamView> teams = captains();
    games.draw(owner, game);
    games.attend(players.get(3), game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.id().equals(players.get(3)))
        .findFirst()
        .orElseThrow()
        .teamId()
    ).isNull();

    games.draw(owner, game);
    assertThat(
      games
        .get(owner, game)
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
    scheduledTimeHasPassed();
    matches.start(players.get(2), game);
    assertThatThrownBy(() -> games.draw(owner, game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("começou");
    assertThat(teams).hasSize(2);
  }

  @Test
  void concurrentDrawsLeaveEveryConfirmedPlayerOnExactlyOneTeam()
    throws Exception {
    confirmed(8);
    captains();

    var results = race(i -> games.draw(owner, game));

    assertThat(results.stream().filter(Objects::nonNull).count()).isZero();
    var result = games.get(owner, game);
    assertThat(
      result
        .attendees()
        .stream()
        .filter(p -> p.status().equals("CONFIRMED"))
    ).allSatisfy(p -> assertThat(p.teamId()).isNotNull());
    for (TeamView team : result.teams())
      assertThat(
        result
          .attendees()
          .stream()
          .filter(p -> team.id().equals(p.teamId()))
      ).hasSize(4);
  }

  @Test
  void monthlyBarbecueKeepsMonthEndAndReplenishesAfterAnIsolatedCancellation()
    throws Exception {
    ClubView recurring = groups.create(
      owner,
      new CreateClub("Grupo do churrasco", "", "MONTHLY")
    );
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant anchor = ZonedDateTime.of(
      2027,
      1,
      31,
      19,
      0,
      0,
      0,
      zone
    ).toInstant();

    List<BarbecueView> firstWindow = barbecues.startSeries(
      owner,
      recurring.id(),
      new CreateBarbecueSeries(anchor, "Salão", zone.getId())
    );

    assertThat(firstWindow).hasSize(6);
    assertThat(
      firstWindow
        .stream()
        .limit(3)
        .map(event -> event.startsAt().atZone(zone).toLocalDate())
    ).containsExactly(
      LocalDate.of(2027, 1, 31),
      LocalDate.of(2027, 2, 28),
      LocalDate.of(2027, 3, 31)
    );
    assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    assertThat(
      race(i -> barbecues.list(owner, recurring.id()).size())
        .stream()
        .filter(Objects::nonNull)
        .count()
    ).isZero();
    assertThat(
      barbecues.cancel(owner, firstWindow.getFirst().id()).cancelled()
    ).isTrue();

    List<BarbecueView> replenished = barbecues.list(owner, recurring.id());
    assertThat(replenished).hasSize(7);
    assertThat(
      replenished
        .stream()
        .filter(event -> event.recurring() && !event.cancelled())
    ).hasSize(6);
    assertThat(replenished.stream().map(BarbecueView::id).distinct()).hasSize(
      7
    );
  }

  @Test
  void twoAndThreeMonthBarbecueRecurrencesUseTheirAnchorDate() {
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant anchor = ZonedDateTime.of(
      2027,
      1,
      31,
      19,
      0,
      0,
      0,
      zone
    ).toInstant();
    for (String frequency : List.of("EVERY_2_MONTHS", "EVERY_3_MONTHS")) {
      ClubView recurring = groups.create(
        owner,
        new CreateClub("Grupo " + frequency, "", frequency)
      );
      List<BarbecueView> events = barbecues.startSeries(
        owner,
        recurring.id(),
        new CreateBarbecueSeries(anchor, "Salão", zone.getId())
      );
      LocalDate expected = frequency.equals("EVERY_2_MONTHS")
        ? LocalDate.of(2027, 3, 31)
        : LocalDate.of(2027, 4, 30);
      assertThat(events).hasSize(6);
      assertThat(events.get(1).startsAt().atZone(zone).toLocalDate()).isEqualTo(
        expected
      );
      assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    }
  }

  @Test
  void pauseStopsWindowGenerationAndResumeRestoresSixUpcomingEvents() {
    ClubView recurring = groups.create(
      owner,
      new CreateClub("Grupo pausável", "", "EVERY_2_MONTHS")
    );
    Instant anchor = Instant.now().plus(Duration.ofDays(45));
    List<BarbecueView> initial = barbecues.startSeries(
      owner,
      recurring.id(),
      new CreateBarbecueSeries(anchor, "Salão", "UTC")
    );
    UUID seriesId = tx.execute(
      s ->
        store
          .first(
            BarbecueSeries.class,
            "from BarbecueSeries where clubId=:club",
            "club",
            recurring.id()
          )
          .orElseThrow()
          .id
    );
    barbecues.pauseSeries(owner, recurring.id());
    tx.executeWithoutResult(s -> {
      store.get(BarbecueSeries.class, seriesId).nextOccurrenceIndex =
        initial.size();
      store
        .list(
          Barbecue.class,
          "from Barbecue where seriesId=:series",
          "series",
          seriesId
        )
        .forEach(event -> event.startsAt = Instant.now().minusSeconds(60));
    });

    assertThat(barbecues.list(owner, recurring.id())).hasSize(6);
    assertThat(
      groups
        .list(owner)
        .stream()
        .filter(c -> c.id().equals(recurring.id()))
        .findFirst()
        .orElseThrow()
        .barbecueSeriesActive()
    ).isFalse();
    List<BarbecueView> resumed = barbecues.resumeSeries(owner, recurring.id());
    assertThat(resumed).hasSize(12);
    assertThat(
      resumed.stream().filter(event -> event.startsAt().isAfter(Instant.now()))
    ).hasSize(6);
    assertThat(
      groups
        .list(owner)
        .stream()
        .filter(c -> c.id().equals(recurring.id()))
        .findFirst()
        .orElseThrow()
        .barbecueSeriesActive()
    ).isTrue();
  }

  @Test
  void barbecueGuestInviteIsEventScopedAndAttendanceIsIndependentFromFootball() {
    ClubView barbecueClub = groups.create(
      owner,
      new CreateClub("Churrasco avulso", "")
    );
    groups.join(players.get(1), barbecueClub.invite());
    Instant startsAt = Instant.now().plus(Duration.ofDays(3));
    BarbecueView invited = barbecues.createOneOff(
      owner,
      barbecueClub.id(),
      new CreateBarbecue(startsAt, "Salão")
    );
    BarbecueView other = barbecues.createOneOff(
      owner,
      barbecueClub.id(),
      new CreateBarbecue(startsAt.plus(Duration.ofDays(30)), "Quintal")
    );
    UUID outsider = tx.execute(
      s ->
        store
          .save(new Player("Convidado", "guest@test.invalid", "!disabled"))
          .id
    );

    barbecues.attendance(players.get(1), invited.id(), true);
    assertThat(games.get(players.get(1), game).attendees()).isEmpty();
    assertThatThrownBy(() ->
      barbecues.attendance(outsider, invited.id(), true)
    ).isInstanceOf(ApiException.class);
    UUID oldToken = UUID.fromString(invited.inviteToken());
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isFalse();
    barbecues.inviteAttendance(outsider, oldToken, true);
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isTrue();
    assertThat(groups.list(outsider)).isEmpty();
    assertThatThrownBy(() ->
      groups.requireMember(outsider, barbecueClub.id())
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      barbecues.list(outsider, barbecueClub.id())
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      barbecues.inviteDetails(outsider, UUID.randomUUID())
    ).isInstanceOf(ApiException.class);

    BarbecueView updated = barbecues.update(
      owner,
      other.id(),
      new UpdateBarbecue(startsAt.plus(Duration.ofDays(31)), "Outro salão")
    );
    assertThat(updated.location()).isEqualTo("Outro salão");
    assertThat(barbecues.cancel(owner, other.id()).cancelled()).isTrue();
    assertThat(barbecues.inviteDetails(outsider, oldToken)).isNotNull();
    assertThat(
      barbecues.inviteDetails(outsider, oldToken).attending()
    ).isTrue();
    assertThatThrownBy(() ->
      barbecues.createOneOff(
        players.get(1),
        barbecueClub.id(),
        new CreateBarbecue(startsAt, "Salão")
      )
    ).isInstanceOf(ApiException.class);
  }

  @Test
  void cancelledAndStartedGamesAreReadOnly() {
    games.cancel(owner, game);
    assertThatThrownBy(() -> games.attend(owner, game)).isInstanceOf(
      ApiException.class
    );
    UUID other = games
      .create(
        owner,
        club,
        new CreateGame("Outra", "Quadra", Instant.now().plusSeconds(3600), 2, 5)
      )
      .game()
      .id();
    tx.executeWithoutResult(
      s -> store.get(Game.class, other).startsAt = Instant.now().minusSeconds(5)
    );
    assertThatThrownBy(() -> games.attend(owner, other)).isInstanceOf(
      ApiException.class
    );
    assertThat(games.get(owner, other).game().editable()).isFalse();
  }

  @Test
  void demoGroupRejectsEvenItsOwner() {
    tx.executeWithoutResult(s -> store.get(Club.class, club).demo = true);
    assertThatThrownBy(() -> games.attend(owner, game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("demonstração");
    assertThatThrownBy(() -> games.cancel(owner, game)).isInstanceOf(
      ApiException.class
    );
  }

  void scheduledTimeHasPassed() {
    tx.executeWithoutResult(
      s -> store.get(Game.class, game).startsAt = Instant.now().minusSeconds(2)
    );
  }

  @Test
  void liveStartRequiresTimeAndAllConfirmedAssigned() {
    confirmed(3);
    var teams = captains();
    assertThatThrownBy(() -> matches.start(players.get(2), game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("horário");
    scheduledTimeHasPassed();
    assertThatThrownBy(() -> matches.start(players.get(2), game))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("Distribua");
    games.pick(owner, game, teams.getFirst().id(), players.get(2));
    matches.start(players.get(3), game);
    assertThat(games.get(owner, game).game().matchStatus()).isEqualTo("LIVE");
    assertThatThrownBy(() -> games.cancel(owner, game)).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() ->
      games.pick(owner, game, teams.getFirst().id(), players.get(4))
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> games.attend(players.get(4), game)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void onlyOneSimultaneousStartWins() throws Exception {
    confirmed(2);
    captains();
    scheduledTimeHasPassed();
    var results = race(i -> matches.start(players.get(i), game));
    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
    assertThat(games.get(owner, game).game().matchStartedAt()).isNotNull();
  }

  @Test
  void ownGoalsVoidsAndOrganizerCorrectionKeepFrozenClock() {
    confirmed(2);
    var teams = captains();
    scheduledTimeHasPassed();
    matches.start(owner, game);
    matches.goal(
      owner,
      game,
      new NewGoal(teams.get(1).id(), owner, true, null)
    );
    assertThat(matches.goals(storeGetGame()).getFirst().ownGoal()).isTrue();
    UUID goalId = matches.goals(storeGetGame()).getFirst().id();
    assertThatThrownBy(() ->
      matches.goal(
        owner,
        game,
        new NewGoal(teams.get(1).id(), owner, false, null)
      )
    ).isInstanceOf(ApiException.class);
    matches.voidGoal(players.get(1), game, goalId);
    assertThat(matches.goals(storeGetGame()).getFirst().voided()).isTrue();
    matches.finish(owner, game);
    assertThatThrownBy(() ->
      matches.goal(
        owner,
        game,
        new NewGoal(teams.get(0).id(), owner, false, null)
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      matches.correction(players.get(1), game, true)
    ).isInstanceOf(ApiException.class);
    matches.correction(owner, game, true);
    matches.duration(owner, game, 3120);
    matches.goal(owner, game, new NewGoal(teams.get(0).id(), owner, false, 42));
    matches.correction(owner, game, false);
    var result = games.get(owner, game);
    assertThat(result.game().matchDurationSeconds()).isEqualTo(3120);
    assertThat(
      result
        .goals()
        .stream()
        .filter(g -> !g.voided())
        .count()
    ).isEqualTo(1);
    assertThat(result.goals().getLast().minute()).isEqualTo(42);
  }

  @Test
  void ratingsArePrivateUntilDeadlineAndOverallUsesEqualGameWeight() {
    confirmed(3);
    var teams = captains();
    games.pick(owner, game, teams.getFirst().id(), players.get(2));
    scheduledTimeHasPassed();
    matches.start(owner, game);
    matches.finish(owner, game);
    assertThatThrownBy(() ->
      matches.rate(owner, game, new SaveRating(owner, 5))
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() ->
      matches.rate(players.get(1), game, new SaveRating(owner, 5))
    ).isInstanceOf(ApiException.class);
    matches.rate(players.get(2), game, new SaveRating(owner, 3));
    matches.rate(players.get(2), game, new SaveRating(owner, 5));
    assertThat(games.get(players.get(1), game).ratings()).isEmpty();
    assertThat(games.get(players.get(2), game).myRatings()).hasSize(1);
    tx.executeWithoutResult(
      s ->
        store.get(Game.class, game).matchEndedAt = Instant.now().minusSeconds(
          86401
        )
    );
    assertThat(
      games
        .get(players.get(1), game)
        .ratings()
        .stream()
        .filter(r -> r.playerId().equals(owner))
        .findFirst()
        .orElseThrow()
        .average()
    ).isEqualTo(5.0);
    assertThatThrownBy(() ->
      matches.rate(players.get(2), game, new SaveRating(owner, 4))
    ).isInstanceOf(ApiException.class);

    UUID second = games
      .create(
        owner,
        club,
        new CreateGame(
          "Outra pelada",
          "Quadra 2",
          Instant.now().plusSeconds(3600),
          2,
          5
        )
      )
      .game()
      .id();
    tx.executeWithoutResult(s -> {
      Game other = store.get(Game.class, second);
      other.matchStartedAt = Instant.now().minusSeconds(90000);
      other.matchEndedAt = Instant.now().minusSeconds(86401);
      other.matchDurationSeconds = 1200;
      store.save(
        new Rating(
          second,
          players.get(1),
          owner,
          1,
          Instant.now().minusSeconds(89000)
        )
      );
      store.save(
        new Rating(
          second,
          players.get(2),
          owner,
          3,
          Instant.now().minusSeconds(89000)
        )
      );
    });
    var profile = matches.profile(players.get(1), owner);
    assertThat(profile.average()).isEqualTo(3.5); // (5 + (1+3)/2) / 2
    assertThat(profile.ratedGames()).isEqualTo(2);
    UUID outsider = tx.execute(
      s ->
        store.save(new Player("Fora", "outsider@test.invalid", "!disabled")).id
    );
    assertThatThrownBy(() -> matches.profile(outsider, owner)).isInstanceOf(
      ApiException.class
    );
  }

  private Game storeGetGame() {
    return tx.execute(s -> store.get(Game.class, game));
  }

  private List<Throwable> race(IntConsumer operation) throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2),
        start = new CountDownLatch(1);
      List<Future<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        futures.add(
          executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
              operation.accept(index);
              return null;
            } catch (Throwable e) {
              return e;
            }
          })
        );
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Throwable> results = new ArrayList<>();
      for (var f : futures) results.add(f.get(15, TimeUnit.SECONDS));
      return results;
    }
  }
}
