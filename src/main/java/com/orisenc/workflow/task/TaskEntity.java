package com.orisenc.workflow.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workflow_task", indexes = {
    @Index(name = "idx_workflow_task_org_status", columnList = "organization_id,status"),
    @Index(name = "idx_workflow_task_assignee_status", columnList = "assignee,status"),
    @Index(name = "idx_workflow_task_department_due", columnList = "department,due_at")
})
public class TaskEntity {

  /** The actor on a history row nobody chose to write. */
  public static final String SLA_ACTOR = "sla-monitor";

  @Id @Column(length = 40) private String id;
  @Version private long version;
  @Column(name = "organization_id") private UUID organizationId;
  @Column(name = "customer_id") private UUID customerId;
  @Column(name = "vendor_id") private UUID vendorId;
  @Column(nullable = false, length = 160) private String title;
  @Column(nullable = false, length = 120) private String reference;
  @Column(nullable = false, length = 60) private String department;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskType type;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskPriority priority;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskStatus status;
  @Column(nullable = false, length = 120) private String requester;
  @Column(length = 120) private String assignee;
  @Column(nullable = false, length = 2000) private String summary;
  @Column(length = 100) private String requestValue;
  @Column(nullable = false) private Instant createdAt;
  @Column(name = "due_at", nullable = false) private Instant dueAt;
  private Instant completedAt;
  /**
   * The highest SLA rung already raised for this approval - see {@code com.orisenc.workflow.sla}.
   *
   * <p>A column, not a response field. The {@code /api/tasks} contract is frozen, and an SLA ladder
   * is not a reason to break it; the UI already computes its own countdown from {@code dueAt}.
   */
  @Column(name = "escalation_level", nullable = false) private int escalationLevel;
  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("occurredAt ASC") private List<TaskHistoryEntity> history = new ArrayList<>();

  protected TaskEntity() {}
  public TaskEntity(String id, String title, String reference, String department, TaskType type,
      TaskPriority priority, String requester, String assignee, String summary, String requestValue,
      Instant createdAt, Instant dueAt) {
    this.id=id; this.title=title; this.reference=reference; this.department=department; this.type=type;
    this.priority=priority; this.requester=requester; this.assignee=assignee; this.summary=summary;
    this.requestValue=requestValue; this.createdAt=createdAt; this.dueAt=dueAt; this.status=TaskStatus.PENDING;
  }
  public void addHistory(String action, String actor, String comment, Instant time, String correlationId) {
    addHistory(action, actor, null, comment, time, correlationId);
  }

  /**
   * Records an action taken under a delegation.
   *
   * <p>{@code onBehalfOf} is the assignee whose authority was used. The overload above is the same
   * call for somebody acting as themselves, which is most of them.
   */
  public void addHistory(String action, String actor, String onBehalfOf, String comment, Instant time,
      String correlationId) {
    history.add(new TaskHistoryEntity(this, action, actor, onBehalfOf, comment, time, correlationId));
  }
  public void assignTo(String actor) { assignee = actor; }

  /**
   * Records that this approval has reached an SLA rung, and writes the history row that says so.
   *
   * <p>The actor is the service, not a person: nobody decided this, a deadline passed.
   *
   * @throws IllegalArgumentException when the rung is not above the one already recorded
   */
  public void recordEscalation(int level, String note, Instant time, String correlationId) {
    if (level <= escalationLevel)
      throw new IllegalArgumentException("This task has already reached SLA level " + escalationLevel);
    escalationLevel = level;
    addHistory("SLA_ESCALATED", SLA_ACTOR, note, time, correlationId);
  }
  public void transition(TaskStatus newStatus, Instant time) { status = newStatus; if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.REJECTED) completedAt = time; }
  public String getId(){return id;} public long getVersion(){return version;} public UUID getOrganizationId(){return organizationId;}
  public UUID getCustomerId(){return customerId;} public UUID getVendorId(){return vendorId;} public String getTitle(){return title;}
  public String getReference(){return reference;} public String getDepartment(){return department;} public TaskType getType(){return type;}
  public TaskPriority getPriority(){return priority;} public TaskStatus getStatus(){return status;} public String getRequester(){return requester;}
  public String getAssignee(){return assignee;} public String getSummary(){return summary;} public String getRequestValue(){return requestValue;}
  public Instant getCreatedAt(){return createdAt;} public Instant getDueAt(){return dueAt;} public Instant getCompletedAt(){return completedAt;}
  public int getEscalationLevel(){return escalationLevel;}
  public List<TaskHistoryEntity> getHistory(){return List.copyOf(history);}

  public void linkToMaster(UUID organizationId, UUID customerId, UUID vendorId) {
    if (organizationId == null) throw new IllegalArgumentException("Organization id is required.");
    if (customerId != null && vendorId != null)
      throw new IllegalArgumentException("A workflow task cannot reference both a customer and a vendor.");
    this.organizationId = organizationId;
    this.customerId = customerId;
    this.vendorId = vendorId;
  }
}
