package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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

  public static final int MAX_TITLE = 200;
  public static final int MAX_DESCRIPTION = 4000;
  public static final int MAX_SUMMARY = 1000;

  @Id @Column(length = 40) private String id;
  @Version private long version;
  @Column(name = "organization_id") private Long organizationId;
  @Column(name = "customer_id") private Long customerId;
  @Column(name = "vendor_id") private Long vendorId;

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

  /**
   * The task's own short account of the work (TM-A02), distinct from {@code description}.
   *
   * <p>{@code description} is the brief: what was asked for and what counts as done, written when
   * the task is raised. This is what the work amounts to, and it stays editable throughout. The two
   * are separate because the first stops being rewritten once work starts and the second does not.
   *
   * <p>Nullable, because every task that already exists has none and {@code ddl-auto} cannot add a
   * non-null column to a populated table.
   */
  @Column(name = "summary", length = MAX_SUMMARY) private String summary;

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

  /**
   * When the owner intends to start and finish (TM-A01).
   *
   * <p>Deliberately inert: {@code dueAt} remains the deadline the SLA ladder and every overdue query
   * read, and planned end drives nothing. They answer different questions - "when did we commit to
   * having this done" against "when do I mean to do it" - and collapsing them would make moving your
   * own schedule silently move the deadline you are measured against.
   */
  @Column(name = "planned_start_at") private Instant plannedStartAt;
  @Column(name = "planned_end_at") private Instant plannedEndAt;

  /**
   * When work actually began and finished.
   *
   * <p>Stamped by {@link #stampTimestamps} on the matching transition and correctable afterwards by
   * whoever may edit the task, because people do the work and update the system later. No second
   * pair of "actual" columns was added beside these: they already mean exactly this, and a duplicate
   * pair would give every SLA and reporting query two candidate answers for when a task finished.
   */
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

  /**
   * People working this item alongside its owner. Ordered by name so the panel and the API agree on
   * a stable order without either sorting it themselves.
   */
  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("username ASC") private List<ProjectTaskAssigneeEntity> assignees = new ArrayList<>();

  /**
   * Effort logged against this item, and journeys made for it (TM-A05, TM-A06).
   *
   * <p>Two collections rather than one, because their totals are reported separately and never added
   * together: a two-hour job that cost five hours of travel is a fact worth seeing, and a single
   * seven-hour figure hides it.
   */
  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("workDate ASC, id ASC") private List<ProjectTaskTimeEntryEntity> timeEntries = new ArrayList<>();

  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("travelDate ASC, id ASC") private List<ProjectTaskTravelEntryEntity> travelEntries = new ArrayList<>();

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
   * The three fields the constructor does not take, set once at creation.
   *
   * <p>Package-private and separate from {@link #applyEdit} because creation is not an edit: there
   * is no previous value to diff against and no history row to write, and routing it through the
   * edit path would put "summary set to ..." on a task one line after "CREATED".
   */
  void describe(String summary, Instant plannedStartAt, Instant plannedEndAt) {
    this.summary = optionalText(summary, "A summary", MAX_SUMMARY);
    assertOrdered(plannedStartAt, plannedEndAt, "The planned end cannot be before the planned start.");
    this.plannedStartAt = plannedStartAt;
    this.plannedEndAt = plannedEndAt;
  }

  // ---------------------------------------------------------------- editing (TM-A03)

  /**
   * One field an edit actually changed, ready to become a history row.
   *
   * <p>Values are rendered as text rather than kept typed: the row this becomes is read by a person
   * looking at an activity feed, not by code branching on the field name.
   */
  public record FieldChange(String field, String from, String to) {}

  /** The editable state of a work item, sent whole. See {@link #applyEdit}. */
  public record Edit(String title, String summary, String description, ProjectTaskType taskType,
      TaskPriority priority, ProjectTaskVisibility visibility, String relevantTeam,
      LinkedEntityType linkedEntityType, String linkedEntityId, String linkedEntityRef,
      String parentTaskId, Instant dueAt, Instant plannedStartAt, Instant plannedEndAt,
      Instant startedAt, Instant completedAt) {}

  /**
   * Whether this item is still open to change.
   *
   * <p>Closed means Completed or Cancelled, archived means withdrawn from use, and neither may be
   * edited or have time logged against it: a closed record that keeps moving is not a record. A
   * completed task can be reopened through the ordinary transition, which is the deliberate way back
   * in - and that demands a comment, so the reason for reopening lands on the trail.
   */
  public boolean open() {
    return !status.closed() && archivedAt == null;
  }

  /**
   * Applies an edit and reports exactly what it changed.
   *
   * <p>Whole state rather than a sparse patch, so that clearing an optional field and leaving it
   * alone are different requests instead of the same absent value. Optimistic locking is what makes
   * that safe, and the service refuses an edit that does not carry the expected version.
   *
   * <p>The diff is computed here rather than in the service because the two must not disagree: a
   * history row claiming a change the entity did not make, or an entity change no row describes, is
   * precisely the audit hole TM-018 exists to close. The caller writes one row per returned change
   * and nothing else.
   *
   * @return the fields that actually changed, empty when the edit was a no-op
   * @throws IllegalArgumentException when a required field is missing or the dates contradict
   */
  public List<FieldChange> applyEdit(Edit edit) {
    if (edit == null) throw new IllegalArgumentException("Nothing to change.");
    String newTitle = requiredText(edit.title(), "A title", MAX_TITLE);
    String newDescription = requiredText(edit.description(), "A description", MAX_DESCRIPTION);
    String newSummary = optionalText(edit.summary(), "A summary", MAX_SUMMARY);
    String newTeam = requiredText(edit.relevantTeam(), "A relevant team", 60);
    if (edit.taskType() == null) throw new IllegalArgumentException("A task type is required.");
    if (edit.priority() == null) throw new IllegalArgumentException("A priority is required.");
    if (edit.visibility() == null) throw new IllegalArgumentException("A visibility is required.");
    if (edit.dueAt() == null) throw new IllegalArgumentException("A due date is required.");
    assertOrdered(edit.plannedStartAt(), edit.plannedEndAt(),
        "The planned end cannot be before the planned start.");
    assertOrdered(edit.startedAt(), edit.completedAt(),
        "A work item cannot have finished before it started.");
    LinkedEntityType newLinkType =
        edit.linkedEntityType() == null ? LinkedEntityType.NONE : edit.linkedEntityType();
    String newLinkId = optionalText(edit.linkedEntityId(), "A linked record id", 64);
    String newLinkRef = optionalText(edit.linkedEntityRef(), "A linked record reference", 120);
    if (newLinkType != LinkedEntityType.NONE && newLinkId == null && newLinkRef == null)
      throw new IllegalArgumentException("A linked record needs an id or a reference.");
    String newParent = optionalText(edit.parentTaskId(), "A parent work item", 40);
    if (id.equals(newParent)) throw new IllegalArgumentException("A work item cannot be its own parent.");

    var changes = new ArrayList<FieldChange>();
    title = record(changes, "title", title, newTitle);
    summary = record(changes, "summary", summary, newSummary);
    description = record(changes, "description", description, newDescription);
    taskType = record(changes, "type", taskType, edit.taskType());
    priority = record(changes, "priority", priority, edit.priority());
    visibility = record(changes, "visibility", visibility, edit.visibility());
    relevantTeam = record(changes, "relevant team", relevantTeam, newTeam);
    linkedEntityType = record(changes, "linked record type", linkedEntityType, newLinkType);
    linkedEntityId = record(changes, "linked record id", linkedEntityId, newLinkId);
    linkedEntityRef = record(changes, "linked record", linkedEntityRef, newLinkRef);
    parentTaskId = record(changes, "parent work item", parentTaskId, newParent);
    dueAt = record(changes, "due date", dueAt, edit.dueAt());
    plannedStartAt = record(changes, "planned start", plannedStartAt, edit.plannedStartAt());
    plannedEndAt = record(changes, "planned end", plannedEndAt, edit.plannedEndAt());
    startedAt = record(changes, "actual start", startedAt, edit.startedAt());
    completedAt = record(changes, "actual end", completedAt, edit.completedAt());
    return List.copyOf(changes);
  }

  /**
   * Notes a change if there is one, and returns the value to keep.
   *
   * <p>Returning the replacement rather than assigning it keeps each line above readable as one
   * statement - "this field becomes that, and the change is recorded" - instead of an if and an
   * assignment that can drift apart.
   */
  private static <T> T record(List<FieldChange> changes, String field, T current, T replacement) {
    if (Objects.equals(current, replacement)) return current;
    changes.add(new FieldChange(field, text(current), text(replacement)));
    return replacement;
  }

  private static String text(Object value) {
    if (value == null) return null;
    String rendered = value.toString();
    // A four-thousand character description would otherwise put four thousand characters onto a
    // history row, twice. The row says the field changed; the task itself says what it now holds.
    return rendered.length() > 200 ? rendered.substring(0, 197) + "..." : rendered;
  }

  private static void assertOrdered(Instant start, Instant end, String message) {
    if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException(message);
  }

  private static String requiredText(String value, String what, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " is required.");
    String trimmed = value.trim();
    if (trimmed.length() > max)
      throw new IllegalArgumentException(what + " cannot be longer than " + max + " characters.");
    return trimmed;
  }

  private static String optionalText(String value, String what, int max) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    if (trimmed.length() > max)
      throw new IllegalArgumentException(what + " cannot be longer than " + max + " characters.");
    return trimmed;
  }

  // ------------------------------------------------- time and travel (TM-A05, TM-A06)

  /**
   * Records that somebody spent time on this item.
   *
   * <p>Refuses a closed or archived item for the same reason editing does: hours arriving against a
   * task that finished last month change a total somebody has already reported on.
   *
   * @throws IllegalArgumentException when the item is closed or archived, or the entry is invalid
   */
  public ProjectTaskTimeEntryEntity logTime(String username, LocalDate workDate, int minutes,
      String note, String loggedBy, Instant now) {
    assertOpen("log time against");
    var entry = new ProjectTaskTimeEntryEntity(this, username, workDate, minutes, note, loggedBy, now);
    timeEntries.add(entry);
    return entry;
  }

  public ProjectTaskTravelEntryEntity logTravel(String traveller, LocalDate travelDate, String from,
      String to, String purpose, int travelMinutes, BigDecimal expense, String currency,
      String voucherRef, String loggedBy, Instant now) {
    assertOpen("log travel against");
    var entry = new ProjectTaskTravelEntryEntity(this, traveller, travelDate, from, to, purpose,
        travelMinutes, expense, currency, voucherRef, loggedBy, now);
    travelEntries.add(entry);
    return entry;
  }

  public ProjectTaskTimeEntryEntity timeEntry(Long entryId) {
    return timeEntries.stream().filter(entry -> entry.getId().equals(entryId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("That time entry is not on this work item."));
  }

  public ProjectTaskTravelEntryEntity travelEntry(Long entryId) {
    return travelEntries.stream().filter(entry -> entry.getId().equals(entryId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("That travel entry is not on this work item."));
  }

  /** Corrects an entry, refusing a closed item exactly as logging one does. */
  public void correctTime(ProjectTaskTimeEntryEntity entry, LocalDate workDate, int minutes,
      String note, Instant now) {
    assertOpen("change time logged against");
    entry.correct(workDate, minutes, note, now);
  }

  public void correctTravel(ProjectTaskTravelEntryEntity entry, LocalDate travelDate, String from,
      String to, String purpose, int travelMinutes, BigDecimal expense, String currency,
      String voucherRef, Instant now) {
    assertOpen("change travel logged against");
    entry.correct(travelDate, from, to, purpose, travelMinutes, expense, currency, voucherRef, now);
  }

  public void removeTimeEntry(ProjectTaskTimeEntryEntity entry) {
    assertOpen("change time logged against");
    timeEntries.remove(entry);
  }

  public void removeTravelEntry(ProjectTaskTravelEntryEntity entry) {
    assertOpen("change travel logged against");
    travelEntries.remove(entry);
  }

  private void assertOpen(String what) {
    if (archivedAt != null)
      throw new IllegalArgumentException("This work item is archived; you cannot " + what + " it.");
    if (status.closed())
      throw new IllegalArgumentException("This work item is " + readable(status)
          + "; reopen it before you " + what + " it.");
  }

  /** Effort logged on this item, in minutes. Never includes travel - see {@link #totalTravelMinutes}. */
  public int totalWorkMinutes() {
    return timeEntries.stream().mapToInt(ProjectTaskTimeEntryEntity::getDurationMinutes).sum();
  }

  public int totalTravelMinutes() {
    return travelEntries.stream().mapToInt(ProjectTaskTravelEntryEntity::getTravelMinutes).sum();
  }

  /**
   * What travel for this item has cost.
   *
   * <p>Totals only the entries in {@link #expenseCurrency()} rather than adding different currencies
   * into a meaningless number. Mixed-currency travel on one task is unlikely, and a silently wrong
   * total would be worse than a visibly partial one.
   */
  public BigDecimal totalExpense() {
    String currency = expenseCurrency();
    return travelEntries.stream()
        .filter(entry -> entry.getCurrencyCode().equals(currency))
        .map(ProjectTaskTravelEntryEntity::getExpenseAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The currency the expense total is in, or the default when no travel has been logged. */
  public String expenseCurrency() {
    return travelEntries.stream().findFirst()
        .map(ProjectTaskTravelEntryEntity::getCurrencyCode)
        .orElse(ProjectTaskTravelEntryEntity.DEFAULT_CURRENCY);
  }

  public List<ProjectTaskTimeEntryEntity> getTimeEntries() { return List.copyOf(timeEntries); }

  public List<ProjectTaskTravelEntryEntity> getTravelEntries() { return List.copyOf(travelEntries); }


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
   * Puts a second person on this item.
   *
   * <p>Refuses the owner: they already hold it, and a row saying otherwise would make "who is on
   * this task" two different answers depending on which one you read. Refuses a repeat for the same
   * reason the unique constraint does - the entity should say no before the database has to.
   *
   * @throws IllegalArgumentException when the person owns the item or is already on it
   */
  public ProjectTaskAssigneeEntity addAssignee(String username, String addedBy, Instant time) {
    if (username == null || username.isBlank())
      throw new IllegalArgumentException("A username is required.");
    String trimmed = username.trim();
    if (ownerUserId != null && ownerUserId.equalsIgnoreCase(trimmed))
      throw new IllegalArgumentException(trimmed + " already owns this task.");
    if (isAssignee(trimmed))
      throw new IllegalArgumentException(trimmed + " is already assigned to this task.");
    var assignee = new ProjectTaskAssigneeEntity(this, trimmed, addedBy, time);
    assignees.add(assignee);
    return assignee;
  }

  /** Removes a person from this item. Returns false when they were not on it. */
  public boolean removeAssignee(String username) {
    return assignees.removeIf(entry -> entry.getUsername().equalsIgnoreCase(username));
  }

  /**
   * Whether this person is named on the item beyond its owner.
   *
   * <p>Case-insensitive, matching how {@code ownerUserId} and {@code createdBy} are compared in
   * {@link ProjectTaskVisibilityPolicy} - one kind of identity comparison across the item.
   */
  public boolean isAssignee(String username) {
    return username != null
        && assignees.stream().anyMatch(entry -> entry.getUsername().equalsIgnoreCase(username));
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
  public Long getOrganizationId() { return organizationId; }
  public Long getCustomerId() { return customerId; }
  public Long getVendorId() { return vendorId; }
  public String getProjectId() { return projectId; }
  public String getParentTaskId() { return parentTaskId; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public String getSummary() { return summary; }
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
  public Instant getPlannedStartAt() { return plannedStartAt; }
  public Instant getPlannedEndAt() { return plannedEndAt; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getCancelledAt() { return cancelledAt; }
  public Instant getArchivedAt() { return archivedAt; }

  public List<ProjectTaskHistoryEntity> getHistory() {
    return history.stream().sorted(Comparator.comparing(ProjectTaskHistoryEntity::getOccurredAt)).toList();
  }

  public List<ProjectTaskCommentEntity> getComments() { return List.copyOf(comments); }

  public List<ProjectTaskChecklistItemEntity> getChecklist() { return List.copyOf(checklist); }

  public List<ProjectTaskAssigneeEntity> getAssignees() { return List.copyOf(assignees); }

  public void linkToMaster(Long organizationId, Long customerId, Long vendorId) {
    if (organizationId == null) throw new IllegalArgumentException("Organization id is required.");
    if (customerId != null && vendorId != null)
      throw new IllegalArgumentException("A project task cannot reference both a customer and a vendor.");
    this.organizationId = organizationId;
    this.customerId = customerId;
    this.vendorId = vendorId;
  }
}
