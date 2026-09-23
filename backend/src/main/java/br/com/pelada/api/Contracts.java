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
    String barbecueFrequency
  ) {
    public CreateClub(String name, String description) {
      this(name, description, "NONE");
    }

    public CreateClub {
      if (barbecueFrequency == null) barbecueFrequency = "NONE";
    }
  }

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
    @Min(5) @Max(12) int teamSize
  ) {}

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
    boolean barbecueSeriesActive
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
