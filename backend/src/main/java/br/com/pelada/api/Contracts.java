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
    @NotNull @Size(max = 300) String description
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

  public record UserView(UUID id, String name, String email) {}

  public record Person(UUID id, String name) {}

  public record ClubView(
    UUID id,
    String name,
    String description,
    UUID ownerId,
    UUID invite,
    long memberCount,
    boolean demo
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
    boolean editable
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
    List<TeamView> teams
  ) {}
}
