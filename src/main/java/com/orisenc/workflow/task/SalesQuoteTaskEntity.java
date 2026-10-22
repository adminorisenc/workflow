package com.orisenc.workflow.task;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Binds one Sales quote approval request (a quote and its approval round) to its Workflow approval task.
 * Sales owns the quote and the decision; this row only records what Sales asserted. Numeric ids are Sales identities.
 */
@Entity @Table(name="sales_quote_approval_task")
public class SalesQuoteTaskEntity {
  @Id @Column(name="request_key",length=80) String requestKey;
  @Column(name="quote_id",nullable=false) Long quoteId;
  @Column(name="approval_round",nullable=false) Integer round;
  @Column(name="quote_code",nullable=false,length=40) String quoteCode;
  @Column(name="request_hash",nullable=false,length=64) String requestHash;
  @Column(name="task_id",unique=true,length=40) String taskId;
  @Column(name="source_status",nullable=false,length=30) String sourceStatus;
  @Column(name="latest_sequence",nullable=false) Integer latestSequence;
  @Column(name="decided_by",length=200) String decidedBy;
  @Column(name="decided_at") Instant decidedAt;
  @Column(length=2000) String reason;
  @Column(name="service_actor",nullable=false,length=200) String serviceActor;
  @Column(name="created_at",nullable=false) Instant createdAt;
  @Column(name="updated_at",nullable=false) Instant updatedAt;
  protected SalesQuoteTaskEntity() {}
}
