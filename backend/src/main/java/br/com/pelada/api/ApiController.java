package br.com.pelada.api;

import br.com.pelada.api.Contracts.*;
import br.com.pelada.auth.Accounts;
import br.com.pelada.games.Games;
import br.com.pelada.games.Matches;
import br.com.pelada.groups.Barbecues;
import br.com.pelada.groups.Finance;
import br.com.pelada.groups.Groups;
import br.com.pelada.social.Social;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {

  private final Accounts accounts;
  private final Groups groups;
  private final Games games;
  private final Matches matches;
  private final Barbecues barbecues;
  private final Finance finance;
  private final Social social;

  public ApiController(
    Accounts accounts,
    Groups groups,
    Games games,
    Matches matches,
    Barbecues barbecues,
    Finance finance,
    Social social
  ) {
    this.accounts = accounts;
    this.groups = groups;
    this.games = games;
    this.matches = matches;
    this.barbecues = barbecues;
    this.finance = finance;
    this.social = social;
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

  @GetMapping("/social/cities")
  public List<MunicipalityView> socialCities(
    Authentication auth,
    @RequestParam(defaultValue = "") String query
  ) {
    return social.cities(user(auth), query);
  }

  @GetMapping("/social/mine")
  public List<SocialOwnedGroupView> mySocialListings(Authentication auth) {
    return social.mine(user(auth));
  }

  @PutMapping("/social/listings/{clubId}")
  public SocialListingView saveSocialListing(
    Authentication auth,
    @PathVariable UUID clubId,
    @Valid @RequestBody SocialListingInput input
  ) {
    return social.saveListing(user(auth), clubId, input);
  }

  @DeleteMapping("/social/listings/{clubId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeSocialListing(
    Authentication auth,
    @PathVariable UUID clubId
  ) {
    social.removeListing(user(auth), clubId);
  }

  @GetMapping("/social/search")
  public List<SocialSearchResult> searchSocial(
    Authentication auth,
    @RequestParam String cityCode,
    @RequestParam(defaultValue = "50") int radiusKm,
    @RequestParam(required = false) String category,
    @RequestParam(required = false) String skillLevel,
    @RequestParam(required = false) List<String> days,
    @RequestParam(required = false) List<String> periods
  ) {
    return social.search(
      user(auth),
      cityCode,
      radiusKm,
      category,
      skillLevel,
      days,
      periods
    );
  }

  @GetMapping("/social/invitations")
  public List<SocialMatchView> socialInvitations(Authentication auth) {
    return social.invitations(user(auth));
  }

  @PostMapping("/social/invitations")
  public SocialMatchView createSocialInvitation(
    Authentication auth,
    @Valid @RequestBody SocialInviteInput input
  ) {
    return social.invite(user(auth), input);
  }

  @PostMapping("/social/invitations/{id}/accept")
  public SocialMatchView acceptSocialInvitation(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.accept(user(auth), id);
  }

  @PostMapping("/social/invitations/{id}/decline")
  public SocialMatchView declineSocialInvitation(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.decline(user(auth), id);
  }

  @PostMapping("/social/invitations/{id}/confirm")
  public SocialMatchView confirmSocialInvitation(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.confirm(user(auth), id);
  }

  @PutMapping("/social/invitations/{id}/proposal")
  public SocialMatchView updateSocialProposal(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody SocialProposalInput input
  ) {
    return social.propose(user(auth), id, input);
  }

  @PostMapping("/social/invitations/{id}/cancel")
  public SocialMatchView cancelSocialInvitation(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.cancel(user(auth), id);
  }

  @GetMapping("/social/invitations/{id}/messages")
  public List<SocialMessageView> socialMessages(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.messages(user(auth), id);
  }

  @PostMapping("/social/invitations/{id}/messages")
  public SocialMessageView sendSocialMessage(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody SocialMessageInput input
  ) {
    return social.sendMessage(user(auth), id, input);
  }

  @GetMapping("/groups/{id}/finance")
  public FinanceSummary finance(
    Authentication auth,
    @PathVariable UUID id,
    @RequestParam(required = false) String period,
    @RequestParam(required = false) UUID playerId,
    @RequestParam(required = false) UUID gameId
  ) {
    return finance.summary(user(auth), id, period, playerId, gameId);
  }

  @PutMapping("/groups/{id}/finance/settings")
  public FinanceSummary financeSettings(
    Authentication auth,
    @PathVariable UUID id,
    @Valid @RequestBody FinanceSettingsInput input
  ) {
    return finance.updateSettings(user(auth), id, input);
  }

  @PutMapping("/groups/{id}/finance/members/{playerId}")
  public FinanceSummary classifyFinanceMember(
    Authentication auth,
    @PathVariable UUID id,
    @PathVariable UUID playerId,
    @RequestBody FinanceMemberInput input
  ) {
    return finance.classifyMember(user(auth), id, playerId, input.monthly());
  }

  @PostMapping("/finance/charges/{id}/receipt")
  public FinanceChargeView uploadFinanceReceipt(
    Authentication auth,
    @PathVariable UUID id,
    @RequestPart("file") MultipartFile file
  ) {
    if (
      file.getSize() > Finance.MAX_RECEIPT_BYTES
    ) throw new br.com.pelada.domain.ApiException(
      413,
      "O comprovante deve ter no máximo 2 MB."
    );
    try {
      return finance.uploadReceipt(
        user(auth),
        id,
        file.getOriginalFilename(),
        file.getContentType(),
        file.getBytes()
      );
    } catch (IOException ex) {
      throw new br.com.pelada.domain.ApiException(
        400,
        "Não foi possível ler o comprovante."
      );
    }
  }

  @GetMapping("/finance/charges/{id}/receipt")
  public ResponseEntity<byte[]> financeReceipt(
    Authentication auth,
    @PathVariable UUID id
  ) {
    Finance.ReceiptDownload file = finance.receipt(user(auth), id);
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(file.contentType()))
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename(file.filename(), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .header("X-Content-Type-Options", "nosniff")
      .cacheControl(CacheControl.noStore())
      .body(file.data());
  }

  @PostMapping("/finance/charges/{id}/approve")
  public FinanceChargeView approveFinanceCharge(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return finance.review(user(auth), id, true, null);
  }

  @PostMapping("/finance/charges/{id}/reject")
  public FinanceChargeView rejectFinanceCharge(
    Authentication auth,
    @PathVariable UUID id,
    @RequestBody(required = false) FinanceReviewInput input
  ) {
    return finance.review(user(auth), id, false, input);
  }

  @PostMapping("/finance/charges/{id}/cash")
  public FinanceChargeView markFinanceCash(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return finance.markCash(user(auth), id);
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

  @GetMapping("/groups/{id}/friendlies")
  public List<SocialScheduleView> friendlies(
    Authentication auth,
    @PathVariable UUID id
  ) {
    return social.schedule(user(auth), id);
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
