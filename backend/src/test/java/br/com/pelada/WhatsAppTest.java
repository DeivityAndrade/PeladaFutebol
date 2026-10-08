package br.com.pelada;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.pelada.api.Contracts.CreateClub;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.groups.Groups;
import br.com.pelada.whatsapp.WhatsApp;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
  }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WhatsAppTest {

  @Autowired
  WhatsApp whatsapp;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  MockMvc mvc;

  @MockitoBean
  Clock clock;

  UUID owner, member, stranger, club;
  Instant now = Instant.parse("2026-10-08T20:00:00Z");

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(now);
    jdbc.execute("TRUNCATE players,whatsapp_received_events CASCADE");
    var ids = tx.execute(s ->
      List.of(
        store.save(new Player("Ana", "owner@wa.invalid", "!disabled")).id,
        store.save(new Player("Beto", "member@wa.invalid", "!disabled")).id,
        store.save(new Player("Carol", "stranger@wa.invalid", "!disabled")).id
      )
    );
    owner = ids.get(0);
    member = ids.get(1);
    stranger = ids.get(2);
    var c = groups.create(owner, new CreateClub("Turma", ""));
    club = c.id();
    groups.join(member, c.invite());
  }

  void fails(int code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ApiException.class,
      e -> assertThat(e.status).isEqualTo(code)
    );
  }

  void incoming(String id, String phone, String text) {
    whatsapp.receive(
      List.of(
        new WhatsApp.Incoming(id, phone, text, clock.instant().getEpochSecond())
      )
    );
  }

  WhatsApp.Challenge prove(UUID user, String phone) {
    var c = whatsapp.begin(user);
    incoming(UUID.randomUUID().toString(), phone, "VINCULAR " + c.code());
    return c;
  }

  void link(UUID user, String phone) {
    var c = prove(user, phone);
    whatsapp.confirm(user, c.id());
  }

  void consent(UUID user, boolean invitations, boolean reminders) {
    whatsapp.choose(
      user,
      club,
      new WhatsApp.Choice(invitations, reminders, WhatsApp.TEXT_VERSION)
    );
  }

  @Test
  void incomingCodeRequiresAuthenticatedConfirmationAndNeverGrantsConsent() {
    var c = whatsapp.begin(owner);
    assertThat(c.url()).startsWith("https://wa.me/15550000000?text=VINCULAR+");
    assertThat(
      jdbc.queryForObject(
        "SELECT code_hash FROM whatsapp_contacts WHERE player_id=?",
        String.class,
        owner
      )
    ).doesNotContain(c.code());
    fails(409, () -> whatsapp.confirm(owner, c.id()));
    incoming("msg-1", "5511999990000", "VINCULAR " + c.code());
    var pending = whatsapp.status(owner);
    assertThat(pending.verified()).isFalse();
    assertThat(pending.pendingPhone()).isEqualTo("+55 •••• 0000");
    incoming("msg-2", "5511999991234", "VINCULAR " + c.code());
    assertThat(whatsapp.status(owner).pendingPhone()).endsWith("0000");
    fails(409, () -> whatsapp.confirm(stranger, c.id()));
    var linked = whatsapp.confirm(owner, c.id());
    assertThat(linked.verified()).isTrue();
    assertThat(linked.groups().getFirst().invitations()).isFalse();
    fails(409, () -> whatsapp.confirm(owner, c.id()));
  }

  @Test
  void expiryRotationAndLimitsSurviveDatabaseReloads() {
    var first = whatsapp.begin(owner);
    fails(429, () -> whatsapp.begin(owner));
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    var second = whatsapp.begin(owner);
    incoming("old", "5511999990000", "VINCULAR " + first.code());
    assertThat(whatsapp.status(owner).pendingPhone()).isNull();
    when(clock.instant()).thenReturn(now.plusSeconds(661));
    incoming("expired", "5511999990000", "VINCULAR " + second.code());
    fails(409, () -> whatsapp.confirm(owner, second.id()));
    whatsapp.begin(owner);
    when(clock.instant()).thenReturn(now.plusSeconds(722));
    fails(429, () -> whatsapp.begin(owner));
    when(clock.instant()).thenReturn(now.plusSeconds(3601));
    assertThat(whatsapp.begin(owner).code()).isNotBlank();
  }

  @Test
  void stopDisconnectAndNumberChangeRevokeAllConsents() {
    link(owner, "5511999990000");
    consent(owner, true, true);
    incoming("stop", "5511999990000", " sair ");
    assertThat(whatsapp.status(owner).stopped()).isTrue();
    assertThat(
      whatsapp.status(owner).groups().getFirst().reminders()
    ).isFalse();
    fails(409, () -> consent(owner, true, false));
    incoming("stop", "5511999990000", "SAIR");
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_received_events WHERE event_hash IS NOT NULL",
        Integer.class
      )
    ).isEqualTo(2);
    when(clock.instant()).thenReturn(now.plusSeconds(61));
    link(owner, "5511999990000");
    consent(owner, true, false);
    when(clock.instant()).thenReturn(now.plusSeconds(122));
    link(owner, "5511999991234");
    assertThat(
      whatsapp.status(owner).groups().getFirst().invitations()
    ).isFalse();
    consent(owner, false, true);
    whatsapp.disconnect(owner);
    assertThat(whatsapp.status(owner).verified()).isFalse();
    assertThat(
      whatsapp.status(owner).groups().getFirst().reminders()
    ).isFalse();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_consent_events WHERE reason='STOP'",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void staleStopRetryDoesNotCancelFreshAuthorization() {
    link(owner, "5511999990000");
    consent(owner, true, true);
    incoming("stop", "5511999990000", "SAIR");
    when(clock.instant()).thenReturn(now.plusSeconds(61));
    link(owner, "5511999990000");
    consent(owner, true, false);
    incoming("stop", "5511999990000", "SAIR");
    assertThat(
      whatsapp.status(owner).groups().getFirst().invitations()
    ).isTrue();
  }

  @Test
  void stopCancelsPendingProofAndOldStopDoesNotRevokeNewNumberVerification() {
    var pending = prove(owner, "5511999990000");
    incoming("stop-pending", "5511999990000", "SAIR");
    fails(409, () -> whatsapp.confirm(owner, pending.id()));
    when(clock.instant()).thenReturn(now.plusSeconds(61));
    link(owner, "5511999990000");
    consent(owner, true, true);
    whatsapp.receive(
      List.of(
        new WhatsApp.Incoming(
          "old-delayed-stop",
          "5511999990000",
          "SAIR",
          now.getEpochSecond()
        )
      )
    );
    assertThat(
      whatsapp.status(owner).groups().getFirst().invitations()
    ).isTrue();
  }

  @Test
  void numberBelongsToOnlyOneAccountEvenWithConcurrentConfirmations()
    throws Exception {
    var a = prove(owner, "5511999990000");
    var b = prove(member, "5511999990000");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Boolean> one = () -> {
        gate.await();
        try {
          whatsapp.confirm(owner, a.id());
          return true;
        } catch (ApiException e) {
          assertThat(e.status).isEqualTo(409);
          return false;
        }
      };
      Callable<Boolean> two = () -> {
        gate.await();
        try {
          whatsapp.confirm(member, b.id());
          return true;
        } catch (ApiException e) {
          assertThat(e.status).isEqualTo(409);
          return false;
        }
      };
      var x = executor.submit(one);
      var y = executor.submit(two);
      gate.countDown();
      assertThat(
        List.of(x.get(10, TimeUnit.SECONDS), y.get(10, TimeUnit.SECONDS))
      ).containsExactlyInAnyOrder(true, false);
    }
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_contacts WHERE phone IS NOT NULL",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void preferencesRequireMembershipProofAndCurrentTextAndDoNotReviveAfterRejoin() {
    fails(409, () -> consent(owner, true, true));
    link(owner, "5511999990000");
    consent(owner, true, false);
    fails(403, () -> consent(stranger, false, false));
    fails(409, () ->
      whatsapp.choose(owner, club, new WhatsApp.Choice(true, true, "old"))
    );
    jdbc.update(
      "DELETE FROM members WHERE club_id=? AND player_id=?",
      club,
      owner
    );
    assertThat(whatsapp.status(owner).groups()).isEmpty();
    groups.join(owner, groups.list(member).getFirst().invite());
    assertThat(
      whatsapp.status(owner).groups().getFirst().invitations()
    ).isFalse();
  }

  @Test
  void cleanupRemovesExpiredCodesAndOldEventsWithoutRemovingCurrentLink() {
    link(owner, "5511999990000");
    consent(owner, true, false);
    when(clock.instant()).thenReturn(now.plusSeconds(61));
    whatsapp.begin(owner);
    when(clock.instant()).thenReturn(now.plus(Duration.ofDays(91)));
    whatsapp.cleanup();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_received_events",
        Integer.class
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_consent_events",
        Integer.class
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT code_hash FROM whatsapp_contacts WHERE player_id=?",
        String.class,
        owner
      )
    ).isNull();
    assertThat(whatsapp.status(owner).verified()).isTrue();
  }

  byte[] payload(String code, String phoneId) {
    return """
    {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{
    "metadata":{"phone_number_id":"%s"},"messages":[{"id":"webhook-msg","from":"5511999990000","type":"text",
    "timestamp":"%d","text":{"body":"VINCULAR %s"}}]}}]}]}
    """
      .formatted(phoneId, now.getEpochSecond(), code)
      .getBytes(StandardCharsets.UTF_8);
  }

  String signature(byte[] body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(
      new SecretKeySpec(
        "test-secret".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"
      )
    );
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void webhookAuthenticatesRawBodyFiltersReceivingNumberAndDeduplicatesWithoutSession()
    throws Exception {
    var c = whatsapp.begin(owner);
    byte[] body = payload(c.code(), "other-phone");
    mvc
      .perform(
        post("/api/whatsapp/webhook")
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/whatsapp/webhook")
          .header("X-Hub-Signature-256", signature(body))
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk());
    assertThat(whatsapp.status(owner).pendingPhone()).isNull();
    body = payload(c.code(), "test-phone");
    for (int i = 0; i < 2; i++) mvc
      .perform(
        post("/api/whatsapp/webhook")
          .header("X-Hub-Signature-256", signature(body))
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk());
    assertThat(whatsapp.status(owner).pendingPhone()).endsWith("0000");
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM whatsapp_received_events",
        Integer.class
      )
    ).isEqualTo(1);
    byte[] changed = Arrays.copyOf(body, body.length + 1);
    mvc
      .perform(
        post("/api/whatsapp/webhook")
          .header("X-Hub-Signature-256", signature(body))
          .contentType("application/json")
          .content(changed)
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void challengeAndBrowserRoutesKeepAuthenticationCsrfAndPrivatePhones()
    throws Exception {
    mvc
      .perform(
        get("/api/whatsapp/webhook")
          .param("hub.mode", "subscribe")
          .param("hub.verify_token", "test-verify")
          .param("hub.challenge", "123")
      )
      .andExpect(status().isOk())
      .andExpect(content().string("123"));
    mvc
      .perform(
        get("/api/whatsapp/webhook")
          .param("hub.mode", "subscribe")
          .param("hub.verify_token", "wrong")
          .param("hub.challenge", "123")
      )
      .andExpect(status().isForbidden());
    mvc.perform(get("/api/whatsapp")).andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/whatsapp/verification").with(user("owner@wa.invalid"))
      )
      .andExpect(status().isForbidden());
    link(owner, "5511999990000");
    mvc
      .perform(get("/api/whatsapp").with(user("member@wa.invalid")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.phone").isEmpty());
    mvc
      .perform(get("/api/whatsapp").with(user("owner@wa.invalid")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.phone").value("+55 •••• 0000"));
    mvc
      .perform(delete("/api/whatsapp").with(user("owner@wa.invalid")))
      .andExpect(status().isForbidden());
    mvc
      .perform(
        delete("/api/whatsapp").with(user("owner@wa.invalid")).with(csrf())
      )
      .andExpect(status().isOk());
  }
}
