package br.com.pelada;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.pelada.api.Contracts.CreateClub;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.groups.Groups;
import br.com.pelada.whatsapp.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
  "app.whatsapp.enabled=true", "app.whatsapp.business-number=353830000000",
  "app.whatsapp.phone-id=gs-phone", "app.whatsapp.provider=GUPSHUP",
  "app.whatsapp.gupshup-app-id=11111111-1111-1111-1111-111111111111",
  "app.whatsapp.gupshup-webhook-token=1111111111111111111111111111111111111111111111111111111111111111",
  "app.whatsapp.automation-enabled=true", "app.whatsapp.send-enabled=true",
  "app.whatsapp.replies-only=true",
  "app.whatsapp.integration-token=local-test-token-not-a-real-secret-0001",
  "app.whatsapp.pilot-numbers=5511999990000", "app.public-app-url=http://localhost:8080"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WhatsAppGupshupTest {
  static final String APP = "11111111-1111-1111-1111-111111111111";
  static final String WEBHOOK = "1".repeat(64);
  static final String WORKER = "Bearer local-test-token-not-a-real-secret-0001";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired Store store;
  @Autowired Groups groups;
  @Autowired TransactionTemplate tx;
  @Autowired WhatsApp whatsapp;
  @Autowired WhatsAppInbox inbox;
  @Autowired WhatsAppOutbox outbox;
  @Autowired WhatsAppAgent agent;
  @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String database;
  @MockitoBean Clock clock;
  Instant now = Instant.parse("2026-10-10T20:00:00Z");
  UUID owner;

  @BeforeEach void setup() {
    assertThat(database).matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/pelada[a-z_]*test");
    when(clock.instant()).thenReturn(now);
    jdbc.execute("TRUNCATE players,whatsapp_received_events,whatsapp_delivery_events,whatsapp_worker_limits,assistant_request_limits CASCADE");
    owner = tx.execute(s -> store.save(new Player("Ana", "gs@wa.invalid", "!disabled")).id);
    UUID club = groups.create(owner, new CreateClub("Turma", "")).id();
    outbox.choose(owner, club, new WhatsAppOutbox.Choice(true, 60));
    jdbc.update("INSERT INTO whatsapp_contacts(player_id,phone,verified_at) VALUES(?,?,?)",
      owner, "+5511999990000", Timestamp.from(now.minusSeconds(60)));
  }

  String message(String app, String phoneId, String from, String message) {
    return """
      {"gs_app_id":"%s","object":"whatsapp_business_account","entry":[{"changes":[{
      "field":"messages","value":{"metadata":{"phone_number_id":"%s"},
      "messages":[{"id":"wamid.gs","from":"%s","timestamp":"%s",%s}]}}]}]}
      """.formatted(app, phoneId, from, now.getEpochSecond(), message);
  }

  void postEvent(String json) throws Exception {
    mvc.perform(post("/api/whatsapp/webhook/gupshup").header("X-ToDentro-Webhook", WEBHOOK)
      .contentType("application/json").content(json)).andExpect(status().isOk());
  }

  @Test void requiresIsolatedCredentialAndAppBeforeConsuming() throws Exception {
    String json = message(APP, "gs-phone", "5511999990000", "\"type\":\"text\",\"text\":{\"body\":\"agenda\"}");
    mvc.perform(post("/api/whatsapp/webhook/gupshup").contentType("application/json").content(json))
      .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/whatsapp/webhook/gupshup").header("X-ToDentro-Webhook", WORKER)
      .contentType("application/json").content(json)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/whatsapp/webhook/gupshup").header("X-ToDentro-Webhook", WEBHOOK)
      .contentType("application/json").content(json.replace(APP, "22222222-2222-2222-2222-222222222222")))
      .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_inbox", Integer.class)).isZero();
  }

  @Test void keepsSenderPilotAndDeduplicationChecks() throws Exception {
    postEvent(message(APP, "other-phone", "5511999990000", "\"type\":\"text\",\"text\":{\"body\":\"agenda\"}"));
    postEvent(message(APP, "gs-phone", "5511999990001", "\"type\":\"text\",\"text\":{\"body\":\"agenda\"}"));
    String json = message(APP, "gs-phone", "5511999990000", "\"type\":\"text\",\"text\":{\"body\":\"agenda\"}")
      .replace("wamid.gs", "wamid.pilot");
    postEvent(json); postEvent(json);
    assertThat(inbox.claim()).hasSize(1);
  }

  @Test void textUsesExistingAgentAndOnlyMatchingWorkerCanReserve() throws Exception {
    postEvent(message(APP, "gs-phone", "5511999990000", "\"type\":\"text\",\"text\":{\"body\":\"agenda\"}"));
    var job = inbox.claim().getFirst();
    assertThat(agent.act(job.id(), job.leaseId(), new WhatsAppAgent.Command("AUTO", null, null)).needsAgent()).isFalse();
    inbox.finish(job.id(), job.leaseId());
    mvc.perform(post("/api/integrations/whatsapp/outbox/claim").header("Authorization", WORKER))
      .andExpect(status().isOk()).andExpect(content().json("[]"));
    var response = mvc.perform(post("/api/integrations/whatsapp/outbox/claim").header("Authorization", WORKER)
      .contentType("application/json").content("{\"provider\":\"GUPSHUP\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists()).andReturn();
    var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).path(0);
    UUID id = UUID.fromString(tree.path("id").asText()), lease = UUID.fromString(tree.path("leaseId").asText());
    String dispatch = "/api/integrations/whatsapp/outbox/" + id + "/dispatch";
    mvc.perform(post(dispatch).header("Authorization", WORKER).contentType("application/json")
      .content("{\"leaseId\":\"" + lease + "\"}")).andExpect(status().isConflict());
    mvc.perform(post(dispatch).header("Authorization", WORKER).contentType("application/json")
      .content("{\"leaseId\":\"" + lease + "\",\"provider\":\"GUPSHUP\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://api.gupshup.io/wa/app/" + APP + "/v3/msg"))
      .andExpect(jsonPath("$.payload.to").value("5511999990000"));
    outbox.receipt(id, lease, new WhatsAppOutbox.Receipt("ACCEPTED", "gs-outgoing", null));
    postEvent("""
      {"gs_app_id":"%s","object":"whatsapp_business_account","entry":[{"changes":[{
      "field":"messages","value":{"metadata":{"phone_number_id":"gs-phone"},
      "statuses":[{"id":"wamid.provider","gs_id":"gs-outgoing","status":"delivered","timestamp":"%s"}]}}]}]}
      """.formatted(APP, now.getEpochSecond()));
    assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_outbox WHERE id=?", String.class, id)).isEqualTo("DELIVERED");
  }

  @Test void audioUsesExplicitTextFallbackAndNeverSendsMetaCredential() throws Exception {
    postEvent(message(APP, "gs-phone", "5511999990000", "\"type\":\"audio\",\"audio\":{\"id\":\"123456\",\"mime_type\":\"audio/ogg\"}"));
    var job = inbox.claim().getFirst();
    assertThat(job.mediaUrl()).isNull();
    var result = agent.act(job.id(), job.leaseId(), new WhatsAppAgent.Command("AUTO", null, null));
    assertThat(result.message()).contains("envie seu pedido por texto");
    assertThat(result.needsAgent()).isFalse();
  }

  @Test void bodyLimitIsEnforcedBeforeParsing() throws Exception {
    mvc.perform(post("/api/whatsapp/webhook/gupshup").header("X-ToDentro-Webhook", WEBHOOK)
      .contentType("application/json").content("x".repeat(131073))).andExpect(status().isPayloadTooLarge());
  }
}
