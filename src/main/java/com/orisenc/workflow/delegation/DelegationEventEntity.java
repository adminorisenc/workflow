package com.orisenc.workflow.delegation;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One immutable event in a delegation's life.
 *
 * <p>Append-only, like the task histories. It matters more here than it does on a task: a delegation
 * is a transfer of authority, so the question an auditor asks later is not only "who could act" but
 * "who allowed them to, and when did that stop". Both answers are rows in this table.
 */
@Entity
@Table(name = "workflow_delegation_event", indexes = {
    @Index(name = "idx_delegation_event", columnList = "delegation_id,occurred_at")
})
public class DelegationEventEntity {

  public static final int MAX_COMMENT = 500;

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "delegation_id", nullable = false) private DelegationEntity delegation;

  @Column(nullable = false, length = 40) private String action;
  @Column(nullable = false, length = 120) private String actor;
  @Column(length = MAX_COMMENT) private String comment;
  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
  @Column(name = "correlation_id", nullable = false, length = 100) private String correlationId;

  protected DelegationEventEntity() {}

  DelegationEventEntity(DelegationEntity delegation, String action, String actor, String comment,
      Instant occurredAt, String correlationId) {
    this.delegation = delegation;
    this.action = action;
    this.actor = actor;
    this.comment = comment;
    this.occurredAt = occurredAt;
    this.correlationId = correlationId;
  }

  public Long getId() { return id; }
  public String getAction() { return action; }
  public String getActor() { return actor; }
  public String getComment() { return comment; }
  public Instant getOccurredAt() { return occurredAt; }
  public String getCorrelationId() { return correlationId; }
}
