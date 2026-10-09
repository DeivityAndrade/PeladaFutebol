package br.com.pelada.assistant;

import br.com.pelada.assistant.AssistantContracts.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class AgentContracts {

  private AgentContracts() {}

  public record Message(
    @NotBlank @Size(max = 1200) String message,
    UUID conversationId,
    @PositiveOrZero long version,
    UUID selectedClubId
  ) {}

  public record Confirmation(@NotNull UUID actionId) {}

  public record Availability(boolean available, boolean audioAvailable) {}

  public record Turn(String role, String text) {}

  public record GameOption(
    UUID id,
    UUID clubId,
    String title,
    String location,
    Instant startsAt,
    String timeZone
  ) {}

  public record GroupOption(
    UUID id,
    String name,
    String timeZone,
    boolean organizer,
    List<GameOption> games
  ) {}

  public record Context(
    Instant now,
    UUID selectedClubId,
    List<GroupOption> groups,
    AssistantContracts.Draft previous,
    List<Turn> history
  ) {}

  public record Decision(
    String action,
    UUID clubId,
    UUID gameId,
    String title,
    String location,
    LocalDate date,
    LocalTime time,
    Integer teamCount,
    Integer teamSize,
    Boolean recurring,
    LocalDate recurrenceEndsOn,
    String question
  ) {
    public Interpretation interpretation() {
      return new Interpretation(
        title,
        location,
        date,
        time,
        teamCount,
        teamSize,
        recurring,
        recurrenceEndsOn,
        question
      );
    }
  }

  public record Action(
    UUID id,
    String type,
    UUID clubId,
    UUID gameId,
    UUID proposalId,
    String label,
    String summary,
    Proposal proposal
  ) {}

  public record Reply(
    UUID conversationId,
    long version,
    String message,
    UUID clubId,
    List<GameOption> games,
    Action action,
    UUID changedGameId
  ) {}
}
