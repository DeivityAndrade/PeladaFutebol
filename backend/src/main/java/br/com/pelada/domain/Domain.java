package br.com.pelada.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Small aggregate entities. Cross-aggregate references are explicit UUIDs. */
public final class Domain {

  private Domain() {}

  @Entity(name = "Player")
  @Table(name = "players")
  public static class Player {

    @Id
    public UUID id = UUID.randomUUID();

    public String name;
    public String email;
    public String password;

    protected Player() {}

    public Player(String name, String email, String password) {
      this.name = name;
      this.email = email;
      this.password = password;
    }
  }

  @Entity(name = "Club")
  @Table(name = "clubs")
  public static class Club {

    @Id
    public UUID id = UUID.randomUUID();

    public String name;
    public String description;
    public UUID ownerId;
    public UUID invite = UUID.randomUUID();
    public boolean demo;
    public String barbecueFrequency = "NONE";

    protected Club() {}

    public Club(String name, String description, UUID ownerId) {
      this.name = name;
      this.description = description;
      this.ownerId = ownerId;
    }
  }

  @Entity(name = "Member")
  @Table(name = "members")
  public static class Member {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID playerId;

    protected Member() {}

    public Member(UUID clubId, UUID playerId) {
      this.clubId = clubId;
      this.playerId = playerId;
    }
  }

  @Entity(name = "BarbecueSeries")
  @Table(name = "barbecue_series")
  public static class BarbecueSeries {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public String frequency;
    public Instant anchorStartsAt;
    public String timeZone;
    public String location;
    public boolean active = true;
    public int nextOccurrenceIndex;

    protected BarbecueSeries() {}

    public BarbecueSeries(
      UUID clubId,
      String frequency,
      Instant anchorStartsAt,
      String timeZone,
      String location
    ) {
      this.clubId = clubId;
      this.frequency = frequency;
      this.anchorStartsAt = anchorStartsAt;
      this.timeZone = timeZone;
      this.location = location;
    }
  }

  @Entity(name = "Barbecue")
  @Table(name = "barbecues")
  public static class Barbecue {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID seriesId;
    public int occurrenceIndex;
    public Instant startsAt;
    public String location;
    public boolean cancelled;
    public UUID inviteToken = UUID.randomUUID();

    protected Barbecue() {}

    public Barbecue(
      UUID clubId,
      UUID seriesId,
      int occurrenceIndex,
      Instant startsAt,
      String location
    ) {
      this.clubId = clubId;
      this.seriesId = seriesId;
      this.occurrenceIndex = occurrenceIndex;
      this.startsAt = startsAt;
      this.location = location;
    }
  }

  @Entity(name = "BarbecueAttendance")
  @Table(
    name = "barbecue_attendance",
    uniqueConstraints = @UniqueConstraint(
      columnNames = { "barbecue_id", "player_id" }
    )
  )
  public static class BarbecueAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public UUID barbecueId;
    public UUID playerId;
    public Instant createdAt;

    protected BarbecueAttendance() {}

    public BarbecueAttendance(
      UUID barbecueId,
      UUID playerId,
      Instant createdAt
    ) {
      this.barbecueId = barbecueId;
      this.playerId = playerId;
      this.createdAt = createdAt;
    }
  }

  @Entity(name = "Game")
  @Table(name = "games")
  public static class Game {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public String title;
    public String location;
    public Instant startsAt;
    public int teamCount;
    public int teamSize;
    public boolean cancelled;
    public boolean liveEnabled;
    public Instant matchStartedAt;
    public Instant matchEndedAt;
    public Integer matchDurationSeconds;
    public boolean correctionOpen;

    protected Game() {}

    public Game(
      UUID clubId,
      String title,
      String location,
      Instant startsAt,
      int teamCount,
      int teamSize
    ) {
      this.clubId = clubId;
      this.title = title;
      this.location = location;
      this.startsAt = startsAt;
      this.teamCount = teamCount;
      this.teamSize = teamSize;
      this.liveEnabled = teamCount == 2;
    }
  }

  @Entity(name = "Team")
  @Table(name = "teams")
  public static class Team {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID gameId;
    public int ordinal;
    public String name;
    public String color;
    public UUID captainId;
    public String formation = "2-2";
    public long version;

    protected Team() {}

    public Team(UUID gameId, int ordinal, String name, String color) {
      this.gameId = gameId;
      this.ordinal = ordinal;
      this.name = name;
      this.color = color;
    }
  }

  @Entity(name = "Participation")
  @Table(name = "participations")
  public static class Participation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public UUID gameId;
    public UUID playerId;
    public String status;
    public UUID teamId;
    public Integer slot;

    protected Participation() {}

    public Participation(UUID gameId, UUID playerId, String status) {
      this.gameId = gameId;
      this.playerId = playerId;
      this.status = status;
    }
  }

  @Entity(name = "Goal")
  @Table(name = "goals")
  public static class Goal {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID gameId;
    public UUID teamId;
    public UUID scorerId;
    public int minute;
    public boolean ownGoal;
    public Instant createdAt;
    public Instant voidedAt;

    protected Goal() {}

    public Goal(
      UUID gameId,
      UUID teamId,
      UUID scorerId,
      int minute,
      boolean ownGoal,
      Instant createdAt
    ) {
      this.gameId = gameId;
      this.teamId = teamId;
      this.scorerId = scorerId;
      this.minute = minute;
      this.ownGoal = ownGoal;
      this.createdAt = createdAt;
    }
  }

  @Entity(name = "Rating")
  @Table(
    name = "ratings",
    uniqueConstraints = @UniqueConstraint(
      columnNames = { "game_id", "rater_id", "player_id" }
    )
  )
  public static class Rating {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID gameId;
    public UUID raterId;
    public UUID playerId;
    public int stars;
    public Instant updatedAt;

    protected Rating() {}

    public Rating(
      UUID gameId,
      UUID raterId,
      UUID playerId,
      int stars,
      Instant updatedAt
    ) {
      this.gameId = gameId;
      this.raterId = raterId;
      this.playerId = playerId;
      this.stars = stars;
      this.updatedAt = updatedAt;
    }
  }
}
