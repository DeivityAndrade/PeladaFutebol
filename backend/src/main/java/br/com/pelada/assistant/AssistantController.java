package br.com.pelada.assistant;

import static br.com.pelada.assistant.AssistantContracts.*;

import br.com.pelada.api.Contracts.GameDetail;
import br.com.pelada.auth.Accounts;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AssistantController {

  private final Accounts accounts;
  private final Assistant assistant;

  public AssistantController(Accounts accounts, Assistant assistant) {
    this.accounts = accounts;
    this.assistant = assistant;
  }

  @GetMapping("/groups/{id}/assistant")
  public Availability availability(Authentication auth, @PathVariable UUID id) {
    return assistant.availability(accounts.current(auth).id, id);
  }

  @PostMapping("/groups/{id}/assistant/proposals")
  public Proposal propose(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody Request input
  ) {
    return assistant.propose(accounts.current(auth).id, id, input);
  }

  @PutMapping("/assistant/proposals/{id}")
  public Proposal revise(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody Revision input
  ) {
    return assistant.revise(accounts.current(auth).id, id, input);
  }

  @PostMapping("/assistant/proposals/{id}/confirm")
  public GameDetail confirm(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody Confirmation input
  ) {
    return assistant.confirm(accounts.current(auth).id, id, input);
  }
}
