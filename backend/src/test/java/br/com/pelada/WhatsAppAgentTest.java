package br.com.pelada;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.assistant.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.games.Games;
import br.com.pelada.groups.Groups;
import br.com.pelada.whatsapp.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
  properties = {
    "app.whatsapp.enabled=true",
    "app.whatsapp.business-number=15550000000",
    "app.whatsapp.phone-id=test-phone",
    "app.whatsapp.app-secret=test-secret",
    "app.whatsapp.verify-token=test-verify",
    "app.whatsapp.automation-enabled=true",
    "app.whatsapp.send-enabled=true",
    "app.whatsapp.integration-token=local-test-token-not-a-real-secret-0001",
    "app.whatsapp.pilot-numbers=5511999990000,5511999990001,5511999990002",
    "app.whatsapp.invitation-template=convite_teste",
    "app.whatsapp.reminder-template=lembrete_teste",
    "app.public-app-url=http://localhost:8080",
  }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WhatsAppAgentTest {

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  Store store;

  @Autowired
  Groups groups;

  @Autowired
  Games games;

  @Autowired
  WhatsApp whatsapp;

  @Autowired
  WhatsAppInbox inbox;

  @Autowired
  WhatsAppOutbox outbox;

  @Autowired
  WhatsAppAgent agent;

  @Autowired
  MockMvc mvc;

  @MockitoBean
  Clock clock;

  @MockitoBean
  AiInterpreter interpreter;

  @Value("${spring.datasource.url}")
  String database;

  UUID owner, member, other, club;
  Instant now = Instant.parse("2026-10-08T20:00:00Z");
  final String token = "Bearer local-test-token-not-a-real-secret-0001";

  @BeforeEach
  void setup() {
    assertThat(database).matches(
      "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/pelada[a-z_]*test"
    );
    now = Instant.parse("2026-10-08T20:00:00Z");
    when(clock.instant()).thenReturn(now);
    jdbc.execute(
      "TRUNCATE players,whatsapp_received_events,whatsapp_delivery_events,whatsapp_worker_limits,assistant_request_limits CASCADE"
    );
    var ids = tx.execute(s ->
      List.of(
        store.save(new Player("Ana", "owner@wa-agent.invalid", "!")).id,
        store.save(new Player("Beto", "member@wa-agent.invalid", "!")).id,
        store.save(new Player("Carol", "other@wa-agent.invalid", "!")).id
      )
    );
    owner = ids.get(0);
    member = ids.get(1);
    other = ids.get(2);
    var c = groups.create(owner, new CreateClub("Turma", ""));
    club = c.id();
    groups.join(member, c.invite());
    groups.join(other, c.invite());
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(true, 120));
    link(owner, "5511999990000");
    link(member, "5511999990001");
    link(other, "5511999990002");
    when(interpreter.available()).thenReturn(true);
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new AssistantContracts.Interpretation(
        "Jogo",
        "Arena",
        LocalDate.of(2026, 10, 9),
        LocalTime.of(19, 0),
        2,
        5,
        false,
        null,
        null
      )
    );
  }

  void advance(long seconds) {
    now = now.plusSeconds(seconds);
    when(clock.instant()).thenReturn(now);
  }

  void link(UUID user, String phone) {
    var c = whatsapp.begin(user);
    receive(phone, "VINCULAR " + c.code(), null, null);
    whatsapp.confirm(user, c.id());
  }

  void receive(String phone, String text, String button, String reply) {
    whatsapp.receive(
      List.of(
        new WhatsApp.Incoming(
          UUID.randomUUID().toString(),
          phone,
          text,
          now.getEpochSecond(),
          button,
          reply
        )
      )
    );
  }

  void consent(UUID user) {
    whatsapp.choose(
      user,
      club,
      new WhatsApp.Choice(true, true, WhatsApp.TEXT_VERSION)
    );
  }

  UUID game(long offset) {
    return games
      .create(
        owner,
        club,
        new CreateGame("Jogo", "Arena", now.plusSeconds(offset), 2, 5)
      )
      .game()
      .id();
  }

  WhatsAppInbox.Job job(String text) {
    receive("5511999990001", text, null, null);
    return inbox.claim().getFirst();
  }

  WhatsAppAgent.Result act(WhatsAppInbox.Job j, String action, UUID c, UUID g) {
    return agent.act(
      j.id(),
      j.leaseId(),
      new WhatsAppAgent.Command(action, c, g)
    );
  }

  WhatsAppOutbox.Lease invitation(UUID user) {
    return outbox
      .claim()
      .stream()
      .filter(l ->
        user.equals(
          jdbc.queryForObject(
            "SELECT player_id FROM whatsapp_outbox WHERE id=?",
            UUID.class,
            l.id()
          )
        )
      )
      .findFirst()
      .orElseThrow();
  }

  void fails(int code, Runnable run) {
    assertThatThrownBy(run::run).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.status).isEqualTo(code)
    );
  }

  int participation(UUID user, UUID game) {
    return jdbc.queryForObject(
      "SELECT count(*) FROM participations WHERE player_id=? AND game_id=?",
      Integer.class,
      user,
      game
    );
  }

  @Test
  void clearAnswerUpdatesRealAttendanceAndRepeatedToolCallCreatesOneReply() {
    var game = game(18000);
    var job = job("tô dentro");
    assertThat(act(job, "AUTO", null, null).message()).contains("confirmada");
    assertThat(participation(member, game)).isEqualTo(1);
    assertThat(act(job, "DECLINE", null, game).message()).contains(
      "confirmada"
    );
    assertThat(act(job, "PREPARE", null, null).message()).contains(
      "confirmada"
    );
    assertThat(participation(member, game)).isEqualTo(1);
    inbox.finish(job.id(), job.leaseId());
    inbox.finish(job.id(), job.leaseId());
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_outbox WHERE kind='REPLY'",
        Integer.class
      )
    ).isEqualTo(1);
    assertThat(
      jdbc.queryForObject(
        "SELECT text FROM whatsapp_inbox WHERE id=?",
        String.class,
        job.id()
      )
    ).isNull();
  }

  @Test
  void uncertainAnswerAndMultipleGamesNeverChangeAttendance() {
    UUID a = game(18000);
    var job = job("vou se sair cedo");
    assertThat(act(job, "AUTO", null, null).message()).contains(
      "Ainda não alterei"
    );
    assertThat(participation(member, a)).isZero();
    inbox.finish(job.id(), job.leaseId());
    game(20000);
    advance(1);
    var ambiguous = job("sim");
    assertThat(act(ambiguous, "ATTEND", null, a).message()).contains(
      "Confirme sua escolha"
    );
    assertThat(participation(member, a)).isZero();
  }

  @Test
  void invitationButtonsAreBoundToAccountAndCurrentGameRevision() {
    consent(member);
    UUID id = game(18000);
    var lease = invitation(member);
    var sent = outbox.dispatch(lease.id(), lease.leaseId());
    assertThat(sent.send()).isTrue();
    String button =
      "TD:" +
      jdbc.queryForObject(
        "SELECT id FROM whatsapp_actions WHERE player_id=? AND action='ATTEND'",
        UUID.class,
        member
      );
    receive("5511999990002", "", button, null);
    var stranger = inbox.claim().getFirst();
    assertThat(act(stranger, "AUTO", null, null).message()).contains("expirou");
    assertThat(participation(other, id)).isZero();
    inbox.finish(stranger.id(), stranger.leaseId());
    advance(2);
    games.update(
      owner,
      id,
      new UpdateGame("Mudou", "Outra arena", now.plusSeconds(19000), "ONE")
    );
    receive("5511999990001", "", button, null);
    var stale = inbox.claim().getFirst();
    assertThat(act(stale, "AUTO", null, null).message()).contains("mudaram");
    assertThat(participation(member, id)).isZero();
  }

  @Test
  void declineWithdrawsPresenceAndRecordsNoWithoutSendingReminder() {
    consent(member);
    UUID id = game(9000);
    games.attend(member, id);
    var yes = job("sim");
    act(yes, "AUTO", null, null);
    inbox.finish(yes.id(), yes.leaseId());
    advance(2);
    var no = job("não vou");
    assertThat(act(no, "AUTO", null, null).message()).contains("retirada");
    assertThat(participation(member, id)).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT response FROM whatsapp_responses WHERE player_id=?",
        String.class,
        member
      )
    ).isEqualTo("NO");
    inbox.finish(no.id(), no.leaseId());
    advance(1800);
    assertThat(outbox.claim()).allSatisfy(l ->
      assertThat(
        jdbc.queryForObject(
          "SELECT kind FROM whatsapp_outbox WHERE id=?",
          String.class,
          l.id()
        )
      ).isEqualTo("REPLY")
    );
  }

  @Test
  void preparationDoesNotCreateAndOnlyActualUserConfirmationCreatesOnce() {
    receive(
      "5511999990000",
      "Cria uma partida amanhã às 19h na Arena",
      null,
      null
    );
    var p = inbox.claim().getFirst();
    assertThat(act(p, "PREPARE", club, null).message()).contains(
      "Revise antes de criar"
    );
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
    inbox.finish(p.id(), p.leaseId());
    UUID button = jdbc.queryForObject(
      "SELECT id FROM whatsapp_actions WHERE action='CREATE'",
      UUID.class
    );
    advance(1);
    receive("5511999990000", "sim", null, null);
    var plain = inbox.claim().getFirst();
    act(plain, "AUTO", null, null);
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
    inbox.finish(plain.id(), plain.leaseId());
    advance(1);
    receive("5511999990000", "", "TD:" + button, null);
    var confirm = inbox.claim().getFirst();
    assertThat(act(confirm, "AUTO", null, null).message()).contains(
      "Partida criada"
    );
    act(confirm, "AUTO", null, null);
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isEqualTo(1);
  }

  @Test
  void nonOwnerCannotPrepareAndStaleLeaseCannotActAfterDisconnect() {
    var j = job("Cria uma partida");
    act(j, "PREPARE", club, null);
    verify(interpreter, never()).interpret(anyString(), any());
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
    whatsapp.disconnect(member);
    fails(409, () -> act(j, "AUTO", null, null));
  }

  @Test
  void twoPlayersCompetingForLastSpotRespectExistingWaitingList()
    throws Exception {
    UUID id = game(18000);
    for (int n = 0; n < 9; n++) {
      final int k = n;
      UUID filler = tx.execute(
        s -> store.save(new Player("P" + k, "p" + k + "@wa.invalid", "!")).id
      );
      groups.join(filler, groups.list(owner).getFirst().invite());
      games.attend(filler, id);
    }
    receive("5511999990001", "sim", null, null);
    receive("5511999990002", "sim", null, null);
    var jobs = inbox.claim();
    try (var e = Executors.newFixedThreadPool(2)) {
      var a = e.submit(() -> act(jobs.get(0), "AUTO", null, null));
      var b = e.submit(() -> act(jobs.get(1), "AUTO", null, null));
      assertThat(
        List.of(
          a.get(10, TimeUnit.SECONDS).message(),
          b.get(10, TimeUnit.SECONDS).message()
        )
      ).anySatisfy(s -> assertThat(s).contains("fila"));
    }
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM participations WHERE game_id=? AND status='CONFIRMED'",
        Integer.class,
        id
      )
    ).isEqualTo(10);
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM participations WHERE game_id=? AND status='WAITING'",
        Integer.class,
        id
      )
    ).isEqualTo(1);
  }

  @Test
  void deliveryUsesAuthorizationAndStopCancelsClaimedMessage() {
    consent(member);
    game(18000);
    var lease = invitation(member);
    receive("5511999990001", "SAIR", null, null);
    fails(409, () -> outbox.dispatch(lease.id(), lease.leaseId()));
    assertThat(outbox.claim()).isEmpty();
  }

  @Test
  void acceptedIsNotDeliveredAndStatusBeforeHttpResultIsReconciled() {
    consent(member);
    game(18000);
    var lease = invitation(member);
    outbox.dispatch(lease.id(), lease.leaseId());
    outbox.delivery("wamid.test", "delivered", now.getEpochSecond());
    outbox.receipt(
      lease.id(),
      lease.leaseId(),
      new WhatsAppOutbox.Receipt("ACCEPTED", "wamid.test", null)
    );
    assertThat(
      jdbc.queryForObject(
        "SELECT state FROM whatsapp_outbox WHERE id=?",
        String.class,
        lease.id()
      )
    ).isEqualTo("DELIVERED");
    outbox.delivery("wamid.test", "read", now.getEpochSecond());
    outbox.delivery("wamid.test", "failed", now.getEpochSecond());
    assertThat(
      jdbc.queryForObject(
        "SELECT state FROM whatsapp_outbox WHERE id=?",
        String.class,
        lease.id()
      )
    ).isEqualTo("READ");
  }

  @Test
  void ambiguousTimeoutIsNeverAutomaticallyResentAndPauseCanResumePending() {
    consent(member);
    game(18000);
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(false, 120));
    assertThat(outbox.claim()).isEmpty();
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(true, 120));
    var lease = invitation(member);
    outbox.dispatch(lease.id(), lease.leaseId());
    advance(91);
    outbox.reconcile();
    assertThat(outbox.claim()).isEmpty();
    assertThat(
      jdbc.queryForObject(
        "SELECT state FROM whatsapp_outbox WHERE id=?",
        String.class,
        lease.id()
      )
    ).isEqualTo("UNKNOWN");
  }

  @Test
  void integrationRoutesNeedDedicatedTokenAndBrowserRoutesKeepCsrf()
    throws Exception {
    mvc
      .perform(post("/api/integrations/whatsapp/inbox/claim"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/integrations/whatsapp/inbox/claim").with(
          user("owner@wa-agent.invalid")
        )
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/integrations/whatsapp/inbox/claim").header(
          "Authorization",
          token
        )
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        put("/api/whatsapp/groups/" + club + "/automation")
          .with(user("owner@wa-agent.invalid"))
          .contentType("application/json")
          .content("{\"enabled\":true,\"reminderMinutes\":120}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        get("/api/whatsapp/groups/" + club + "/automation").with(
          user("member@wa-agent.invalid")
        )
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void signedInteractiveWebhookQueuesOneActualButtonAndReconcilesDelivery()
    throws Exception {
    consent(member);
    UUID id = game(18000);
    var lease = invitation(member);
    var sent = outbox.dispatch(lease.id(), lease.leaseId());
    String button =
      "TD:" +
      jdbc.queryForObject(
        "SELECT id FROM whatsapp_actions WHERE player_id=? AND action='ATTEND'",
        UUID.class,
        member
      );
    outbox.receipt(
      lease.id(),
      lease.leaseId(),
      new WhatsAppOutbox.Receipt("ACCEPTED", "wamid.signed", null)
    );
    String payload = """
    {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{
      "metadata":{"phone_number_id":"test-phone"},
      "messages":[{"id":"wamid.inbound","from":"5511999990001","timestamp":"%s","type":"interactive",
        "context":{"id":"wamid.signed"},"interactive":{"type":"button_reply","button_reply":{"id":"%s","title":"Vou jogar"}}}],
      "statuses":[{"id":"wamid.signed","status":"delivered","timestamp":"%s"}]
    }}]}]}
    """.formatted(now.getEpochSecond(), button, now.getEpochSecond());
    byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
      new javax.crypto.spec.SecretKeySpec(
        "test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "HmacSHA256"
      )
    );
    String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    mvc
      .perform(
        post("/api/whatsapp/webhook")
          .contentType("application/json")
          .content(bytes)
      )
      .andExpect(status().isForbidden());
    for (int i = 0; i < 2; i++) mvc
      .perform(
        post("/api/whatsapp/webhook")
          .header("X-Hub-Signature-256", signature)
          .contentType("application/json")
          .content(bytes)
      )
      .andExpect(status().isOk());
    var jobs = inbox.claim();
    assertThat(jobs).hasSize(1);
    assertThat(jobs.getFirst().button()).isEqualTo(button);
    assertThat(act(jobs.getFirst(), "AUTO", null, null).message()).contains(
      "confirmada"
    );
    assertThat(participation(member, id)).isEqualTo(1);
    assertThat(
      jdbc.queryForObject(
        "SELECT state FROM whatsapp_outbox WHERE id=?",
        String.class,
        lease.id()
      )
    ).isEqualTo("DELIVERED");
  }

  @Test
  void pausedGroupDoesNotStoreNewCommandAndCurrentLastAttemptIsNotExpired() {
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(false, 120));
    receive("5511999990001", "agenda", null, null);
    assertThat(inbox.claim()).isEmpty();
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(true, 120));
    var job = job("agenda");
    jdbc.update("UPDATE whatsapp_inbox SET attempts=3 WHERE id=?", job.id());
    inbox.cleanup();
    assertThat(act(job, "AUTO", null, null).message()).contains(
      "Não há partidas"
    );
  }

  @Test
  void dailyQuotaStopsSubmission() {
    consent(member);
    game(18000);
    var lease = invitation(member);
    String bucket = "send:" + now.getEpochSecond() / 86400;
    jdbc.update(
      "INSERT INTO whatsapp_worker_limits VALUES(?,20,?)",
      bucket,
      java.sql.Timestamp.from(now.plusSeconds(86400))
    );
    assertThat(outbox.dispatch(lease.id(), lease.leaseId()).send()).isFalse();
  }

  @Test
  void explicitTransientRejectionRetriesAfterBackoffButPermanentFailureDoesNot() {
    consent(member);
    game(18000);
    var lease = invitation(member);
    outbox.dispatch(lease.id(), lease.leaseId());
    outbox.receipt(
      lease.id(),
      lease.leaseId(),
      new WhatsAppOutbox.Receipt("FAILED", null, "130429")
    );
    assertThat(outbox.claim()).isEmpty();
    advance(61);
    var retry = invitation(member);
    assertThat(retry.id()).isEqualTo(lease.id());
    outbox.dispatch(retry.id(), retry.leaseId());
    outbox.receipt(
      retry.id(),
      retry.leaseId(),
      new WhatsAppOutbox.Receipt("FAILED", null, "131026")
    );
    advance(121);
    assertThat(outbox.claim()).isEmpty();
  }
}
