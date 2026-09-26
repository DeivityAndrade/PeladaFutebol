package br.com.pelada.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
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
    public Long monthlyAmountCents;
    public int billingDueDay = 1;
    public Long occasionalAmountCents;
    public String pixInstructions = "";

    @Column(name = "time_zone")
    public String timeZone = "America/Sao_Paulo";

    protected Club() {}

    public Club(String name, String description, UUID ownerId) {
      this.name = name;
      this.description = description;
      this.ownerId = ownerId;
    }
  }

  @Entity(name = "SocialListing")
  @Table(name = "social_listings")
  public static class SocialListing {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public boolean published;
    public String categories;
    public String municipalityCode;
    public String courtName;
    public String neighborhood;
    public String description = "";
    public String skillLevel;
    public String preferredDays = "";
    public String preferredPeriods = "";
    public Instant createdAt;
    public Instant updatedAt;

    protected SocialListing() {}

    public SocialListing(UUID clubId, Instant now) {
      this.clubId = clubId;
      this.createdAt = now;
      this.updatedAt = now;
    }
  }

  @Entity(name = "GoalkeeperProfile")
  @Table(name = "goalkeeper_profiles")
  public static class GoalkeeperProfile {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID playerId;
    public boolean published;
    public String municipalityCode;
    public String skillLevel;
    public String preferredDays = "";
    public String preferredPeriods = "";
    public String description = "";
    public Instant createdAt;
    public Instant updatedAt;

    protected GoalkeeperProfile() {}

    public GoalkeeperProfile(UUID playerId, Instant now) {
      this.playerId = playerId;
      this.createdAt = now;
      this.updatedAt = now;
    }
  }

  @Entity(name = "GoalkeeperInvite")
  @Table(name = "goalkeeper_invites")
  public static class GoalkeeperInvite {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID organizerId;
    public UUID goalkeeperId;
    public UUID gameId;
    public UUID teamId;
    public String message = "";
    public String status = "PENDING";
    public String statusReason;
    public Instant createdAt;
    public Instant expiresAt;
    public Instant respondedAt;
    public Instant closedAt;

    protected GoalkeeperInvite() {}

    public GoalkeeperInvite(
      UUID organizerId,
      UUID goalkeeperId,
      UUID gameId,
      UUID teamId,
      String message,
      Instant createdAt,
      Instant expiresAt
    ) {
      this.organizerId = organizerId;
      this.goalkeeperId = goalkeeperId;
      this.gameId = gameId;
      this.teamId = teamId;
      this.message = message;
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
    }
  }

  @Entity(name = "SocialMatch")
  @Table(name = "social_matches")
  public static class SocialMatch {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID hostClubId;
    public UUID guestClubId;
    public String status = "PENDING";
    public Instant proposedStartsAt;
    public String proposedLocation;
    public Instant startsAt;
    public String location;
    public String note = "";
    public Instant expiresAt;
    public Instant acceptedAt;
    public Instant createdAt;
    public Instant updatedAt;
    public int revision = 1;
    public int hostConfirmedRevision;
    public int guestConfirmedRevision;
    public Instant hostLastReadAt;
    public Instant guestLastReadAt;
    public UUID proposedBy;
    public UUID cancelledBy;
    public Instant cancelledAt;

    protected SocialMatch() {}

    public SocialMatch(
      UUID hostClubId,
      UUID guestClubId,
      Instant startsAt,
      String location,
      String note,
      Instant now,
      Instant expiresAt
    ) {
      this.hostClubId = hostClubId;
      this.guestClubId = guestClubId;
      this.proposedStartsAt = startsAt;
      this.proposedLocation = location;
      this.note = note;
      this.createdAt = now;
      this.updatedAt = now;
      this.expiresAt = expiresAt;
    }
  }

  @Entity(name = "SocialMessage")
  @Table(name = "social_messages")
  public static class SocialMessage {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID matchId;
    public UUID senderId;
    public String body;
    public Instant createdAt;

    protected SocialMessage() {}

    public SocialMessage(
      UUID matchId,
      UUID senderId,
      String body,
      Instant createdAt
    ) {
      this.matchId = matchId;
      this.senderId = senderId;
      this.body = body;
      this.createdAt = createdAt;
    }
  }

  @Entity(name = "Member")
  @Table(name = "members")
  public static class Member {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID playerId;
    public String billingType = "OCCASIONAL";
    public LocalDate monthlyFrom;
    public LocalDate monthlyThrough;
    public Long monthlyAmountCents;

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

  @Entity(name = "FinanceCharge")
  @Table(name = "finance_charges")
  public static class FinanceCharge {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID memberId;
    public UUID playerId;
    public UUID gameId;
    public String type;
    public String period;
    public long amountCents;
    public LocalDate dueDate;
    public String status = "PENDING";
    public boolean manual;
    public String reviewNote;
    public Instant createdAt;
    public Instant paymentSubmittedAt;
    public Instant paidAt;
    public Instant reviewedAt;
    public UUID reviewedBy;
    public Instant receiptUploadedAt;
    public String receiptFilename;
    public String receiptContentType;
    public Instant receiptDeletedAt;

    protected FinanceCharge() {}

    public FinanceCharge(
      UUID clubId,
      UUID memberId,
      UUID playerId,
      UUID gameId,
      String type,
      String period,
      long amountCents,
      LocalDate dueDate,
      Instant createdAt
    ) {
      this.clubId = clubId;
      this.memberId = memberId;
      this.playerId = playerId;
      this.gameId = gameId;
      this.type = type;
      this.period = period;
      this.amountCents = amountCents;
      this.dueDate = dueDate;
      this.createdAt = createdAt;
    }
  }

  @Entity(name = "FinanceReceiptFile")
  @Table(name = "finance_receipt_files")
  public static class FinanceReceiptFile {

    @Id
    public UUID chargeId;

    @Column(columnDefinition = "bytea")
    public byte[] data;

    protected FinanceReceiptFile() {}

    public FinanceReceiptFile(UUID chargeId, byte[] data) {
      this.chargeId = chargeId;
      this.data = data;
    }
  }

  @Entity(name = "GameSeries")
  @Table(name = "game_series")
  public static class GameSeries {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public boolean active = true;
    public String timeZone;
    public Instant anchorStartsAt;
    public int anchorOccurrenceIndex = 1;
    public int nextOccurrenceIndex = 2;
    public LocalDate endsOn;
    public String title;
    public String location;
    public int teamCount;
    public int teamSize;
    public boolean chargeOccasional;
    public Long occasionalAmountCents;
    public Instant createdAt;

    protected GameSeries() {}

    public GameSeries(
      UUID clubId,
      String timeZone,
      Instant anchorStartsAt,
      LocalDate endsOn,
      String title,
      String location,
      int teamCount,
      int teamSize,
      boolean chargeOccasional,
      Long occasionalAmountCents,
      Instant createdAt
    ) {
      this.clubId = clubId;
      this.timeZone = timeZone;
      this.anchorStartsAt = anchorStartsAt;
      this.endsOn = endsOn;
      this.title = title;
      this.location = location;
      this.teamCount = teamCount;
      this.teamSize = teamSize;
      this.chargeOccasional = chargeOccasional;
      this.occasionalAmountCents = occasionalAmountCents;
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
    public boolean chargeOccasional;
    public Long occasionalAmountCents;
    public UUID seriesId;
    public int seriesOccurrenceIndex;
    public boolean seriesException;

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
    public UUID goalkeeperInviteId;

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
