package br.com.pelada.career;

import br.com.pelada.auth.Accounts;
import br.com.pelada.career.CareerContracts.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CareerController {

  private final Career career;
  private final Accounts accounts;

  public CareerController(Career career, Accounts accounts) {
    this.career = career;
    this.accounts = accounts;
  }

  private UUID user(Authentication auth) {
    return accounts.current(auth).id;
  }

  @GetMapping("/career/groups")
  public List<CareerGroup> groups(Authentication auth) {
    return career.directory(user(auth));
  }

  @GetMapping("/groups/{id}/career/me")
  public CareerView mine(Authentication auth, @PathVariable UUID id) {
    return career.mine(user(auth), id);
  }

  @PutMapping("/groups/{id}/career/program")
  public ProgramView program(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody ProgramInput input
  ) {
    return career.configure(user(auth), id, input.active());
  }

  @PutMapping("/groups/{id}/career/card")
  public CardView card(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody CardInput input
  ) {
    return career.customize(user(auth), id, input);
  }

  @PostMapping("/groups/{id}/career/seen")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void seen(Authentication auth, @PathVariable UUID id) {
    career.seen(user(auth), id);
  }

  @GetMapping("/groups/{id}/career/cards/{playerId}")
  public CardView shared(
    Authentication auth,
    @PathVariable UUID id,
    @PathVariable UUID playerId
  ) {
    return career.shared(user(auth), id, playerId);
  }

  @GetMapping("/games/{id}/attendance-review")
  public ReviewView review(Authentication auth, @PathVariable UUID id) {
    return career.review(user(auth), id);
  }

  @PutMapping("/games/{id}/attendance-review")
  public ReviewView save(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody ReviewInput input
  ) {
    return career.saveReview(user(auth), id, input);
  }
}
