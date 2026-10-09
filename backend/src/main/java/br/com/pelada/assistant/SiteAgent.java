package br.com.pelada.assistant;

import static br.com.pelada.assistant.AgentContracts.*;

import br.com.pelada.domain.*;
import br.com.pelada.games.Games;
import br.com.pelada.groups.Groups;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SiteAgent {

  private final Groups groups;
  private final Games games;
  private final Assistant assistant;
  private final AgentInterpreter interpreter;
  private final AudioTranscriber audio;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  public SiteAgent(
    Groups groups,
    Games games,
    Assistant assistant,
    AgentInterpreter interpreter,
    AudioTranscriber audio,
    JdbcTemplate jdbc,
    TransactionTemplate tx,
    Clock clock
  ) {
    this.groups = groups;
    this.games = games;
    this.assistant = assistant;
    this.interpreter = interpreter;
    this.audio = audio;
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
  }

  public Availability availability(UUID user) {
    boolean member = groups
      .list(user)
      .stream()
      .anyMatch(c -> !c.demo());
    return new Availability(
      member && interpreter.available(),
      member && audio.available()
    );
  }

  private record Work(
    UUID id,
    UUID lease,
    long version,
    Context context,
    UUID previousId
  ) {}

  public Reply message(UUID user, Message input) {
    if (!interpreter.available()) throw new ApiException(
      503,
      "O agente ainda não está disponível. Use a agenda."
    );
    Work work = tx.execute(s -> {
      var clubs = groups
        .list(user)
        .stream()
        .filter(c -> !c.demo())
        .limit(25)
        .toList();
      if (clubs.isEmpty()) throw ApiException.forbidden();
      if (
        input.selectedClubId() != null &&
        clubs.stream().noneMatch(c -> c.id().equals(input.selectedClubId()))
      ) throw ApiException.forbidden();
      UUID id = input.conversationId();
      if (id == null) {
        id = UUID.randomUUID();
        jdbc.update(
          "INSERT INTO assistant_conversations(id,author_id,club_id,expires_at) VALUES (?,?,?,?)",
          id,
          user,
          input.selectedClubId(),
          expiry()
        );
      }
      var row = owned(user, id);
      if ((long) row.get("revision") != input.version()) throw new ApiException(
        409,
        "A conversa mudou. Inicie um novo pedido."
      );
      if (
        row.get("lease_until") != null &&
        ((Timestamp) row.get("lease_until"))
          .toInstant()
          .isAfter(clock.instant())
      ) throw new ApiException(
        409,
        "Estou atendendo seu pedido anterior. Aguarde."
      );
      UUID selected =
        input.selectedClubId() != null
          ? input.selectedClubId()
          : (UUID) row.get("club_id");
      String scope = json.writeValueAsString(
        clubs
          .stream()
          .map(c -> c.id().toString() + ":" + c.ownerId().equals(user))
          .sorted()
          .toList()
      );
      UUID selectionToCheck = selected;
      if (
        selected != null &&
        clubs.stream().noneMatch(c -> c.id().equals(selectionToCheck))
      ) selected = null;
      boolean changed =
        input.selectedClubId() != null &&
        !Objects.equals(selected, row.get("club_id"));
      changed = changed || !scope.equals(row.get("scope"));
      UUID prior = changed ? null : (UUID) row.get("proposal_id");
      AssistantContracts.Draft draft = null;
      if (prior != null) {
        try {
          draft = assistant.inspect(user, prior).draft();
          draft = new AssistantContracts.Draft(
            draft.title(),
            draft.location(),
            draft.date(),
            draft.time(),
            draft.teamCount(),
            draft.teamSize(),
            draft.recurring(),
            draft.recurrenceEndsOn(),
            false,
            null
          );
        } catch (ApiException e) {
          if (e.status == 410 || e.status == 409) prior = null;
          else throw e;
        }
      }
      List<Turn> history = changed ? List.of() : history(row);
      var contextGroups = new ArrayList<GroupOption>();
      for (var c : clubs) {
        var options = games
          .list(user, c.id())
          .stream()
          .filter(g -> !g.cancelled() && g.startsAt().isAfter(clock.instant()))
          .limit(8)
          .map(g ->
            new GameOption(
              g.id(),
              c.id(),
              g.title(),
              g.location(),
              g.startsAt(),
              c.timeZone()
            )
          )
          .toList();
        contextGroups.add(
          new GroupOption(
            c.id(),
            c.name(),
            c.timeZone(),
            c.ownerId().equals(user),
            options
          )
        );
      }
      assistant.takeAttempt(user);
      jdbc.update(
        "UPDATE assistant_conversations SET scope=?,proposal_id=? WHERE id=?",
        scope,
        prior,
        id
      );
      UUID lease = UUID.randomUUID();
      jdbc.update(
        "UPDATE assistant_conversations SET lease_id=?,lease_until=?,pending=NULL,expires_at=? WHERE id=?",
        lease,
        Timestamp.from(clock.instant().plusSeconds(90)),
        expiry(),
        id
      );
      return new Work(
        id,
        lease,
        input.version(),
        new Context(clock.instant(), selected, contextGroups, draft, history),
        prior
      );
    });
    try {
      // No domain locks or database transaction are held while the model responds.
      Decision decision = interpreter.decide(
        input.message().strip(),
        work.context()
      );
      return tx.execute(s -> {
        var row = owned(user, work.id());
        if (!work.lease().equals(row.get("lease_id"))) throw new ApiException(
          409,
          "Esse pedido foi substituído. Envie novamente."
        );
        Reply response = apply(user, work, decision);
        var turns = new ArrayList<>(work.context().history());
        turns.add(new Turn("user", input.message().strip()));
        turns.add(new Turn("assistant", response.message()));
        while (turns.size() > 8) turns.removeFirst();
        jdbc.update(
          "UPDATE assistant_conversations SET revision=?,club_id=?,proposal_id=?,history=?,pending=?,lease_id=NULL,lease_until=NULL,expires_at=? WHERE id=?",
          response.version(),
          response.clubId(),
          "CREATE".equals(decision.action())
            ? jdbc.queryForObject(
                "SELECT proposal_id FROM assistant_conversations WHERE id=?",
                UUID.class,
                work.id()
              )
            : null,
          json.writeValueAsString(turns),
          response.action() == null
            ? null
            : json.writeValueAsString(response.action()),
          expiry(),
          work.id()
        );
        return response;
      });
    } catch (RuntimeException e) {
      tx.executeWithoutResult(s ->
        jdbc.update(
          "UPDATE assistant_conversations SET lease_id=NULL,lease_until=NULL WHERE id=? AND lease_id=?",
          work.id(),
          work.lease()
        )
      );
      throw e;
    }
  }

  private Reply apply(UUID user, Work work, Decision d) {
    var context = work.context();
    UUID clubId =
      d.clubId() != null
        ? d.clubId()
        : "LIST".equals(d.action())
          ? null
          : context.selectedClubId();
    if (clubId == null && context.groups().size() == 1) clubId = context
      .groups()
      .getFirst()
      .id();
    UUID target = clubId;
    var group =
      target == null
        ? null
        : context
            .groups()
            .stream()
            .filter(c -> c.id().equals(target))
            .findFirst()
            .orElseThrow(ApiException::forbidden);
    if (group != null) {
      var current = groups.requireMember(user, group.id());
      if (!current.timeZone.equals(group.timeZone())) throw new ApiException(
        409,
        "O fuso do grupo mudou. Envie novamente."
      );
      if (
        context
          .groups()
          .stream()
          .filter(c -> c.name().equalsIgnoreCase(group.name()))
          .count() > 1 &&
        !group.id().equals(context.selectedClubId())
      ) return reply(
        work,
        "Há grupos com o mesmo nome. Escolha o grupo no campo acima.",
        null,
        List.of(),
        null
      );
    }
    if ("LIST".equals(d.action())) {
      var options = context
        .groups()
        .stream()
        .filter(c -> group == null || c.id().equals(group.id()))
        .flatMap(c -> c.games().stream())
        .sorted(Comparator.comparing(GameOption::startsAt))
        .limit(30)
        .toList();
      // Recheck access to every group whose information will be shown.
      options
        .stream()
        .map(GameOption::clubId)
        .distinct()
        .forEach(id -> groups.requireMember(user, id));
      return reply(
        work,
        options.isEmpty()
          ? "Você não tem próximos jogos neste contexto."
          : "Estes são os próximos jogos. Você pode pedir para confirmar sua presença em um deles.",
        clubId,
        options,
        null
      );
    }
    if ("HELP".equals(d.action())) return reply(
      work,
      d.question() == null
        ? "Posso consultar jogos, preparar uma partida ou confirmar e retirar sua presença. O que você quer fazer?"
        : shortQuestion(d.question()),
      clubId,
      List.of(),
      null
    );
    if (group == null) return reply(
      work,
      "Em qual grupo? Diga o nome ou escolha no campo acima.",
      null,
      List.of(),
      null
    );
    if ("CREATE".equals(d.action())) {
      groups.requireOwner(user, group.id());
      UUID prior = Objects.equals(group.id(), context.selectedClubId())
        ? work.previousId()
        : null;
      var p = assistant.fromAgent(user, group.id(), prior, d.interpretation());
      // Keep incomplete proposals as server-side conversation context too.
      jdbc.update(
        "UPDATE assistant_conversations SET club_id=?,proposal_id=? WHERE id=?",
        group.id(),
        p.id(),
        work.id()
      );
      if (!p.ready()) return reply(
        work,
        "Para preparar o jogo, preciso saber: " +
          String.join(" ", p.questions()),
        group.id(),
        List.of(),
        null
      );
      var action = new Action(
        UUID.randomUUID(),
        "CREATE",
        group.id(),
        null,
        p.id(),
        "Criar jogo",
        "Vou criar este jogo em " +
          group.name() +
          ". Convites seguem a automação do grupo e a autorização dos participantes.",
        p
      );
      return reply(
        work,
        "Deixei a partida pronta. Confira o resumo e confirme para eu criar. Você também pode pedir ajustes por áudio ou mensagem.",
        group.id(),
        List.of(),
        action
      );
    }
    if (
      !Set.of("ATTEND", "DECLINE").contains(d.action())
    ) throw new ApiException(400, "Ação desconhecida.");
    var options = group.games();
    UUID gameId = d.gameId();
    if (gameId == null && options.size() == 1) gameId = options.getFirst().id();
    if (gameId == null) return reply(
      work,
      options.isEmpty()
        ? "Não há próximos jogos neste grupo."
        : "Em qual partida? Diga o título ou a data. Ainda não alterei sua presença.",
      group.id(),
      options,
      null
    );
    UUID chosen = gameId;
    var option = options
      .stream()
      .filter(g -> g.id().equals(chosen))
      .findFirst()
      .orElseThrow(ApiException::forbidden);
    var actual = games.get(user, chosen).game();
    if (
      !actual.clubId().equals(group.id()) ||
      actual.cancelled() ||
      !actual.startsAt().isAfter(clock.instant())
    ) throw new ApiException(
      409,
      "Este jogo não está mais disponível. Consulte a agenda."
    );
    String label = "ATTEND".equals(d.action())
      ? "Confirmar minha presença"
      : "Retirar minha presença";
    String summary =
      option.title() +
      " · " +
      group.name() +
      " · " +
      option
        .startsAt()
        .atZone(ZoneId.of(group.timeZone()))
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")) +
      " · " +
      option.location() +
      " · " +
      group.timeZone();
    return reply(
      work,
      "Confira a partida antes de " +
        ("ATTEND".equals(d.action()) ? "confirmar" : "retirar") +
        " sua presença.",
      group.id(),
      List.of(),
      new Action(
        UUID.randomUUID(),
        d.action(),
        group.id(),
        chosen,
        null,
        label,
        summary,
        null
      )
    );
  }

  private Reply reply(
    Work work,
    String text,
    UUID club,
    List<GameOption> games,
    Action action
  ) {
    return new Reply(
      work.id(),
      work.version() + 1,
      text,
      club,
      games,
      action,
      null
    );
  }

  private String shortQuestion(String text) {
    return text.substring(0, Math.min(text.length(), 400));
  }

  public Reply confirm(UUID user, UUID conversation, UUID actionId) {
    return tx.execute(s -> {
      var row = owned(user, conversation);
      if (actionId.equals(row.get("completed_id"))) {
        var cached = json.readValue(
          (String) row.get("completed_result"),
          Reply.class
        );
        groups.requireMember(user, cached.clubId());
        return cached;
      }
      if (
        row.get("pending") == null || row.get("lease_id") != null
      ) throw new ApiException(
        409,
        "Esse pedido mudou. Confira o novo resumo."
      );
      var action = json.readValue((String) row.get("pending"), Action.class);
      if (!actionId.equals(action.id())) throw new ApiException(
        409,
        "Esse pedido mudou. Confira o novo resumo."
      );
      groups.requireMember(user, action.clubId());
      var detail = switch (action.type()) {
        case "CREATE" -> assistant.confirm(
          user,
          action.proposalId(),
          new AssistantContracts.Confirmation(action.proposal().version())
        );
        case "ATTEND" -> games.attend(user, action.gameId());
        case "DECLINE" -> games.leave(user, action.gameId());
        default -> throw new ApiException(400, "Ação desconhecida.");
      };
      String result = switch (action.type()) {
        case "CREATE" -> "Jogo criado. Os convites seguem a automação do grupo; não confirmei sua presença automaticamente.";
        case "DECLINE" -> "Sua presença foi retirada.";
        default -> detail
          .attendees()
          .stream()
          .anyMatch(a -> a.id().equals(user) && "WAITING".equals(a.status()))
          ? "O jogo está cheio. Você entrou na lista de espera."
          : "Sua presença foi confirmada.";
      };
      long version = (long) row.get("revision") + 1;
      var response = new Reply(
        conversation,
        version,
        result,
        action.clubId(),
        List.of(),
        null,
        detail.game().id()
      );
      var history = new ArrayList<>(history(row));
      history.add(new Turn("assistant", result));
      while (history.size() > 8) history.removeFirst();
      jdbc.update(
        "UPDATE assistant_conversations SET revision=?,pending=NULL,proposal_id=NULL,completed_id=?,completed_result=?,history=?,expires_at=? WHERE id=?",
        version,
        actionId,
        json.writeValueAsString(response),
        json.writeValueAsString(history),
        expiry(),
        conversation
      );
      return response;
    });
  }

  private List<Turn> history(Map<String, Object> row) {
    return Arrays.asList(
      json.readValue((String) row.get("history"), Turn[].class)
    );
  }

  private Map<String, Object> owned(UUID user, UUID id) {
    var rows = jdbc.queryForList(
      "SELECT * FROM assistant_conversations WHERE id=? FOR UPDATE",
      id
    );
    if (rows.isEmpty()) throw ApiException.notFound();
    var row = rows.getFirst();
    if (!user.equals(row.get("author_id"))) throw ApiException.forbidden();
    if (
      !((Timestamp) row.get("expires_at")).toInstant().isAfter(clock.instant())
    ) throw new ApiException(410, "A conversa expirou. Inicie um novo pedido.");
    return row;
  }

  private Timestamp expiry() {
    return Timestamp.from(clock.instant().plusSeconds(1800));
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void cleanup() {
    jdbc.update(
      "DELETE FROM assistant_conversations WHERE expires_at < ?",
      Timestamp.from(clock.instant())
    );
  }
}
