package br.com.pelada.career;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CareerContracts {

  private CareerContracts() {}

  public record ProgramView(
    boolean active,
    Instant startedAt,
    boolean canManage
  ) {}

  public record ProgramInput(@NotNull Boolean active) {}

  public record CareerGroup(UUID clubId, String name, boolean member) {}

  public record AttendanceChoice(
    @NotNull UUID playerId,
    @NotNull Boolean present
  ) {}

  public record ReviewInput(
    @Min(0) long version,
    @NotNull Boolean happened,
    @NotNull @Size(max = 500) List<@Valid AttendanceChoice> players
  ) {}

  public record ReviewPlayer(
    UUID playerId,
    String name,
    boolean guestGoalkeeper,
    Boolean present
  ) {}

  public record AuditView(
    long version,
    String organizerName,
    Instant reviewedAt
  ) {}

  public record ReviewView(
    UUID gameId,
    boolean eligible,
    boolean canReview,
    String message,
    long version,
    Instant reviewedAt,
    List<ReviewPlayer> players,
    List<AuditView> history
  ) {}

  public record AchievementView(
    String code,
    String name,
    int threshold,
    String reward,
    Instant awardedAt,
    boolean unseen
  ) {}

  public record AppearanceView(UUID gameId, String title, Instant startsAt) {}

  public record PendingView(UUID gameId, String title, Instant startsAt) {}

  public record CardView(
    UUID playerId,
    String name,
    String title,
    String frame,
    List<String> badges,
    boolean shared,
    String photoUrl
  ) {}

  public record CardInput(
    @NotNull Boolean shared,
    @Size(max = 40) String title,
    @Size(max = 40) String frame,
    @NotNull @Size(max = 3) List<@NotBlank @Size(max = 40) String> badges
  ) {}

  public record CareerView(
    UUID clubId,
    String clubName,
    String timeZone,
    ProgramView program,
    boolean canShare,
    CardView card,
    int appearances,
    List<AchievementView> achievements,
    List<AppearanceView> history,
    List<PendingView> pending,
    boolean correctionUnseen
  ) {}
}
