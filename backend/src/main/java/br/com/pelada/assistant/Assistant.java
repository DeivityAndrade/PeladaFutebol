package br.com.pelada.assistant;

import static br.com.pelada.assistant.AssistantContracts.*;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.domain.*;
import br.com.pelada.domain.Domain.Club;
import br.com.pelada.games.Games;
import br.com.pelada.groups.Groups;
import jakarta.validation.Validator;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Assistant {

  private final Store store;
  private final Groups groups;
  private final Games games;
  private final Clock clock;
  private final AiInterpreter interpreter;
  private final Validator validator;
  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final int dailyLimit;
  private final JsonMapper json = JsonMapper.builder()
    .findAndAddModules()
    .build();

  public Assistant(
    Store store,
    Groups groups,
    Games games,
    Clock clock,
    AiInterpreter interpreter,
    Validator validator,
    TransactionTemplate tx,
    JdbcTemplate jdbc,
    @Value("${app.assistant.daily-limit:20}") int dailyLimit
  ) {
    this.store = store;
    this.groups = groups;
    this.games = games;
    this.clock = clock;
    this.interpreter = interpreter;
    this.validator = validator;
    this.tx = tx;
    this.jdbc = jdbc;
    this.dailyLimit = Math.max(1, dailyLimit);
  }

  public Availability availability(UUID user, UUID clubId) {
    return tx.execute(status -> {
      groups.requireOwner(user, clubId);
      return new Availability(interpreter.available());
    });
  }

  public Proposal propose(UUID user, UUID clubId, Request request) {
    var prepared = tx.execute(status -> {
      Club club = groups.requireOwner(user, clubId);
      if (!interpreter.available()) throw new ApiException(
        503,
        "O assistente ainda não está disponível. Use o formulário para marcar a pelada."
      );
      Draft previous = null;
      long previousVersion = 0;
      if (request.previousProposalId() != null) {
        var prior = owned(user, request.previousProposalId());
        if (!prior.clubId.equals(clubId)) throw ApiException.forbidden();
        usable(prior);
        previous = read(prior);
        previousVersion = prior.revision;
      }
      Instant now = clock.instant();
      // Count attempts before calling the provider; a failure still consumes the quota.
      take(
        "user:" + user + ":" + now.getEpochSecond() / 3600,
        10,
        now.plusSeconds(7200)
      );
      take(
        "global:" + now.getEpochSecond() / 86400,
        dailyLimit,
        now.plusSeconds(172800)
      );
      jdbc.update(
        "DELETE FROM assistant_request_limits WHERE expires_at < ?",
        java.sql.Timestamp.from(now)
      );
      jdbc.update(
        "DELETE FROM assistant_proposals WHERE expires_at < ?",
        java.sql.Timestamp.from(now.minusSeconds(86400))
      );
      return new Prepared(
        new Context(club.name, club.timeZone, now, previous),
        previousVersion
      );
    });
    Context context = prepared.context();
    // No database transaction or domain lock stays open while the external model responds.
    Interpretation result = interpreter.interpret(
      request.message().strip(),
      context
    );
    return tx.execute(status -> {
      Club club = groups.requireOwner(user, clubId);
      if (!club.timeZone.equals(context.timeZone())) throw new ApiException(
        409,
        "O fuso do grupo mudou. Faça um novo pedido."
      );
      if (request.previousProposalId() != null) {
        var prior = owned(user, request.previousProposalId());
        usable(prior);
        version(prior, prepared.previousVersion());
        prior.expiresAt = clock.instant();
      }
      Draft old = context.previous();
      Draft draft = new Draft(
        result.title() == null
          ? old == null
            ? "Pelada da semana"
            : old.title()
          : result.title().strip(),
        result.location() == null
          ? old == null
            ? null
            : old.location()
          : result.location().strip(),
        result.date() == null && old != null ? old.date() : result.date(),
        result.time() == null && old != null ? old.time() : result.time(),
        result.teamCount() == null
          ? old == null
            ? 2
            : old.teamCount()
          : result.teamCount(),
        result.teamSize() == null
          ? old == null
            ? 7
            : old.teamSize()
          : result.teamSize(),
        result.recurring() == null && old != null
          ? old.recurring()
          : Boolean.TRUE.equals(result.recurring()),
        Boolean.FALSE.equals(result.recurring())
          ? null
          : result.recurrenceEndsOn() == null && old != null
            ? old.recurrenceEndsOn()
            : result.recurrenceEndsOn(),
        old == null
          ? club.occasionalAmountCents != null && club.occasionalAmountCents > 0
          : old.chargeOccasional(),
        old == null ? club.occasionalAmountCents : old.occasionalAmountCents()
      );
      validate(draft);
      var proposal = new AssistantProposal(
        user,
        clubId,
        club.timeZone,
        clock.instant()
      );
      proposal.draft = json.writeValueAsString(draft);
      proposal.question =
        result.question() == null ? null : result.question().strip();
      if (
        proposal.question != null && proposal.question.length() > 400
      ) proposal.question = proposal.question.substring(0, 400);
      store.save(proposal);
      return view(proposal);
    });
  }

  private record Prepared(Context context, long previousVersion) {}

  private void take(String bucket, int maximum, Instant expiry) {
    var counts = jdbc.queryForList(
      "INSERT INTO assistant_request_limits(bucket,requests,expires_at) VALUES (?,1,?) ON CONFLICT (bucket) DO UPDATE SET requests=assistant_request_limits.requests+1 WHERE assistant_request_limits.requests < ? RETURNING requests",
      Integer.class,
      bucket,
      java.sql.Timestamp.from(expiry),
      maximum
    );
    if (counts.isEmpty()) throw new ApiException(
      429,
      "O limite de pedidos do assistente foi atingido. Use o formulário ou tente mais tarde."
    );
  }

  public Proposal revise(UUID user, UUID id, Revision input) {
    return tx.execute(status -> {
      var proposal = owned(user, id);
      usable(proposal);
      version(proposal, input.version());
      validate(input.draft());
      proposal.draft = json.writeValueAsString(input.draft());
      proposal.question = null;
      proposal.revision++;
      return view(proposal);
    });
  }

  public GameDetail confirm(UUID user, UUID id, Confirmation input) {
    return tx.execute(status -> {
      var proposal = owned(user, id);
      // Return the same game even when the client retries after a lost response.
      if (proposal.gameId != null) return games.get(user, proposal.gameId);
      usable(proposal);
      version(proposal, input.version());
      var view = view(proposal);
      if (!view.ready()) throw new ApiException(
        400,
        "Complete e revise os detalhes antes de criar o jogo."
      );
      Draft d = read(proposal);
      var startsAt = startsAt(d, proposal.timeZone);
      var inputGame = new CreateGame(
        d.title().strip(),
        d.location().strip(),
        startsAt,
        d.teamCount(),
        d.teamSize(),
        d.chargeOccasional(),
        d.occasionalAmountCents(),
        Boolean.TRUE.equals(d.recurring()),
        d.recurrenceEndsOn()
      );
      if (!validator.validate(inputGame).isEmpty()) throw new ApiException(
        400,
        "Confira os detalhes da pelada."
      );
      var game = games.create(user, proposal.clubId, inputGame);
      proposal.gameId = game.game().id();
      return game;
    });
  }

  private AssistantProposal owned(UUID user, UUID id) {
    // Lock first so a competing revision/confirmation sees the committed latest version.
    var p = store.lock(AssistantProposal.class, id);
    if (!p.authorId.equals(user)) throw ApiException.forbidden();
    var club = groups.requireOwner(user, p.clubId);
    if (!club.timeZone.equals(p.timeZone)) throw new ApiException(
      409,
      "O fuso do grupo mudou. Faça um novo pedido."
    );
    return p;
  }

  private void usable(AssistantProposal p) {
    if (p.gameId != null) throw new ApiException(
      409,
      "Essa proposta já criou uma pelada."
    );
    if (!p.expiresAt.isAfter(clock.instant())) throw new ApiException(
      410,
      "Essa proposta expirou. Faça um novo pedido."
    );
  }

  private void version(AssistantProposal p, long version) {
    if (p.revision != version) throw new ApiException(
      409,
      "Os detalhes mudaram. Revise a proposta novamente."
    );
  }

  private Draft read(AssistantProposal p) {
    return json.readValue(p.draft, Draft.class);
  }

  private void validate(Draft d) {
    if (!validator.validate(d).isEmpty()) throw new ApiException(
      400,
      "Confira os campos: são 2 a 6 times, com 5 a 12 jogadores por time."
    );
  }

  private Instant startsAt(Draft d, String zoneId) {
    LocalDateTime local = d.date().atTime(d.time());
    var zone = ZoneId.of(zoneId);
    var offsets = zone.getRules().getValidOffsets(local);
    if (offsets.size() != 1) throw new ApiException(
      400,
      "Esse horário é inexistente ou ambíguo no fuso do grupo. Escolha outro horário."
    );
    return local.toInstant(offsets.getFirst());
  }

  private Proposal view(AssistantProposal p) {
    Draft d = read(p);
    var questions = new ArrayList<String>();
    if (p.question != null && !p.question.isBlank()) questions.add(p.question);
    if (d.title() == null || d.title().isBlank()) questions.add(
      "Qual é o nome da pelada?"
    );
    if (d.location() == null || d.location().isBlank()) questions.add(
      "Em qual local será o jogo?"
    );
    if (d.date() == null) questions.add("Em qual data será o jogo?");
    if (d.time() == null) questions.add("Qual será o horário?");
    if (d.teamCount() == null || d.teamSize() == null) questions.add(
      "Informe a quantidade de times e jogadores por time."
    );
    if (d.date() != null && d.time() != null) {
      try {
        if (!startsAt(d, p.timeZone).isAfter(clock.instant())) questions.add(
          "Escolha uma data e um horário no futuro."
        );
      } catch (ApiException ex) {
        questions.add(ex.getMessage());
      }
    }
    if (
      d.recurrenceEndsOn() != null &&
      (!Boolean.TRUE.equals(d.recurring()) ||
        (d.date() != null && d.recurrenceEndsOn().isBefore(d.date())))
    ) questions.add("Confira o término da recorrência semanal.");
    if (
      d.chargeOccasional() &&
      (d.occasionalAmountCents() == null || d.occasionalAmountCents() <= 0)
    ) questions.add("Informe o valor avulso ou desative a cobrança.");
    return new Proposal(
      p.id,
      p.revision,
      p.timeZone,
      p.expiresAt,
      d,
      questions,
      questions.isEmpty()
    );
  }
}
