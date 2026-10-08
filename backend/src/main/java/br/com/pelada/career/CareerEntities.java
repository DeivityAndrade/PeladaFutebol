package br.com.pelada.career;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

public final class CareerEntities {

  private CareerEntities() {}

  @Entity(name = "CareerPeriod")
  @Table(name = "career_periods")
  public static class CareerPeriod {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public Instant startedAt;
    public Instant endedAt;
  }

  @Entity(name = "AttendanceReview")
  @Table(name = "attendance_reviews")
  public static class AttendanceReview {

    @Id
    public UUID id;

    public boolean eligible;
    public long version;
    public Instant reviewedAt;
    public UUID reviewedBy;
  }

  @Entity(name = "VerifiedAttendance")
  @Table(name = "verified_attendance")
  public static class VerifiedAttendance {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID gameId;
    public UUID playerId;
    public boolean present;
  }

  @Entity(name = "AttendanceAudit")
  @Table(name = "attendance_audits")
  public static class AttendanceAudit {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID gameId;
    public long version;
    public UUID reviewedBy;
    public Instant createdAt;

    @Column(columnDefinition = "text")
    public String beforeSnapshot;

    @Column(columnDefinition = "text")
    public String afterSnapshot;
  }

  @Entity(name = "CareerAchievement")
  @Table(name = "career_achievements")
  public static class CareerAchievement {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID playerId;
    public String code;
    public Instant awardedAt;
    public Instant seenAt;
  }

  @Entity(name = "CareerCard")
  @Table(name = "career_cards")
  public static class CareerCard {

    @Id
    public UUID id = UUID.randomUUID();

    public UUID clubId;
    public UUID playerId;
    public boolean shared;
    public String title;
    public String frame;
    public String badges = "";
    public int observedCount;
    public Instant correctedAt;
    public Instant correctionSeenAt;
  }
}
