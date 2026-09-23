package br.com.pelada.api;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.auth.Accounts;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Barbecues;
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
  private final Matches matches;
  private final Barbecues barbecues;

  public ApiController(
    Accounts accounts,
    Groups groups,
    Games games,
    Matches matches,
    Barbecues barbecues
  ) {
    this.accounts = accounts;
    this.groups = groups;
    this.games = games;
    this.matches = matches;
    this.barbecues = barbecues;
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

  @GetMapping("/groups/{id}/barbecues")
  public List<BarbecueView> barbecues(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.list(user(auth), id);
  }

  @PostMapping("/groups/{id}/barbecue-series")
  public List<BarbecueView> startBarbecueSeries(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody CreateBarbecueSeries input
  ) {
    return barbecues.startSeries(user(auth), id, input);
  }

  @PostMapping("/groups/{id}/barbecue-series/pause")
  public List<BarbecueView> pauseBarbecueSeries(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.pauseSeries(user(auth), id);
  }

  @PostMapping("/groups/{id}/barbecue-series/resume")
  public List<BarbecueView> resumeBarbecueSeries(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.resumeSeries(user(auth), id);
  }

  @PostMapping("/groups/{id}/barbecues")
  public BarbecueView createBarbecue(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody CreateBarbecue input
  ) {
    return barbecues.createOneOff(user(auth), id, input);
  }

  @PutMapping("/barbecues/{id}")
  public BarbecueView updateBarbecue(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody UpdateBarbecue input
  ) {
    return barbecues.update(user(auth), id, input);
  }

  @PostMapping("/barbecues/{id}/cancel")
  public BarbecueView cancelBarbecue(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.cancel(user(auth), id);
  }

  @PostMapping("/barbecues/{id}/attendance")
  public BarbecueView attendBarbecue(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.attendance(user(auth), id, true);
  }

  @DeleteMapping("/barbecues/{id}/attendance")
  public BarbecueView leaveBarbecue(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return barbecues.attendance(user(auth), id, false);
  }

  @GetMapping("/barbecue-invites/{token}")
  public BarbecueView barbecueInvite(
    Authentication auth,
    @PathVariable UUID token
  ) {
    return barbecues.inviteDetails(user(auth), token);
  }

  @PostMapping("/barbecue-invites/{token}/attendance")
  public BarbecueView attendInvitedBarbecue(
    Authentication auth,
    @PathVariable UUID token
  ) {
    return barbecues.inviteAttendance(user(auth), token, true);
  }

  @DeleteMapping("/barbecue-invites/{token}/attendance")
  public BarbecueView leaveInvitedBarbecue(
    Authentication auth,
    @PathVariable UUID token
  ) {
    return barbecues.inviteAttendance(user(auth), token, false);
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

  @PostMapping("/games/{id}/teams/draw")
  public GameDetail draw(Authentication auth, @PathVariable UUID id) {
    return games.draw(user(auth), id);
  }

  @PostMapping("/games/{id}/match/start")
  public GameDetail start(Authentication auth, @PathVariable UUID id) {
    return games.detail(matches.start(user(auth), id), user(auth));
  }

  @PostMapping("/games/{id}/match/finish")
  public GameDetail finish(Authentication auth, @PathVariable UUID id) {
    return games.detail(matches.finish(user(auth), id), user(auth));
  }

  @PostMapping("/games/{id}/match/correction")
  public GameDetail openCorrection(Authentication auth, @PathVariable UUID id) {
    return games.detail(matches.correction(user(auth), id, true), user(auth));
  }

  @DeleteMapping("/games/{id}/match/correction")
  public GameDetail closeCorrection(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return games.detail(matches.correction(user(auth), id, false), user(auth));
  }

  @PutMapping("/games/{id}/match/duration")
  public GameDetail duration(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody MatchDuration input
  ) {
    return games.detail(
      matches.duration(user(auth), id, input.seconds()),
      user(auth)
    );
  }

  @PostMapping("/games/{id}/match/goals")
  public GameDetail goal(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody NewGoal input
  ) {
    return games.detail(matches.goal(user(auth), id, input), user(auth));
  }

  @DeleteMapping("/games/{id}/match/goals/{goal}")
  public GameDetail voidGoal(
    Authentication auth,
    @PathVariable UUID id,
    @PathVariable UUID goal
  ) {
    return games.detail(matches.voidGoal(user(auth), id, goal), user(auth));
  }

  @PutMapping("/games/{id}/ratings")
  public GameDetail rating(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody SaveRating input
  ) {
    return games.detail(matches.rate(user(auth), id, input), user(auth));
  }

  @GetMapping("/players/{id}/profile")
  public PlayerProfile profile(Authentication auth, @PathVariable UUID id) {
    return matches.profile(user(auth), id);
  }
}
