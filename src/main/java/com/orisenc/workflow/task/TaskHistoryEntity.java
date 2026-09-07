package com.orisenc.workflow.task;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workflow_task_history")
public class TaskHistoryEntity {

  /**
   * Matches the column width below. Exposed so the service can reject an over-long comment as a
   * validation error instead of letting it fail as a database constraint violation.
   */
  public static final int MAX_COMMENT = 500;

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false) private TaskEntity task;
  @Column(nullable = false, length = 40) private String action;
  @Column(nullable = false, length = 120) private String actor;
  /**
   * Whose authority the actor was using, when it was not their own (REQ-0027).
   *
   * <p>Null for the ordinary case, which is somebody acting for themselves. When set, the pair is
   * the whole point: an audit that records only the delegate cannot answer "who was accountable for
   * this decision", and one that records only the assignee is a lie about who pressed the button.
   */
  @Column(name = "on_behalf_of", length = 120) private String onBehalfOf;
  @Column(length = MAX_COMMENT) private String comment;
  @Column(nullable = false) private Instant occurredAt;
  @Column(nullable = false, length = 100) private String correlationId;
  protected TaskHistoryEntity() {}
  TaskHistoryEntity(TaskEntity task, String action, String actor, String onBehalfOf, String comment, Instant occurredAt, String correlationId) {
    this.task=task; this.action=action; this.actor=actor; this.onBehalfOf=onBehalfOf; this.comment=comment; this.occurredAt=occurredAt; this.correlationId=correlationId;
  }
  public Long getId(){return id;} public String getAction(){return action;} public String getActor(){return actor;}
  public String getOnBehalfOf(){return onBehalfOf;}
  public String getComment(){return comment;} public Instant getOccurredAt(){return occurredAt;} public String getCorrelationId(){return correlationId;}
}
