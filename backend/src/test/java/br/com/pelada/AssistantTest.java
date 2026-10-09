package br.com.pelada;

import static br.com.pelada.assistant.AssistantContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.pelada.api.Contracts.CreateClub;
import br.com.pelada.assistant.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Player;
import br.com.pelada.groups.Groups;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  Assistant assistant;

  @Autowired
  Groups groups;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  @MockitoBean
  AiInterpreter interpreter;

  @MockitoBean
  AudioTranscriber audio;

  @Test
  void audioRequiresOwnerSessionAndCsrfAndOnlyReturnsText() throws Exception {
    when(audio.available()).thenReturn(true);
    when(audio.transcribe(eq(owner), any(), any())).thenReturn(
      new AudioTranscriber.Transcript("Sábado às 19h na Arena")
    );
    var file = new org.springframework.mock.web.MockMultipartFile(
      "file",
      "request.webm",
      "audio/webm",
      new byte[] { 1, 2, 3 }
    );
    String path = "/api/groups/" + club + "/assistant/audio";
    mvc
      .perform(multipart(path).file(file).with(csrf()))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(multipart(path).file(file).with(user("owner@assistant.invalid")))
      .andExpect(status().isForbidden());
    mvc
      .perform(
        multipart(path)
          .file(file)
          .with(user("member@assistant.invalid"))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    verifyNoInteractions(audio);
    mvc
      .perform(
        multipart(path)
          .file(file)
          .with(user("owner@assistant.invalid"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.text").value("Sábado às 19h na Arena"));
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_proposals",
        Integer.class
      )
    ).isZero();
  }

  UUID owner, member, club;
  LocalDate tomorrow;

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE players,assistant_request_limits CASCADE");
    owner = tx.execute(
      s ->
        store
          .save(
            new Player("Organizador", "owner@assistant.invalid", "!disabled")
          )
          .id
    );
    member = tx.execute(
      s ->
        store
          .save(new Player("Membro", "member@assistant.invalid", "!disabled"))
          .id
    );
    var c = groups.create(owner, new CreateClub("Pelada do teste", "Teste"));
    club = c.id();
    groups.join(member, c.invite());
    tomorrow = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1);
    when(interpreter.available()).thenReturn(true);
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new Interpretation(
        "Jogo do teste",
        "Arena",
        tomorrow,
        LocalTime.of(19, 0),
        2,
        7,
        false,
        null,
        null
      )
    );
  }

  Proposal proposal() {
    return assistant.propose(
      owner,
      club,
      new Request("Amanhã às 19h na Arena", null)
    );
  }

  void expectStatus(int code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ApiException.class,
      ex -> assertThat(ex.status).isEqualTo(code)
    );
  }

  @Test
  void apiRequiresAuthenticationCsrfAndBoundedInput() throws Exception {
    String path = "/api/groups/" + club + "/assistant/proposals";
    mvc
      .perform(get("/api/groups/" + club + "/assistant"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post(path)
          .with(user("owner@assistant.invalid"))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"Criar jogo\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post(path)
          .with(user("member@assistant.invalid"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"Criar jogo\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post(path)
          .with(user("owner@assistant.invalid"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"" + "x".repeat(1201) + "\"}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post(path)
          .with(user("owner@assistant.invalid"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"Amanhã na Arena\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.draft.location").value("Arena"));
  }

  @Test
  void interpretationDoesNotCreateAndConfirmationUsesGroupZone() {
    var p = proposal();
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Long.class)
    ).isZero();
    var d = assistant.confirm(owner, p.id(), new Confirmation(p.version()));
    assertThat(d.game().startsAt()).isEqualTo(
      tomorrow.atTime(19, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant()
    );
    assertThat(
      assistant
        .confirm(owner, p.id(), new Confirmation(p.version()))
        .game()
        .id()
    ).isEqualTo(d.game().id());
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Long.class)
    ).isEqualTo(1);
  }

  @Test
  void memberCannotInterpretReviseOrConfirm() {
    expectStatus(403, () ->
      assistant.propose(member, club, new Request("Criar jogo", null))
    );
    verify(interpreter, never()).interpret(anyString(), any());
    var p = proposal();
    expectStatus(403, () ->
      assistant.revise(member, p.id(), new Revision(0, p.draft()))
    );
    expectStatus(403, () ->
      assistant.confirm(member, p.id(), new Confirmation(0))
    );
  }

  @Test
  void missingDetailsAndAmbiguityRequireReview() {
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new Interpretation(
        null,
        null,
        tomorrow,
        null,
        null,
        null,
        null,
        null,
        "Qual é o local de sempre?"
      )
    );
    var p = proposal();
    assertThat(p.ready()).isFalse();
    assertThat(p.questions()).contains(
      "Em qual local será o jogo?",
      "Qual será o horário?"
    );
    expectStatus(400, () ->
      assistant.confirm(owner, p.id(), new Confirmation(0))
    );
    var draft = new Draft(
      "Pelada",
      "Quadra",
      tomorrow,
      LocalTime.NOON,
      2,
      7,
      false,
      null,
      false,
      null
    );
    var revised = assistant.revise(owner, p.id(), new Revision(0, draft));
    assertThat(revised.ready()).isTrue();
    expectStatus(409, () ->
      assistant.confirm(owner, p.id(), new Confirmation(0))
    );
    assertThat(
      assistant
        .confirm(owner, p.id(), new Confirmation(revised.version()))
        .game()
        .location()
    ).isEqualTo("Quadra");
  }

  @Test
  void expiredAndPastProposalsAreRejected() {
    var p = proposal();
    jdbc.update(
      "UPDATE assistant_proposals SET expires_at=now()-interval '1 second' WHERE id=?",
      p.id()
    );
    expectStatus(410, () ->
      assistant.confirm(owner, p.id(), new Confirmation(0))
    );
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new Interpretation(
        "Passado",
        "Arena",
        tomorrow.minusDays(2),
        LocalTime.NOON,
        2,
        7,
        false,
        null,
        null
      )
    );
    var past = proposal();
    assertThat(past.ready()).isFalse();
    expectStatus(400, () ->
      assistant.confirm(owner, past.id(), new Confirmation(0))
    );
  }

  @Test
  void newMessagePreservesFieldsAndExpiresPreviousProposal() {
    var old = proposal();
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new Interpretation(
        null,
        null,
        null,
        LocalTime.of(20, 0),
        null,
        null,
        null,
        null,
        null
      )
    );
    var next = assistant.propose(
      owner,
      club,
      new Request("Mude para 20h", old.id())
    );
    assertThat(next.draft().location()).isEqualTo("Arena");
    assertThat(next.draft().date()).isEqualTo(tomorrow);
    assertThat(next.draft().time()).isEqualTo(LocalTime.of(20, 0));
    expectStatus(410, () ->
      assistant.confirm(owner, old.id(), new Confirmation(0))
    );
  }

  @Test
  void invalidModelParametersNeverCreateGame() {
    when(interpreter.interpret(anyString(), any())).thenReturn(
      new Interpretation(
        "Inválido",
        "Arena",
        tomorrow,
        LocalTime.NOON,
        99,
        1,
        false,
        null,
        null
      )
    );
    expectStatus(400, this::proposal);
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Long.class)
    ).isZero();
  }

  @Test
  void unavailableAndFailedModelKeepManualCreationPossible() {
    when(interpreter.available()).thenReturn(false);
    assertThat(assistant.availability(owner, club).available()).isFalse();
    expectStatus(503, this::proposal);
    when(interpreter.available()).thenReturn(true);
    when(interpreter.interpret(anyString(), any())).thenThrow(
      new ApiException(503, "Falha simulada")
    );
    for (int i = 0; i < 10; i++) expectStatus(503, this::proposal);
    expectStatus(429, this::proposal);
    verify(interpreter, times(10)).interpret(anyString(), any());
  }

  @Test
  void concurrentConfirmationsCreateExactlyOneGame() throws Exception {
    var p = proposal();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<UUID> action = () -> {
        start.await();
        return assistant
          .confirm(owner, p.id(), new Confirmation(0))
          .game()
          .id();
      };
      var a = executor.submit(action);
      var b = executor.submit(action);
      start.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(
        b.get(15, TimeUnit.SECONDS)
      );
    }
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Long.class)
    ).isEqualTo(1);
  }

  @Test
  void weeklyRecurrenceAndReviewedBillingUseExistingRules() {
    var p = proposal();
    var revised = assistant.revise(
      owner,
      p.id(),
      new Revision(
        0,
        new Draft(
          "Semanal",
          "Arena",
          tomorrow,
          LocalTime.NOON,
          2,
          5,
          true,
          tomorrow.plusWeeks(2),
          true,
          2500L
        )
      )
    );
    var result = assistant.confirm(
      owner,
      revised.id(),
      new Confirmation(revised.version())
    );
    assertThat(result.game().recurring()).isTrue();
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Long.class)
    ).isEqualTo(3);
    assertThat(
      jdbc.queryForObject(
        "SELECT occasional_amount_cents FROM games WHERE id=?",
        Long.class,
        result.game().id()
      )
    ).isEqualTo(2500L);
  }
}
