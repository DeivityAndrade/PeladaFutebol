package br.com.pelada.whatsapp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations/whatsapp")
public class WhatsAppWorkerController {

  private final WhatsAppIntegration config;
  private final WhatsAppInbox inbox;
  private final WhatsAppOutbox outbox;
  private final WhatsAppAgent agent;
  private final WhatsApp whatsapp;

  public WhatsAppWorkerController(
    WhatsAppIntegration config,
    WhatsAppInbox inbox,
    WhatsAppOutbox outbox,
    WhatsAppAgent agent,
    WhatsApp whatsapp
  ) {
    this.config = config;
    this.inbox = inbox;
    this.outbox = outbox;
    this.agent = agent;
    this.whatsapp = whatsapp;
  }

  private void auth(String token) {
    config.authenticate(token);
    if (!whatsapp.available()) throw new br.com.pelada.domain.ApiException(
      503,
      "Configure o WhatsApp antes de executar a integração."
    );
  }

  public record Reserve(@NotNull UUID leaseId) {}

  public record Action(
    @NotNull UUID leaseId,
    @NotBlank
    @Pattern(regexp = "AUTO|ATTEND|DECLINE|PREPARE|LIST|HELP|CONFIRM")
    String action,
    UUID clubId,
    UUID gameId
  ) {}

  public record Receipt(
    @NotNull UUID leaseId,
    @NotBlank @Pattern(regexp = "ACCEPTED|FAILED|UNKNOWN") String state,
    @Size(max = 256) String providerId,
    @Size(max = 60) String errorCode
  ) {}

  @PostMapping("/inbox/claim")
  public List<WhatsAppInbox.Job> inbox(
    @RequestHeader(name = "Authorization", required = false) String token
  ) {
    auth(token);
    return inbox.claim();
  }

  @PostMapping("/inbox/{id}/action")
  public WhatsAppAgent.Result action(
    @RequestHeader(name = "Authorization", required = false) String token,
    @PathVariable UUID id,
    @Valid @RequestBody Action request
  ) {
    auth(token);
    return agent.act(
      id,
      request.leaseId(),
      new WhatsAppAgent.Command(
        request.action(),
        request.clubId(),
        request.gameId()
      )
    );
  }

  @PostMapping("/inbox/{id}/finish")
  public void finish(
    @RequestHeader(name = "Authorization", required = false) String token,
    @PathVariable UUID id,
    @Valid @RequestBody Reserve request
  ) {
    auth(token);
    inbox.finish(id, request.leaseId());
  }

  @PostMapping("/outbox/claim")
  public List<WhatsAppOutbox.Lease> outbox(
    @RequestHeader(name = "Authorization", required = false) String token
  ) {
    auth(token);
    agent.materialize();
    return outbox.claim();
  }

  @PostMapping("/outbox/{id}/dispatch")
  public WhatsAppOutbox.Dispatch dispatch(
    @RequestHeader(name = "Authorization", required = false) String token,
    @PathVariable UUID id,
    @Valid @RequestBody Reserve request
  ) {
    auth(token);
    return outbox.dispatch(id, request.leaseId());
  }

  @PostMapping("/outbox/{id}/receipt")
  public void receipt(
    @RequestHeader(name = "Authorization", required = false) String token,
    @PathVariable UUID id,
    @Valid @RequestBody Receipt request
  ) {
    auth(token);
    outbox.receipt(
      id,
      request.leaseId(),
      new WhatsAppOutbox.Receipt(
        request.state(),
        request.providerId(),
        request.errorCode()
      )
    );
  }
}
