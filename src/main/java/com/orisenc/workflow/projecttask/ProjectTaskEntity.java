package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A unit of work (USR-TASK-001), as distinct from an approval decision.
 *
 * <p>Two things make this table the spine of the order-to-cash flow rather than a flat to-do list.
 * The first is {@code parentTaskId}: a customer order is one parent work item and each stage of
 * fulfilling it - raise the vendor order, receive the goods, deliver, capture the POD, invoice,
 * collect - is a child of it. The second is the linked-entity reference, which points each of those
 * children at the record it is about in whichever service owns that record.
 *
 * <p>Ad-hoc work that belongs to no chain, such as attending a customer site, is the same entity
 * with no parent and {@link LinkedEntityType#NONE} or a customer reference.
 *
 * <p>The status rules live here rather than in the service so that no caller can move a task
 * without also writing the history row that explains why - see
 * {@link #transition(ProjectTaskStatus, String, String, Instant, String)}.
 */
@Entity
@Table(name = "project_task", indexes = {
    @Index(name = "idx_project_task_org_status", columnList = "organization_id,status"),
    @Index(name = "idx_project_task_owner_status", columnList = "owner_user_id,status"),
    @Index(name = "idx_project_task_team_due", columnList = "relevant_team,due_at"),
    @Index(name = "idx_project_task_parent", columnList = "parent_task_id"),
    @Index(name = "idx_project_task_linked", columnList = "linked_entity_type,linked_entity_id")
})
public class ProjectTaskEntity {

  /** The actor on a history row nobody chose to write. */
  public static final String SLA_ACTOR = "sla-monitor";

  @Id @Column(length = 40) private String id;
  @Version private long version;
  @Column(name = "organization_id") private UUID organizationId;
  @Column(name = "customer_id") private UUID customerId;
  @Column(name = "vendor_id") private UUID vendorId;

  /** Reserved for the project aggregate, which is designed but not yet built. Always null today. */
  @Column(name = "project_id", length = 40) private String projectId;

  /**
   * Plain column rather than a self-referencing {@code ManyToOne}: children are always fetched by an
   * explicit query, never by walking an object graph, so an association would buy referential
   * integrity at the cost of accidental recursive loading. The service validates that the parent
   * exists and that the link creates no cycle.
   */
  @Column(name = "parent_task_id", length = 40) private String parentTaskId;

  @Column(nullable = false, length = 200) private String title;
  @Column(nullable = false, length = 4000) private String description;

  @Enumerated(EnumType.STRING) @Column(name = "task_type", nullable = false, length = 20)
  private ProjectTaskType taskType;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskPriority priority;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ProjectTaskStatus status;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ProjectTaskVisibility visibility;

  @Column(name = "relevant_team", nullable = false, length = 60) private String relevantTeam;
  /** The single accountable assignee (TM-003). Null means unclaimed. */
  @Column(name = "owner_user_id", length = 120) private String ownerUserId;
  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;

  @Enumerated(EnumType.STRING) @Column(name = "linked_entity_type", nullable = false, length = 30)
  private LinkedEntityType linkedEntityType;
  @Column(name = "linked_entity_id", length = 64) private String linkedEntityId;
  /** Human-readable identifier of the linked record, for example SO-90812. Safe to display. */
  @Column(name = "linked_entity_ref", length = 120) private String linkedEntityRef;

  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "due_at", nullable = false) private Instant dueAt;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "completed_at") private Instant completedAt;
  @Column(name = "cancelled_at") private Instant cancelledAt;
  /** Archived instead of deleted, so the record stays auditable (TM-001). */
  @Column(name = "archived_at") private Instant archivedAt;

  /**
   * The highest SLA rung already raised for this item - see {@code com.orisenc.workflow.sla}.
   *
   * <p>Stored rather than recomputed so the ladder only ever moves upwards: the pass raises a
   * notification when the rung it computes is higher than this, and nothing else. Without it a
   * quarter-hourly scan would re-raise every overdue item on every run, and would depend entirely on
   * the Integration Service's delivery log to stay quiet - a second system's memory standing in for
   * this one's.
   *
   * <p>Deliberately not reset when a completed item is reopened. The rung was genuinely reached and
   * somebody was genuinely told; a reopened item that is still past its deadline escalates again
   * only when it crosses a rung it has not crossed before.
   */
  @Column(name = "escalation_level", nullable = false) private int escalationLevel;

  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("occurredAt ASC") private List<ProjectTaskHistoryEntity> history = new ArrayList<>();

  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("createdAt ASC") private List<ProjectTaskCommentEntity> comments = new ArrayList<>();

  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sequenceNo ASC") private List<ProjectTaskChecklistItemEntity> checklist = new ArrayList<>();

  protected ProjectTaskEntity() {}

  public ProjectTaskEntity(String id, String title, String description, ProjectTaskType taskType,
      TaskPriority priority, ProjectTaskVisibility visibility, String relevantTeam, String ownerUserId,
      String createdBy, LinkedEntityType linkedEntityType, String linkedEntityId, String linkedEntityRef,
      String parentTaskId, Instant createdAt, Instant dueAt) {
    this.id = id;
    this.title = title;
    this.description = description;
    this.taskType = taskType;
    this.priority = priority;
    this.visibility = visibility;
    this.relevantTeam = relevantTeam;
    this.ownerUserId = ownerUserId;
    this.createdBy = createdBy;
    this.linkedEntityType = linkedEntityType == null ? LinkedEntityType.NONE : linkedEntityType;
    this.linkedEntityId = linkedEntityId;
    this.linkedEntityRef = linkedEntityRef;
    this.parentTaskId = parentTaskId;
    this.createdAt = createdAt;
    this.dueAt = dueAt;
    // A task always begins as DRAFT. The creation history row carries no comment, which is the one
    // exemption TM-016 grants; every later transition must explain itself.
    this.status = ProjectTaskStatus.DRAFT;
  }

  /**
   * Moves the task to the target status, refusing transitions the lifecycle does not allow and
   * refusing to move at all without a reason.
   *
   * <p>The history row is appended in the same call because the two must not come apart: a status
   * without its reason is exactly the audit hole TM-016 and policy P-05 exist to close.
   *
   * @throws IllegalArgumentException when the transition is not permitted or the reason is blank;
   *     the service translates these into the API error contract.
   */
  public void transition(ProjectTaskStatus next, String actor, String reason, Instant time, String correlationId) {
    transition(next, actor, null, reason, time, correlationId);
  }

  /**
   * The same move, taken under a delegation.
   *
   * <p>{@code onBehalfOf} is the owner whose authority was used. The overload above is the same call
   * for somebody acting as themselves, which is most of them.
   */
  public void transition(ProjectTaskStatus next, String actor, String onBehalfOf, String reason,
      Instant time, String correlationId) {
    if (next == null) throw new IllegalArgumentException("A target status is required");
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("A comment is required for every status change");
    if (status == next)
      throw new IllegalArgumentException("The task is already " + readable(next));
    if (!status.canMoveTo(next))
      throw new IllegalArgumentException(
          "A task that is " + readable(status) + " cannot move to " + readable(next));

    ProjectTaskStatus previous = status;
    status = next;
    stampTimestamps(previous, next, time);
    history.add(new ProjectTaskHistoryEntity(this, previous, next, actor, onBehalfOf, reason.trim(),
        time, correlationId));
  }

  /**
   * Timestamps track the most recent entry into each state. Reopening a completed task clears its
   * completion time rather than keeping a stale one, because the history already records that the
   * task was once complete and a lingering completion time would make an open task look closed to
   * every SLA and reporting query.
   */
  private void stampTimestamps(ProjectTaskStatus previous, ProjectTaskStatus next, Instant time) {
    switch (next) {
      case IN_PROGRESS -> {
        if (startedAt == null) startedAt = time;
        if (previous == ProjectTaskStatus.COMPLETED) completedAt = null;
        if (previous == ProjectTaskStatus.CANCELLED) cancelledAt = null;
      }
      case NOT_STARTED -> { if (previous == ProjectTaskStatus.CANCELLED) cancelledAt = null; }
      case COMPLETED -> completedAt = time;
      case CANCELLED -> cancelledAt = time;
      default -> { }
    }
  }

  public void assignTo(String actor) { ownerUserId = actor; }

  /**
   * Records that this item has reached an SLA rung, and writes the history row that says so.
   *
   * <p>Both together, like {@link #transition}: an escalation the audit trail cannot see is a
   * message somebody received with nothing on the item to explain it. The actor is the service
   * rather than a person, because nobody decided this - a deadline passed.
   *
   * @throws IllegalArgumentException when the rung is not above the one already recorded, which
   *     would mean the caller is about to notify somebody twice about the same thing
   */
  public void recordEscalation(int level, String note, Instant time, String correlationId) {
    if (level <= escalationLevel)
      throw new IllegalArgumentException("This work item has already reached SLA level " + escalationLevel);
    escalationLevel = level;
    history.add(new ProjectTaskHistoryEntity(this, "SLA_ESCALATED", SLA_ACTOR, null, note, time,
        correlationId));
  }

  public void archive(Instant time) { archivedAt = time; }

  public void addHistory(String action, String actor, String comment, Instant time, String correlationId) {
    addHistory(action, actor, null, comment, time, correlationId);
  }

  /** A non-transition event recorded under a delegation. */
  public void addHistory(String action, String actor, String onBehalfOf, String comment, Instant time,
      String correlationId) {
    history.add(new ProjectTaskHistoryEntity(this, action, actor, onBehalfOf, comment, time, correlationId));
  }

  public void addComment(String author, String body, Instant time) {
    comments.add(new ProjectTaskCommentEntity(this, author, body, time));
  }

  public void addChecklistItem(String itemTitle, boolean required, Instant time) {
    int next = checklist.stream().mapToInt(ProjectTaskChecklistItemEntity::getSequenceNo).max().orElse(0) + 1;
    checklist.add(new ProjectTaskChecklistItemEntity(this, next, itemTitle, required, time));
  }

  /**
   * True when every item marked required is ticked. Consulted before allowing completion so that a
   * checklist is a real gate rather than a decorative list.
   */
  public boolean requiredChecklistComplete() {
    return checklist.stream().filter(ProjectTaskChecklistItemEntity::isRequired)
        .allMatch(ProjectTaskChecklistItemEntity::isCompleted);
  }

  public int getEscalationLevel() { return escalationLevel; }

  public boolean overdue(Instant now) {
    return !status.closed() && archivedAt == null && dueAt.isBefore(now);
  }

  private static String readable(ProjectTaskStatus value) {
    return value.name().toLowerCase().replace('_', ' ');
  }

  public String getId() { return id; }
  public long getVersion() { return version; }
  public UUID getOrganizationId() { return organizationId; }
  public UUID getCustomerId() { return customerId; }
  public UUID getVendorId() { return vendorId; }
  public String getProjectId() { return projectId; }
  public String getParentTaskId() { return parentTaskId; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public ProjectTaskType getTaskType() { return taskType; }
  public TaskPriority getPriority() { return priority; }
  public ProjectTaskStatus getStatus() { return status; }
  public ProjectTaskVisibility getVisibility() { return visibility; }
  public String getRelevantTeam() { return relevantTeam; }
  public String getOwnerUserId() { return ownerUserId; }
  public String getCreatedBy() { return createdBy; }
  public LinkedEntityType getLinkedEntityType() { return linkedEntityType; }
  public String getLinkedEntityId() { return linkedEntityId; }
  public String getLinkedEntityRef() { return linkedEntityRef; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getDueAt() { return dueAt; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getCancelledAt() { return cancelledAt; }
  public Instant getArchivedAt() { return archivedAt; }

  public List<ProjectTaskHistoryEntity> getHistory() {
    return history.stream().sorted(Comparator.comparing(ProjectTaskHistoryEntity::getOccurredAt)).toList();
  }

  public List<ProjectTaskCommentEntity> getComments() { return List.copyOf(comments); }

  public List<ProjectTaskChecklistItemEntity> getChecklist() { return List.copyOf(checklist); }

  public void linkToMaster(UUID organizationId, UUID customerId, UUID vendorId) {
    if (organizationId == null) throw new IllegalArgumentException("Organization id is required.");
    if (customerId != null && vendorId != null)
      throw new IllegalArgumentException("A project task cannot reference both a customer and a vendor.");
    this.organizationId = organizationId;
    this.customerId = customerId;
    this.vendorId = vendorId;
  }
}
