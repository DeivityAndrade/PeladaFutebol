package br.com.pelada.assistant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assistant_proposals")
public class AssistantProposal {

  @Id
  public UUID id = UUID.randomUUID();

  public UUID authorId;
  public UUID clubId;
  public String timeZone;

  @Column(columnDefinition = "text")
  public String draft;

  @Column(columnDefinition = "text")
  public String question;

  public long revision;
  public Instant createdAt;
  public Instant expiresAt;
  public UUID gameId;

  protected AssistantProposal() {}

  public AssistantProposal(
    UUID authorId,
    UUID clubId,
    String timeZone,
    Instant now
  ) {
    this.authorId = authorId;
    this.clubId = clubId;
    this.timeZone = timeZone;
    this.createdAt = now;
    this.expiresAt = now.plusSeconds(1800);
  }
}
