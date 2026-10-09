package br.com.pelada.whatsapp;

import static br.com.pelada.whatsapp.WhatsAppOutbox.*;

import br.com.pelada.assistant.*;
import br.com.pelada.domain.*;
import br.com.pelada.games.Games;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WhatsAppAgent {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final WhatsAppInbox inbox;
  private final WhatsAppOutbox outbox;
  private final Assistant assistant;
  private final Games games;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  public WhatsAppAgent(
    JdbcTemplate jdbc,
    Clock clock,
    WhatsAppInbox inbox,
    WhatsAppOutbox outbox,
    Assistant assistant,
    Games games,
    TransactionTemplate tx
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.inbox = inbox;
    this.outbox = outbox;
    this.assistant = assistant;
    this.games = games;
    this.tx = tx;
  }

  public record Command(String action, UUID clubId, UUID gameId) {}

  public record Result(boolean needsAgent, String message) {}

  public void materialize() {
    // Existing recurrence rules create the next occurrences even without opening the agenda.
    for (var club : jdbc.queryForList(
      "SELECT c.id,c.owner_id FROM clubs c JOIN whatsapp_group_settings s ON s.club_id=c.id WHERE s.enabled AND NOT c.demo ORDER BY c.id LIMIT 100"
    ))
      games.list((UUID) club.get("owner_id"), (UUID) club.get("id"));
  }

  public Result act(UUID id, UUID lease, Command command) {
    if (
      command.action() == null ||
      !Set.of(
        "AUTO",
        "ATTEND",
        "DECLINE",
        "PREPARE",
        "LIST",
        "HELP",
        "CONFIRM"
      ).contains(command.action())
    ) throw new ApiException(400, "Ação desconhecida.");
    try {
      if (command.action().equals("PREPARE")) return prepare(
        id,
        lease,
        command.clubId()
      );
      return tx.execute(s -> {
        outbox.lock();
        var row = inbox.leased(id, lease);
        if (row.get("result") != null) return read(row);
        String button = (String) row.get("button"),
          text = (String) row.get("text");
        if (button != null && button.startsWith("TD:")) return useButton(
          row,
          button.substring(3)
        );
        if (
          text != null && text.strip().matches("(?i)CONFIRMAR [0-9a-f-]{36}")
        ) return useButton(row, text.strip().substring(10));
        String intent = presence(text);
        if (command.action().equals("AUTO")) {
          if (
            row.get("reply_to") == null &&
            inbox
              .conversation((UUID) row.get("player_id"))
              .get("proposal_id") != null &&
            Set.of("GO", "NO").contains(intent)
          ) return proposalAgain(row);
          if (!intent.equals("UNKNOWN")) return attendance(
            row,
            intent.equals("NO") ? "DECLINE" : "ATTEND",
            null,
            intent
          );
          if (
            normalize(text).matches(
              "(agenda|proximo jogo|proxima pelada|meus jogos|ajuda|menu)"
            )
          ) return list(row);
          return new Result(true, "Pedido precisa de interpretação.");
        }
        return switch (command.action()) {
          case "ATTEND", "DECLINE" -> attendance(
            row,
            command.action(),
            command.gameId(),
            intent
          );
          case "CONFIRM" -> proposalAgain(row);
          case "LIST" -> list(row);
          default -> save(
            row,
            "Posso consultar seus próximos jogos, confirmar ou retirar sua presença. Se você organiza um grupo, também posso preparar uma partida. Para criar, informe grupo, data, horário e local.",
            List.of(),
            null,
            null
          );
        };
      });
    } catch (ApiException ex) {
      // A rejected domain operation rolls back before the separate safe reply is saved.
      return tx.execute(s -> {
        outbox.lock();
        var row = inbox.leased(id, lease);
        if (row.get("result") != null) return read(row);
        return save(row, ex.getMessage(), List.of(), null, null);
      });
    }
  }

  static String normalize(String text) {
    if (text == null) return "";
    return Normalizer.normalize(
      text.toLowerCase(Locale.ROOT),
      Normalizer.Form.NFD
    )
      .replaceAll("\\p{M}", "")
      .replaceAll("[.!?,]", " ")
      .strip()
      .replaceAll("\\s+", " ");
  }

  static String presence(String text) {
    String t = normalize(text);
    if (
      t.matches(
        ".*\\b(talvez|se|depende|acho|nao sei|ainda|pode ser|vou tentar|verei|depois|quando)\\b.*"
      )
    ) return "UNSURE";
    if (
      t.matches(
        "(nao|nao vou|nao vou jogar|nao consigo|nao posso|essa semana nao|nao vai dar|to fora|estou fora|desisto|cancela minha presenca)"
      )
    ) return "NO";
    if (
      t.matches(
        "(sim|vou|vou jogar|eu vou|to dentro|tou dentro|tamo junto|confirmo|confirmar presenca|pode confirmar|pode me confirmar|estou dentro)"
      )
    ) return "GO";
    return "UNKNOWN";
  }

  private Result attendance(
    Map<String, Object> row,
    String action,
    UUID suggested,
    String intent
  ) {
    UUID user = (UUID) row.get("player_id");
    var games = inbox.games(user);
    UUID context = inbox.contextGame(row, games);
    if (games.isEmpty()) return save(
      row,
      "Não há partidas abertas nos seus grupos com WhatsApp ativado. Consulte a agenda do site.",
      List.of(),
      null,
      null
    );
    boolean clear = intent.equals("GO") || intent.equals("NO");
    if (context != null && clear) return applyPresence(
      row,
      context,
      intent.equals("GO") ? "ATTEND" : "DECLINE"
    );
    UUID candidate = suggested;
    if (candidate == null) candidate = context;
    if (candidate != null) {
      UUID selected = candidate;
      if (
        games.stream().noneMatch(g -> selected.equals(g.get("id")))
      ) throw ApiException.forbidden();
      var g = outbox.game(candidate);
      String message =
        (intent.equals("UNSURE")
          ? "Ainda não alterei sua presença. Você vai jogar?\n"
          : "Confirme sua escolha para esta partida:\n") + outbox.describe(g);
      return save(
        row,
        message,
        presenceButtons(row, g),
        (UUID) g.get("club_id"),
        candidate
      );
    }
    String choices = games
      .stream()
      .limit(8)
      .map(g -> outbox.describe(outbox.game((UUID) g.get("id"))))
      .reduce((a, b) -> a + "\n\n" + b)
      .orElse("");
    return save(
      row,
      "Há mais de uma partida possível. Responda com o nome do grupo e a data do jogo:\n\n" +
        choices,
      List.of(),
      null,
      null
    );
  }

  private List<Map<String, Object>> presenceButtons(
    Map<String, Object> row,
    Map<String, Object> g
  ) {
    Instant expiry = instant(g.get("starts_at"));
    Instant verified = instant(row.get("verified_at"));
    UUID user = (UUID) row.get("player_id");
    return List.of(
      outbox.button(
        outbox.action(
          user,
          verified,
          (UUID) g.get("id"),
          null,
          null,
          outbox.revision(g),
          "ATTEND",
          expiry
        ),
        "Vou jogar"
      ),
      outbox.button(
        outbox.action(
          user,
          verified,
          (UUID) g.get("id"),
          null,
          null,
          outbox.revision(g),
          "DECLINE",
          expiry
        ),
        "Não vou"
      )
    );
  }

  private Result applyPresence(
    Map<String, Object> row,
    UUID game,
    String action
  ) {
    UUID user = (UUID) row.get("player_id");
    var g = outbox.game(game);
    var versions = jdbc.queryForList(
      "SELECT updated_at FROM whatsapp_game_revisions WHERE game_id=?",
      game
    );
    if (
      !versions.isEmpty() &&
      instant(row.get("message_at"))
        .plusSeconds(1)
        .isBefore(instant(versions.getFirst().get("updated_at")))
    ) throw ApiException.conflict(
      "Essa resposta é anterior aos detalhes atuais da partida. Consulte a agenda atualizada."
    );
    if (
      !Boolean.TRUE.equals(g.get("enabled")) || !outbox.active(g)
    ) throw ApiException.conflict(
      "Essa partida mudou, foi cancelada ou já começou. Consulte a agenda atualizada."
    );
    if (
      inbox
        .games(user)
        .stream()
        .noneMatch(v -> game.equals(v.get("id")))
    ) throw ApiException.forbidden();
    var prior = jdbc.queryForList(
      "SELECT message_at FROM whatsapp_responses WHERE game_id=? AND player_id=?",
      game,
      user
    );
    if (
      !prior.isEmpty() &&
      !instant(row.get("message_at")).isAfter(
        instant(prior.getFirst().get("message_at"))
      )
    ) return save(
      row,
      "Já recebemos uma resposta mais recente para essa partida. Sua presença foi mantida.",
      List.of(),
      (UUID) g.get("club_id"),
      game
    );
    var detail = action.equals("ATTEND")
      ? games.attend(user, game)
      : games.leave(user, game);
    jdbc.update(
      "INSERT INTO whatsapp_responses VALUES(?,?,?,?) ON CONFLICT(game_id,player_id) DO UPDATE SET response=EXCLUDED.response,message_at=EXCLUDED.message_at",
      game,
      user,
      action.equals("ATTEND") ? "GO" : "NO",
      row.get("message_at")
    );
    String status = detail
      .attendees()
      .stream()
      .filter(p -> p.id().equals(user))
      .map(p -> p.status())
      .findFirst()
      .orElse("NONE");
    String answer = action.equals("DECLINE")
      ? "Registrado: você não vai jogar. Sua presença foi retirada."
      : status.equals("WAITING")
        ? "A partida está completa. Você entrou na fila de espera."
        : "Sua presença está confirmada!";
    return save(
      row,
      answer + "\n" + outbox.describe(g),
      List.of(),
      (UUID) g.get("club_id"),
      game
    );
  }

  private Result useButton(Map<String, Object> row, String value) {
    UUID token;
    try {
      token = UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
        400,
        "Resposta inválida. Abra a mensagem mais recente."
      );
    }
    var actions = jdbc.queryForList(
      "SELECT * FROM whatsapp_actions WHERE id=? AND player_id=? AND verified_at=? AND expires_at>?",
      token,
      row.get("player_id"),
      row.get("verified_at"),
      time(clock.instant())
    );
    if (actions.isEmpty()) throw ApiException.conflict(
      "Essa opção expirou. Consulte a agenda ou peça um novo resumo."
    );
    var a = actions.getFirst();
    String action = (String) a.get("action");
    if (action.equals("ATTEND") || action.equals("DECLINE")) {
      UUID game = (UUID) a.get("game_id");
      if (
        !Objects.equals(a.get("revision"), outbox.revision(outbox.game(game)))
      ) throw ApiException.conflict(
        "Os detalhes da partida mudaram. Peça a agenda atualizada antes de responder."
      );
      return applyPresence(row, game, action);
    }
    var c = inbox.conversation((UUID) row.get("player_id"));
    if (
      !Objects.equals(a.get("proposal_id"), c.get("proposal_id")) ||
      !Objects.equals(a.get("proposal_version"), c.get("proposal_version"))
    ) throw ApiException.conflict(
      "A proposta mudou. Confirme o resumo mais recente."
    );
    UUID proposal = (UUID) a.get("proposal_id");
    if (action.equals("DISCARD")) {
      jdbc.update(
        "UPDATE whatsapp_conversations SET proposal_id=NULL,proposal_version=NULL WHERE player_id=?",
        row.get("player_id")
      );
      return save(
        row,
        "Proposta descartada. Nenhuma partida foi criada.",
        List.of(),
        null,
        null
      );
    }
    if (!action.equals("CREATE")) throw ApiException.forbidden();
    if (
      inbox
        .groups((UUID) row.get("player_id"))
        .stream()
        .noneMatch(
          g ->
            Objects.equals(g.get("id"), c.get("club_id")) &&
            Boolean.TRUE.equals(g.get("owner"))
        )
    ) throw ApiException.forbidden();
    var result = assistant.confirm(
      (UUID) row.get("player_id"),
      proposal,
      new AssistantContracts.Confirmation(
        ((Number) a.get("proposal_version")).longValue()
      )
    );
    jdbc.update(
      "UPDATE whatsapp_conversations SET proposal_id=NULL,proposal_version=NULL WHERE player_id=?",
      row.get("player_id")
    );
    return save(
      row,
      "Partida criada!\n" + outbox.describe(outbox.game(result.game().id())),
      List.of(),
      result.club().id(),
      result.game().id()
    );
  }

  private record Preparation(
    UUID user,
    Instant verified,
    String text,
    UUID club,
    UUID previous
  ) {}

  private Result prepare(UUID id, UUID lease, UUID club) {
    if (club == null) return tx.execute(s -> {
      outbox.lock();
      var row = inbox.leased(id, lease);
      if (row.get("result") != null) return read(row);
      return save(
        row,
        "Informe em qual grupo deseja marcar a partida.",
        List.of(),
        null,
        null
      );
    });
    var prepared = tx.execute(s -> {
      outbox.lock();
      var row = inbox.leased(id, lease);
      if (row.get("result") != null) return null;
      UUID user = (UUID) row.get("player_id");
      if (
        inbox
          .groups(user)
          .stream()
          .noneMatch(
            g -> club.equals(g.get("id")) && Boolean.TRUE.equals(g.get("owner"))
          )
      ) throw ApiException.forbidden();
      var c = inbox.conversation(user);
      UUID previous = club.equals(c.get("club_id"))
        ? (UUID) c.get("proposal_id")
        : null;
      return new Preparation(
        user,
        instant(row.get("verified_at")),
        (String) row.get("text"),
        club,
        previous
      );
    });
    if (prepared == null) return tx.execute(s -> read(inbox.leased(id, lease)));
    if (
      prepared.text() == null || prepared.text().isBlank()
    ) throw new ApiException(400, "Descreva a partida por texto.");
    var proposal = assistant.propose(
      prepared.user(),
      prepared.club(),
      new AssistantContracts.Request(prepared.text(), prepared.previous())
    );
    return tx.execute(s -> {
      outbox.lock();
      var row = inbox.leased(id, lease);
      if (row.get("result") != null) return read(row);
      if (
        inbox
          .groups(prepared.user())
          .stream()
          .noneMatch(
            g ->
              prepared.club().equals(g.get("id")) &&
              Boolean.TRUE.equals(g.get("owner"))
          )
      ) throw ApiException.forbidden();
      jdbc.update(
        "UPDATE whatsapp_conversations SET club_id=?,proposal_id=?,proposal_version=?,expires_at=? WHERE player_id=?",
        club,
        proposal.id(),
        proposal.version(),
        time(proposal.expiresAt()),
        prepared.user()
      );
      return summarize(row, proposal, club);
    });
  }

  private Result proposalAgain(Map<String, Object> row) {
    var c = inbox.conversation((UUID) row.get("player_id"));
    UUID proposal = (UUID) c.get("proposal_id");
    if (proposal == null) return save(
      row,
      "Não há proposta pendente. Descreva a partida que deseja criar.",
      List.of(),
      null,
      null
    );
    return summarize(
      row,
      assistant.inspect((UUID) row.get("player_id"), proposal),
      (UUID) c.get("club_id")
    );
  }

  private Result summarize(
    Map<String, Object> row,
    AssistantContracts.Proposal p,
    UUID club
  ) {
    if (!p.ready()) return save(
      row,
      "Preciso completar os detalhes:\n" + String.join("\n", p.questions()),
      List.of(),
      club,
      null
    );
    var d = p.draft();
    String name = jdbc.queryForObject(
      "SELECT name FROM clubs WHERE id=?",
      String.class,
      club
    );
    String summary =
      "Revise antes de criar:\n" +
      name +
      " — " +
      d.title() +
      "\n" +
      d.date() +
      " às " +
      d.time() +
      " (" +
      p.timeZone() +
      ")\n" +
      d.location() +
      "\n" +
      d.teamCount() +
      " times de " +
      d.teamSize() +
      " jogadores";
    if (Boolean.TRUE.equals(d.recurring())) summary +=
      "\nSemanal" +
      (d.recurrenceEndsOn() == null ? "" : " até " + d.recurrenceEndsOn());
    summary += d.chargeOccasional()
      ? "\nCobrança avulsa: R$ " +
        String.format(
          Locale.forLanguageTag("pt-BR"),
          "%.2f",
          d.occasionalAmountCents() / 100.0
        ) +
        " no início da partida."
      : "\nSem cobrança avulsa.";
    UUID create = outbox.action(
      (UUID) row.get("player_id"),
      instant(row.get("verified_at")),
      null,
      p.id(),
      p.version(),
      null,
      "CREATE",
      p.expiresAt()
    );
    UUID discard = outbox.action(
      (UUID) row.get("player_id"),
      instant(row.get("verified_at")),
      null,
      p.id(),
      p.version(),
      null,
      "DISCARD",
      p.expiresAt()
    );
    summary +=
      "\nToque em Criar partida ou responda CONFIRMAR " +
      create +
      ". Para corrigir, escreva o que deve mudar.";
    return save(
      row,
      summary,
      List.of(
        outbox.button(create, "Criar partida"),
        outbox.button(discard, "Descartar")
      ),
      club,
      null
    );
  }

  private Result list(Map<String, Object> row) {
    var games = inbox.games((UUID) row.get("player_id"));
    String result = games
      .stream()
      .limit(8)
      .map(
        g ->
          outbox.describe(outbox.game((UUID) g.get("id"))) +
          "\nSua presença: " +
          switch ((String) g.get("attendance")) {
            case "CONFIRMED" -> "confirmada";
            case "WAITING" -> "na fila";
            default -> "não confirmada";
          }
      )
      .reduce((a, b) -> a + "\n\n" + b)
      .orElse("Não há partidas abertas nos seus grupos com WhatsApp ativado.");
    return save(row, result, List.of(), null, null);
  }

  private Result save(
    Map<String, Object> row,
    String message,
    List<Map<String, Object>> buttons,
    UUID club,
    UUID game
  ) {
    if (message.length() > 3500) message = message.substring(0, 3500);
    var result = new Result(false, message);
    outbox.reply(
      (UUID) row.get("id"),
      (UUID) row.get("player_id"),
      instant(row.get("verified_at")),
      message,
      buttons,
      club,
      game
    );
    jdbc.update(
      "UPDATE whatsapp_inbox SET result=? WHERE id=?",
      json.writeValueAsString(result),
      row.get("id")
    );
    return result;
  }

  private Result read(Map<String, Object> row) {
    return json.readValue((String) row.get("result"), Result.class);
  }
}
