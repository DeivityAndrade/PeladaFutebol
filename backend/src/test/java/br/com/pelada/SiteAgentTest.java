package br.com.pelada;

import static br.com.pelada.assistant.AgentContracts.*;
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
class SiteAgentTest {

  @Autowired
  SiteAgent agent;

  @Autowired
  Groups groups;

  @Autowired
  Games games;

  @Autowired
  Store store;

  @Autowired
  TransactionTemplate tx;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MockMvc mvc;

  @MockitoBean
  AgentInterpreter interpreter;

  @MockitoBean
  AiInterpreter creator;

  @MockitoBean
  AudioTranscriber audio;

  UUID owner, member, club;
  LocalDate tomorrow;

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE players,assistant_request_limits CASCADE");
    owner = tx.execute(
      s ->
        store
          .save(
            new Player(
              "Organizador",
              "agent-owner@example.invalid",
              "!disabled"
            )
          )
          .id
    );
    member = tx.execute(
      s ->
        store
          .save(
            new Player("Membro", "agent-member@example.invalid", "!disabled")
          )
          .id
    );
    var c = groups.create(owner, new CreateClub("Corrêa", "Teste"));
    club = c.id();
    groups.join(member, c.invite());
    tomorrow = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1);
    when(interpreter.available()).thenReturn(true);
    when(creator.available()).thenReturn(true);
  }

  Decision decision(String action, UUID game, String local) {
    return new Decision(
      action,
      club,
      game,
      "Pelada",
      local,
      tomorrow,
      LocalTime.of(20, 0),
      2,
      7,
      false,
      null,
      null
    );
  }

  Message message(String text, Reply previous) {
    return new Message(
      text,
      previous == null ? null : previous.conversationId(),
      previous == null ? 0 : previous.version(),
      club
    );
  }

  void rejected(int code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
      ApiException.class,
      e -> assertThat(e.status).isEqualTo(code)
    );
  }

  @Test
  void missingDetailsContinueFromServerContextAndOneModelCallPerTurn() {
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("CREATE", null, null)
    );
    var first = agent.message(owner, message("Marque amanhã às 20h", null));
    assertThat(first.action()).isNull();
    assertThat(first.message()).contains("local");
    when(interpreter.decide(anyString(), any())).thenAnswer(invocation -> {
      Context context = invocation.getArgument(1);
      assertThat(context.previous()).isNotNull();
      assertThat(context.previous().date()).isEqualTo(tomorrow);
      assertThat(context.previous().occasionalAmountCents()).isNull();
      assertThat(context.history()).hasSize(2);
      return new Decision(
        "CREATE",
        club,
        null,
        null,
        "Gools",
        null,
        null,
        null,
        null,
        null,
        null,
        null
      );
    });
    var next = agent.message(owner, message("No Gools", first));
    assertThat(next.action().proposal().draft().location()).isEqualTo("Gools");
    assertThat(next.action().proposal().draft().time()).isEqualTo(
      LocalTime.of(20, 0)
    );
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
    verify(creator, never()).interpret(anyString(), any());
    assertThat(
      jdbc.queryForObject(
        "SELECT sum(requests) FROM assistant_request_limits WHERE bucket LIKE 'global:%'",
        Integer.class
      )
    ).isEqualTo(2);
  }

  @Test
  void confirmationCreatesOnceAndOldActionsCannotExecuteRevisedProposal() {
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("CREATE", null, "Gools")
    );
    var first = agent.message(owner, message("Marque amanhã", null));
    var second = agent.message(owner, message("Mude o pedido", first));
    rejected(409, () ->
      agent.confirm(owner, first.conversationId(), first.action().id())
    );
    var done = agent.confirm(
      owner,
      second.conversationId(),
      second.action().id()
    );
    var retry = agent.confirm(
      owner,
      second.conversationId(),
      second.action().id()
    );
    assertThat(retry).isEqualTo(done);
    assertThat(done.changedGameId()).isNotNull();
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isEqualTo(1);
  }

  @Test
  void presenceUsesExistingRulesOnlyAfterConfirmationAndDoesNotCreateGames() {
    var game = tx
      .execute(s ->
        games.create(
          owner,
          club,
          new CreateGame(
            "Pelada",
            "Gools",
            tomorrow
              .atTime(20, 0)
              .atZone(ZoneId.of("America/Sao_Paulo"))
              .toInstant(),
            2,
            7,
            false,
            null
          )
        )
      )
      .game();
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("ATTEND", game.id(), null)
    );
    var first = agent.message(member, message("Eu vou jogar", null));
    assertThat(games.get(member, game.id()).attendees()).isEmpty();
    agent.confirm(member, first.conversationId(), first.action().id());
    assertThat(games.get(member, game.id()).attendees())
      .extracting(Attendee::id)
      .contains(member);
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("DECLINE", game.id(), null)
    );
    var leave = agent.message(member, message("Não vou mais", null));
    agent.confirm(member, leave.conversationId(), leave.action().id());
    assertThat(games.get(member, game.id()).attendees()).isEmpty();
  }

  @Test
  void permissionsAreRecheckedAfterProviderAndOnConfirmation() {
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("CREATE", null, "Gools")
    );
    rejected(403, () -> agent.message(member, message("Crie para mim", null)));
    var first = agent.message(owner, message("Marque amanhã", null));
    rejected(403, () ->
      agent.confirm(member, first.conversationId(), first.action().id())
    );
    jdbc.update("UPDATE clubs SET owner_id=? WHERE id=?", member, club);
    rejected(403, () ->
      agent.confirm(owner, first.conversationId(), first.action().id())
    );
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
  }

  @Test
  void forgedGroupOrGameAndProviderFailureDoNotExecuteActions() {
    UUID foreign = UUID.randomUUID();
    when(interpreter.decide(anyString(), any())).thenReturn(
      new Decision(
        "LIST",
        foreign,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      )
    );
    rejected(403, () ->
      agent.message(owner, message("Veja outros grupos", null))
    );
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("ATTEND", foreign, null)
    );
    rejected(403, () ->
      agent.message(owner, message("Confirmar outro jogo", null))
    );
    when(interpreter.decide(anyString(), any())).thenThrow(
      new ApiException(503, "Falha")
    );
    rejected(503, () -> agent.message(owner, message("Agenda", null)));
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM games", Integer.class)
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_conversations WHERE lease_id IS NOT NULL",
        Integer.class
      )
    ).isZero();
  }

  @Test
  void busyConversationRejectsOverlappingMessageAndOldConfirmation()
    throws Exception {
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("CREATE", null, "Gools")
    );
    var first = agent.message(owner, message("Marque amanhã", null));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(interpreter.decide(anyString(), any())).thenAnswer(i -> {
      started.countDown();
      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      return decision("CREATE", null, "Arena");
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = executor.submit(() ->
        agent.message(owner, message("Troque para Arena", first))
      );
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      rejected(409, () -> agent.message(owner, message("Mais um", first)));
      rejected(409, () ->
        agent.confirm(owner, first.conversationId(), first.action().id())
      );
      release.countDown();
      assertThat(
        running.get(10, TimeUnit.SECONDS).action().proposal().draft().location()
      ).isEqualTo("Arena");
    } finally {
      release.countDown();
    }
  }

  @Test
  void authenticationCsrfBoundedInputAndAudioUseSessionIdentity()
    throws Exception {
    String path = "/api/assistant/conversations";
    mvc
      .perform(
        post(path)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"Agenda\"}")
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post(path)
          .with(user("agent-owner@example.invalid"))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"Agenda\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post(path)
          .with(user("agent-owner@example.invalid"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"message\":\"" + "a".repeat(1201) + "\"}")
      )
      .andExpect(status().isBadRequest());
    verify(interpreter, never()).decide(anyString(), any());
    when(audio.transcribe(eq(member), any(), any())).thenReturn(
      new AudioTranscriber.Transcript("Vou jogar")
    );
    var file = new org.springframework.mock.web.MockMultipartFile(
      "file",
      "pedido.webm",
      "audio/webm",
      new byte[] { 1 }
    );
    mvc
      .perform(
        multipart("/api/assistant/audio")
          .file(file)
          .with(user("agent-member@example.invalid"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.text").value("Vou jogar"));
    verify(audio).transcribe(eq(member), any(), any());
  }

  @Test
  void expiredConversationCannotReuseContext() {
    when(interpreter.decide(anyString(), any())).thenReturn(
      decision("LIST", null, null)
    );
    var first = agent.message(owner, message("Agenda", null));
    jdbc.update(
      "UPDATE assistant_conversations SET expires_at=now()-interval '1 second' WHERE id=?",
      first.conversationId()
    );
    rejected(410, () -> agent.message(owner, message("Outro pedido", first)));
    agent.cleanup();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM assistant_conversations",
        Integer.class
      )
    ).isZero();
  }

  @Test
  void listAllGroupsAndMembershipChangeDiscardPreviousHistory() {
    var second = groups.create(owner, new CreateClub("Outro grupo", "Teste"));
    when(interpreter.decide(anyString(), any())).thenReturn(
      new Decision(
        "LIST",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      )
    );
    var first = agent.message(owner, message("Meus jogos", null));
    assertThat(first.clubId()).isNull();
    jdbc.update(
      "DELETE FROM members WHERE club_id=? AND player_id=?",
      club,
      owner
    );
    when(interpreter.decide(anyString(), any())).thenAnswer(i -> {
      Context context = i.getArgument(1);
      assertThat(context.history()).isEmpty();
      assertThat(context.groups())
        .extracting(GroupOption::id)
        .containsExactly(second.id());
      return new Decision(
        "LIST",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      );
    });
    agent.message(
      owner,
      new Message(
        "Consultar de novo",
        first.conversationId(),
        first.version(),
        null
      )
    );
  }
}
