package br.com.pelada.api;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.auth.Accounts;
import br.com.pelada.games.Games;
import br.com.pelada.groups.Groups;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {

  private final Accounts accounts;
  private final Groups groups;
  private final Games games;

  public ApiController(Accounts accounts, Groups groups, Games games) {
    this.accounts = accounts;
    this.groups = groups;
    this.games = games;
  }

  private UUID user(Authentication auth) {
    return accounts.current(auth).id;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/groups")
  public List<ClubView> groups(Authentication auth) {
    return groups.list(user(auth));
  }

  @PostMapping("/groups")
  public ClubView createGroup(
    Authentication auth,
    @Valid @RequestBody CreateClub input
  ) {
    return groups.create(user(auth), input);
  }

  @PostMapping("/invites/{invite}/join")
  public ClubView join(Authentication auth, @PathVariable UUID invite) {
    return groups.join(user(auth), invite);
  }

  @GetMapping("/groups/{id}/games")
  public List<GameView> games(Authentication auth, @PathVariable UUID id) {
    return games.list(user(auth), id);
  }

  @PostMapping("/groups/{id}/games")
  public GameDetail createGame(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody CreateGame input
  ) {
    return games.create(user(auth), id, input);
  }

  @GetMapping("/games/{id}")
  public GameDetail game(Authentication auth, @PathVariable UUID id) {
    return games.get(user(auth), id);
  }

  @PostMapping("/games/{id}/attendance")
  public GameDetail attend(Authentication auth, @PathVariable UUID id) {
    return games.attend(user(auth), id);
  }

  @DeleteMapping("/games/{id}/attendance")
  public GameDetail leave(Authentication auth, @PathVariable UUID id) {
    return games.leave(user(auth), id);
  }

  @PostMapping("/games/{id}/cancel")
  public GameDetail cancel(Authentication auth, @PathVariable UUID id) {
    return games.cancel(user(auth), id);
  }

  @PutMapping("/games/{game}/teams/{team}")
  public GameDetail configure(
    Authentication auth,
    @PathVariable UUID game,
    @PathVariable UUID team,
    @Valid @RequestBody UpdateTeam input
  ) {
    return games.configure(user(auth), game, team, input);
  }

  @PostMapping("/games/{game}/teams/{team}/players")
  public GameDetail pick(
    Authentication auth,
    @PathVariable UUID game,
    @PathVariable UUID team,
    @Valid @RequestBody Pick input
  ) {
    return games.pick(user(auth), game, team, input.playerId());
  }

  @DeleteMapping("/games/{game}/teams/{team}/players/{player}")
  public GameDetail release(
    Authentication auth,
    @PathVariable UUID game,
    @PathVariable UUID team,
    @PathVariable UUID player
  ) {
    return games.release(user(auth), game, team, player);
  }

  @PutMapping("/games/{game}/teams/{team}/lineup")
  public GameDetail lineup(
    Authentication auth,
    @PathVariable UUID game,
    @PathVariable UUID team,
    @Valid @RequestBody Lineup input
  ) {
    return games.lineup(user(auth), game, team, input);
  }
}
