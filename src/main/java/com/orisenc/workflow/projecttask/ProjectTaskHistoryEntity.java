package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One immutable lifecycle event on a work item (TM-018, task_status_history).
 *
 * <p>Carries both the old and the new status, unlike the approval history in
 * {@code com.orisenc.workflow.task}, which records only an action name. Storing the pair is what
 * lets an auditor reconstruct the task's state at any past moment without replaying assumptions
 * about which action implied which status.
 *
 * <p>Rows that describe something other than a transition - creation, a claim, an archive - leave
 * both statuses null and carry an action name instead.
 */
@Entity
@Table(name = "project_task_history", indexes = {
    @Index(name = "idx_project_task_history_task", columnList = "task_id,occurred_at")
})
public class ProjectTaskHistoryEntity {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false) private ProjectTaskEntity task;

  @Enumerated(EnumType.STRING) @Column(name = "from_status", length = 20) private ProjectTaskStatus fromStatus;
  @Enumerated(EnumType.STRING) @Column(name = "to_status", length = 20) private ProjectTaskStatus toStatus;

  /** Set for non-transition events such as CREATED, CLAIMED, ARCHIVED. */
  @Column(length = 40) private String action;

  @Column(nullable = false, length = 120) private String actor;
  /**
   * Whose authority the actor was using, when it was not their own (REQ-0027).
   *
   * <p>Null for the ordinary case. When set, the pair is the whole point: an audit that records only
   * the delegate cannot answer who was accountable, and one that records only the owner is a lie
   * about who did it.
   */
  @Column(name = "on_behalf_of", length = 120) private String onBehalfOf;
  @Column(length = 2000) private String reason;
  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
  @Column(name = "correlation_id", nullable = false, length = 100) private String correlationId;

  protected ProjectTaskHistoryEntity() {}

  /** A status transition. */
  ProjectTaskHistoryEntity(ProjectTaskEntity task, ProjectTaskStatus fromStatus, ProjectTaskStatus toStatus,
      String actor, String onBehalfOf, String reason, Instant occurredAt, String correlationId) {
    this.task = task;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.action = "STATUS_CHANGED";
    this.actor = actor;
    this.onBehalfOf = onBehalfOf;
    this.reason = reason;
    this.occurredAt = occurredAt;
    this.correlationId = correlationId;
  }

  /** A non-transition event. */
  ProjectTaskHistoryEntity(ProjectTaskEntity task, String action, String actor, String onBehalfOf,
      String reason, Instant occurredAt, String correlationId) {
    this.task = task;
    this.action = action;
    this.actor = actor;
    this.onBehalfOf = onBehalfOf;
    this.reason = reason;
    this.occurredAt = occurredAt;
    this.correlationId = correlationId;
  }

  public Long getId() { return id; }
  public ProjectTaskStatus getFromStatus() { return fromStatus; }
  public ProjectTaskStatus getToStatus() { return toStatus; }
  public String getAction() { return action; }
  public String getActor() { return actor; }
  public String getOnBehalfOf() { return onBehalfOf; }
  public String getReason() { return reason; }
  public Instant getOccurredAt() { return occurredAt; }
  public String getCorrelationId() { return correlationId; }
}
