package com.orisenc.workflow.projecttask;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="order_tech_task",uniqueConstraints=@UniqueConstraint(name="uk_order_tech_source",columnNames="order_id"))
public class OrderTechTaskEntity {
  @Id @Column(name="request_key",length=100) String requestKey;
  @Column(name="order_id",nullable=false,length=60) String orderId;
  @Column(name="request_hash",nullable=false,length=64) String requestHash;
  @Column(name="task_id",length=120) String taskId;
  @Column(name="requested_by",nullable=false,length=200) String requestedBy;
  @Column(name="service_actor",nullable=false,length=200) String serviceActor;
  @Column(name="created_at",nullable=false) Instant createdAt;
  protected OrderTechTaskEntity() {}
}
