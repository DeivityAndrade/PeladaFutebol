package br.com.pelada.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class AssistantContracts {

  private AssistantContracts() {}

  public record Request(
    @NotBlank @Size(max = 1200) String message,
    UUID previousProposalId
  ) {}

  public record Draft(
    @Size(max = 100) String title,
    @Size(max = 160) String location,
    LocalDate date,
    LocalTime time,
    @Min(2) @Max(6) Integer teamCount,
    @Min(5) @Max(12) Integer teamSize,
    Boolean recurring,
    LocalDate recurrenceEndsOn,
    boolean chargeOccasional,
    @PositiveOrZero Long occasionalAmountCents
  ) {}

  public record Revision(
    @PositiveOrZero long version,
    @NotNull @Valid Draft draft
  ) {}

  public record Confirmation(@PositiveOrZero long version) {}

  public record Proposal(
    UUID id,
    long version,
    String timeZone,
    Instant expiresAt,
    Draft draft,
    List<String> questions,
    boolean ready
  ) {}

  public record Availability(boolean available) {}

  public record Interpretation(
    String title,
    String location,
    LocalDate date,
    LocalTime time,
    Integer teamCount,
    Integer teamSize,
    Boolean recurring,
    LocalDate recurrenceEndsOn,
    String question
  ) {}

  public record Context(
    String groupName,
    String timeZone,
    Instant now,
    Draft previous
  ) {}
}
