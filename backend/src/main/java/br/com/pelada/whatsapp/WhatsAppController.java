package br.com.pelada.whatsapp;

import br.com.pelada.auth.Accounts;
import br.com.pelada.domain.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

  private final WhatsApp whatsapp;
  private final Accounts accounts;
  private final ObjectMapper mapper;
  private final WhatsAppOutbox outbox;

  public WhatsAppController(
    WhatsApp whatsapp,
    Accounts accounts,
    ObjectMapper mapper,
    WhatsAppOutbox outbox
  ) {
    this.whatsapp = whatsapp;
    this.accounts = accounts;
    this.mapper = mapper;
    this.outbox = outbox;
  }

  @GetMapping
  public WhatsApp.Status status(Authentication auth) {
    return whatsapp.status(accounts.current(auth).id);
  }

  @PostMapping("/verification")
  public WhatsApp.Challenge begin(Authentication auth) {
    return whatsapp.begin(accounts.current(auth).id);
  }

  public record Confirmation(@NotNull UUID challengeId) {}

  @PostMapping("/verification/confirm")
  public WhatsApp.Status confirm(
    Authentication auth,
    @Valid @RequestBody Confirmation input
  ) {
    return whatsapp.confirm(accounts.current(auth).id, input.challengeId());
  }

  @DeleteMapping
  public WhatsApp.Status disconnect(Authentication auth) {
    return whatsapp.disconnect(accounts.current(auth).id);
  }

  @PutMapping("/groups/{club}")
  public WhatsApp.Status choose(
    Authentication auth,
    @PathVariable UUID club,
    @RequestBody WhatsApp.Choice choice
  ) {
    return whatsapp.choose(accounts.current(auth).id, club, choice);
  }

  @GetMapping("/groups/{club}/automation")
  public WhatsAppOutbox.Settings automation(
    Authentication auth,
    @PathVariable UUID club
  ) {
    return outbox.settings(accounts.current(auth).id, club);
  }

  @PutMapping("/groups/{club}/automation")
  public WhatsAppOutbox.Settings configure(
    Authentication auth,
    @PathVariable UUID club,
    @RequestBody WhatsAppOutbox.Choice choice
  ) {
    return outbox.choose(accounts.current(auth).id, club, choice);
  }

  @GetMapping(value = "/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
  public String challenge(
    @RequestParam(name = "hub.mode", required = false) String mode,
    @RequestParam(name = "hub.verify_token", required = false) String token,
    @RequestParam(name = "hub.challenge", required = false) String challenge
  ) {
    return whatsapp.challenge(mode, token, challenge);
  }

  @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> webhook(
    @RequestBody byte[] body,
    @RequestHeader(
      name = "X-Hub-Signature-256",
      required = false
    ) String signature
  ) {
    whatsapp.authenticate(body, signature);
    List<WhatsApp.Incoming> messages = new ArrayList<>();
    List<Delivery> deliveries = new ArrayList<>();
    try {
      var root = mapper.readTree(body);
      if (
        !root.path("object").asText().equals("whatsapp_business_account")
      ) throw new ApiException(400, "Evento inválido.");
      for (var entry : root.path("entry"))
        for (var change : entry.path("changes")) {
          var value = change.path("value");
          if (
            !change.path("field").asText().equals("messages") ||
            !value
              .path("metadata")
              .path("phone_number_id")
              .asText()
              .equals(whatsapp.phoneId())
          ) continue;
          for (var m : value.path("messages")) {
            String type = m.path("type").asText();
            if (
              !Set.of("text", "button", "interactive", "audio").contains(type)
            ) continue;
            String text = type.equals("text")
              ? m.path("text").path("body").asText()
              : "";
            String button = type.equals("button")
              ? m.path("button").path("payload").asText()
              : type.equals("interactive")
                ? m.path("interactive").path("button_reply").path("id").asText()
                : null;
            String replyTo = m.path("context").path("id").asText(null);
            String mediaId = type.equals("audio")
              ? m.path("audio").path("id").asText("")
              : null;
            String mediaMime = type.equals("audio")
              ? m.path("audio").path("mime_type").asText("")
              : null;
            if (
              mediaId != null &&
              (!mediaId.matches("[0-9]{1,100}") || mediaMime.length() > 80)
            ) continue;
            if (
              text.length() > 1200 ||
              (button != null && button.length() > 100) ||
              (replyTo != null && replyTo.length() > 256)
            ) continue;
            messages.add(
              new WhatsApp.Incoming(
                m.path("id").asText(),
                m.path("from").asText(),
                text,
                Long.parseLong(m.path("timestamp").asText()),
                button,
                replyTo,
                mediaId,
                mediaMime
              )
            );
            if (messages.size() > 100) throw new ApiException(
              413,
              "Muitos eventos."
            );
          }
          for (var status : value.path("statuses")) {
            deliveries.add(
              new Delivery(
                status.path("id").asText(),
                status.path("status").asText(),
                Long.parseLong(status.path("timestamp").asText())
              )
            );
            if (deliveries.size() > 100) throw new ApiException(
              413,
              "Muitos eventos."
            );
          }
        }
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(400, "Evento inválido.");
    }
    whatsapp.receive(messages);
    deliveries.forEach(d -> outbox.delivery(d.id(), d.state(), d.timestamp()));
    return ResponseEntity.ok().build();
  }

  private record Delivery(String id, String state, long timestamp) {}
}
