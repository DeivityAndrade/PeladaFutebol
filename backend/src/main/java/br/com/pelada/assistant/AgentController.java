package br.com.pelada.assistant;

import br.com.pelada.auth.Accounts;
import br.com.pelada.domain.ApiException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assistant")
public class AgentController {

  private final Accounts accounts;
  private final SiteAgent agent;
  private final AudioTranscriber audio;

  public AgentController(
    Accounts accounts,
    SiteAgent agent,
    AudioTranscriber audio
  ) {
    this.accounts = accounts;
    this.agent = agent;
    this.audio = audio;
  }

  @GetMapping("/agent")
  public AgentContracts.Availability availability(Authentication auth) {
    return agent.availability(accounts.current(auth).id);
  }

  @PostMapping("/conversations")
  public AgentContracts.Reply message(
    Authentication auth,
    @Valid @RequestBody AgentContracts.Message input
  ) {
    return agent.message(accounts.current(auth).id, input);
  }

  @PostMapping("/conversations/{id}/confirm")
  public AgentContracts.Reply confirm(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody AgentContracts.Confirmation input
  ) {
    return agent.confirm(accounts.current(auth).id, id, input.actionId());
  }

  @PostMapping(value = "/audio", consumes = "multipart/form-data")
  public AudioTranscriber.Transcript transcribe(
    Authentication auth,
    @RequestParam MultipartFile file
  ) throws IOException {
    UUID user = accounts.current(auth).id;
    if (!agent.availability(user).available()) throw new ApiException(
      503,
      "O agente ainda não está disponível. Use a agenda."
    );
    return audio.transcribe(user, file.getBytes(), file.getContentType());
  }
}
