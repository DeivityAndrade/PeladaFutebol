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
}
