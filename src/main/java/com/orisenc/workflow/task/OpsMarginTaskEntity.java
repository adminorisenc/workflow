package com.orisenc.workflow.task;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Binds one OPS margin evaluation to its Workflow approval task. OPS owns the evaluation and the decision;
 * this row only records what OPS asserted and which task represents it. Numeric ids are OPS identities.
 */
@Entity @Table(name="ops_margin_approval_task")
public class OpsMarginTaskEntity {
  @Id @Column(name="margin_id") Long marginId;
  @Column(name="request_key",nullable=false,unique=true,length=60) String requestKey;
  @Column(name="order_id",nullable=false) Long orderId;
  @Column(name="revision_id",nullable=false) Long revisionId;
  @Column(name="margin_version",nullable=false) Long marginVersion;
  @Column(name="order_number",nullable=false,length=60) String orderNumber;
  @Column(name="request_hash",nullable=false,length=64) String requestHash;
  @Column(name="task_id",unique=true,length=40) String taskId;
  @Column(name="source_status",nullable=false,length=30) String sourceStatus;
  @Column(name="latest_ops_version",nullable=false) Long latestOpsVersion;
  @Column(name="decided_by",length=200) String decidedBy;
  @Column(name="decided_at") Instant decidedAt;
  @Column(length=2000) String reason;
  @Column(name="service_actor",nullable=false,length=200) String serviceActor;
  @Column(name="created_at",nullable=false) Instant createdAt;
  @Column(name="updated_at",nullable=false) Instant updatedAt;
  protected OpsMarginTaskEntity() {}
}
