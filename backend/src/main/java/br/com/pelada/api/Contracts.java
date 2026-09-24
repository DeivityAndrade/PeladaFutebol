package br.com.pelada.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Contracts {

  private Contracts() {}

  public record Register(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 8, max = 72) String password
  ) {}

  public record Login(
    @NotBlank @Email String email,
    @NotBlank @Size(max = 72) String password
  ) {}

  public record CreateClub(
    @NotBlank @Size(max = 80) String name,
    @NotNull @Size(max = 300) String description,
    @Pattern(regexp = "NONE|MONTHLY|EVERY_2_MONTHS|EVERY_3_MONTHS")
    String barbecueFrequency,
    @PositiveOrZero Long monthlyAmountCents,
    @Min(1) @Max(31) Integer billingDueDay,
    @PositiveOrZero Long occasionalAmountCents,
    @Size(max = 500) String pixInstructions
  ) {
    public CreateClub(String name, String description) {
      this(name, description, "NONE", null, 1, null, "");
    }

    public CreateClub(
      String name,
      String description,
      String barbecueFrequency
    ) {
      this(name, description, barbecueFrequency, null, 1, null, "");
    }

    public CreateClub {
      if (barbecueFrequency == null) barbecueFrequency = "NONE";
      if (billingDueDay == null) billingDueDay = 1;
      if (pixInstructions == null) pixInstructions = "";
    }
  }

  public record FinanceSettingsInput(
    @PositiveOrZero Long monthlyAmountCents,
    @NotNull @Min(1) @Max(31) Integer billingDueDay,
    @PositiveOrZero Long occasionalAmountCents,
    @Size(max = 500) String pixInstructions
  ) {
    public FinanceSettingsInput {
      if (pixInstructions == null) pixInstructions = "";
    }
  }

  public record FinanceMemberInput(boolean monthly) {}

  public record FinanceReviewInput(@Size(max = 240) String note) {}

  public record CreateBarbecueSeries(
    @NotNull Instant startsAt,
    @NotBlank @Size(max = 160) String location,
    @NotBlank @Size(max = 80) String timeZone
  ) {}

  public record CreateBarbecue(
    @NotNull Instant startsAt,
    @NotBlank @Size(max = 160) String location
  ) {}

  public record UpdateBarbecue(
    @NotNull Instant startsAt,
    @NotBlank @Size(max = 160) String location
  ) {}

  public record CreateGame(
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 160) String location,
    @NotNull Instant startsAt,
    @Min(2) @Max(6) int teamCount,
    @Min(5) @Max(12) int teamSize,
    Boolean chargeOccasional,
    @PositiveOrZero Long occasionalAmountCents
  ) {
    public CreateGame {
      if (chargeOccasional == null) chargeOccasional = false;
    }

    public CreateGame(
      String title,
      String location,
      Instant startsAt,
      int teamCount,
      int teamSize
    ) {
      this(title, location, startsAt, teamCount, teamSize, false, null);
    }
  }

  public record UpdateTeam(
    @NotBlank @Size(max = 60) String name,
    @NotNull @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
    UUID captainId
  ) {}

  public record Pick(@NotNull UUID playerId) {}

  public record Lineup(
    @NotNull @Pattern(regexp = "2-2|1-2-1|3-1") String formation,
    @NotNull @Size(min = 5, max = 5) List<UUID> slots,
    @PositiveOrZero long version
  ) {}

  public record NewGoal(
    @NotNull UUID teamId,
    @NotNull UUID scorerId,
    boolean ownGoal,
    @Min(0) @Max(1440) Integer minute
  ) {}

  public record MatchDuration(@Min(0) @Max(86400) int seconds) {}

  public record SaveRating(@NotNull UUID playerId, @Min(1) @Max(5) int stars) {}

  public record UserView(UUID id, String name, String email) {}

  public record Person(UUID id, String name) {}

  public record ClubView(
    UUID id,
    String name,
    String description,
    UUID ownerId,
    UUID invite,
    long memberCount,
    boolean demo,
    String barbecueFrequency,
    boolean barbecueSeriesActive,
    Long monthlyAmountCents,
    int billingDueDay,
    Long occasionalAmountCents,
    String pixInstructions
  ) {}

  public record SocialListingInput(
    @NotEmpty
    @Size(max = 2)
    List<@Pattern(regexp = "PICKUP|FIXED_TEAM") String> categories,
    @NotBlank @Pattern(regexp = "[0-9]{7}") String municipalityCode,
    @NotBlank @Size(max = 100) String courtName,
    @NotBlank @Size(max = 80) String neighborhood,
    @NotNull @Size(max = 500) String description,
    @NotBlank
    @Pattern(regexp = "RECREATIONAL|INTERMEDIATE|COMPETITIVE")
    String skillLevel,
    @NotNull
    @Size(max = 7)
    List<@Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN") String> preferredDays,
    @NotNull
    @Size(max = 3)
    List<@Pattern(
      regexp = "MORNING|AFTERNOON|EVENING"
    ) String> preferredPeriods,
    boolean published
  ) {}

  public record SocialInviteInput(
    @NotNull UUID senderClubId,
    @NotNull UUID targetClubId,
    @NotNull Instant startsAt,
    @NotBlank @Size(max = 240) String location,
    @Size(max = 500) String message
  ) {
    public SocialInviteInput {
      if (message == null) message = "";
    }
  }

  public record SocialProposalInput(
    @NotNull Instant startsAt,
    @NotBlank @Size(max = 240) String location
  ) {}

  public record SocialMessageInput(@NotBlank @Size(max = 1000) String body) {}

  public record MunicipalityView(
    String code,
    String name,
    String uf,
    String label
  ) {}

  public record SocialListingView(
    UUID clubId,
    String clubName,
    boolean published,
    List<String> categories,
    String municipalityCode,
    String municipalityName,
    String uf,
    String courtName,
    String neighborhood,
    String description,
    String skillLevel,
    List<String> preferredDays,
    List<String> preferredPeriods,
    boolean canManage
  ) {}

  public record SocialOwnedGroupView(
    UUID clubId,
    String clubName,
    SocialListingView listing
  ) {}

  public record SocialSearchResult(
    SocialListingView listing,
    double distanceKm,
    boolean scheduleCompatible,
    boolean levelSimilar
  ) {}

  public record SocialMatchView(
    UUID id,
    UUID hostClubId,
    String hostClubName,
    UUID guestClubId,
    String guestClubName,
    String status,
    boolean outgoing,
    boolean chatOpen,
    Instant proposedStartsAt,
    String proposedLocation,
    String message,
    Instant expiresAt,
    int revision,
    boolean hostConfirmed,
    boolean guestConfirmed,
    int unreadMessages,
    Instant startsAt,
    String location,
    boolean canAccept,
    boolean canDecline,
    boolean canConfirm,
    boolean canPropose,
    boolean canCancel
  ) {}

  public record SocialMessageView(
    UUID id,
    UUID senderId,
    String senderName,
    String body,
    Instant createdAt,
    boolean mine
  ) {}

  public record SocialScheduleView(
    UUID id,
    UUID clubId,
    String opponentName,
    Instant startsAt,
    String location,
    String status
  ) {}

  public record FinanceSettingsView(
    Long monthlyAmountCents,
    int billingDueDay,
    Long occasionalAmountCents,
    String pixInstructions
  ) {}

  public record FinanceMemberView(
    UUID playerId,
    String playerName,
    String billingType,
    java.time.LocalDate monthlyFrom,
    java.time.LocalDate monthlyThrough,
    Long monthlyAmountCents
  ) {}

  public record FinanceChargeView(
    UUID id,
    UUID playerId,
    String playerName,
    String type,
    String period,
    UUID gameId,
    String gameTitle,
    Instant gameStartsAt,
    long amountCents,
    java.time.LocalDate dueDate,
    String status,
    boolean overdue,
    boolean dueSoon,
    boolean manual,
    String reviewNote,
    boolean receiptAvailable,
    String receiptFilename,
    String receiptContentType,
    Instant receiptUploadedAt,
    Instant receiptExpiresAt,
    boolean canUpload,
    boolean canReview,
    boolean canReadReceipt
  ) {}

  public record FinanceSummary(
    FinanceSettingsView settings,
    List<FinanceMemberView> members,
    List<Person> people,
    List<FinanceChargeView> charges,
    long receivedCents,
    long pendingCents,
    long overdueCents,
    int overdueCount,
    int dueSoonCount,
    boolean canManage,
    boolean canViewAll
  ) {}

  public record BarbecueAttendee(UUID id, String name) {}

  public record BarbecueView(
    UUID id,
    UUID clubId,
    String clubName,
    Instant startsAt,
    String location,
    boolean cancelled,
    boolean recurring,
    int confirmed,
    boolean attending,
    String inviteToken,
    List<BarbecueAttendee> attendees
  ) {}

  public record GameView(
    UUID id,
    UUID clubId,
    String title,
    String location,
    Instant startsAt,
    int teamCount,
    int teamSize,
    int confirmed,
    int waiting,
    boolean chargeOccasional,
    Long occasionalAmountCents,
    boolean cancelled,
    boolean editable,
    boolean teamEditable,
    boolean liveEnabled,
    String matchStatus,
    Instant matchStartedAt,
    Instant matchEndedAt,
    Integer matchDurationSeconds,
    boolean correctionOpen,
    Instant serverNow
  ) {}

  public record GoalView(
    UUID id,
    UUID teamId,
    UUID scorerId,
    String scorerName,
    int minute,
    boolean ownGoal,
    boolean voided
  ) {}

  public record TeamScore(UUID teamId, int goals) {}

  public record RatingView(UUID playerId, Double average, int count) {}

  public record OwnRating(UUID playerId, int stars) {}

  public record PlayerGameRating(
    UUID gameId,
    String gameTitle,
    Instant startsAt,
    double average,
    int count
  ) {}

  public record PlayerProfile(
    UUID id,
    String name,
    Double average,
    int ratedGames,
    List<PlayerGameRating> history
  ) {}

  public record Attendee(
    UUID id,
    String name,
    String status,
    UUID teamId,
    Integer slot
  ) {}

  public record TeamView(
    UUID id,
    String name,
    String color,
    UUID captainId,
    String formation,
    long version
  ) {}

  public record GameDetail(
    GameView game,
    ClubView club,
    List<Attendee> attendees,
    List<TeamView> teams,
    List<TeamScore> score,
    List<GoalView> goals,
    List<RatingView> ratings,
    List<OwnRating> myRatings,
    Instant ratingsVisibleAt
  ) {}
}
