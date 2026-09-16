package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.task.TaskPriority;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire types for the project work item API.
 *
 * <p>No request carries an actor. The controller derives it from the validated token, for the same
 * reason the approval API does: an actor supplied by the client is an audit record that proves
 * nothing.
 */
public final class ProjectTaskDtos {

  private ProjectTaskDtos() {}

  public record ChecklistItemInput(String title, Boolean required) {}

  public record CreateProjectTaskRequest(
      String title, String description, ProjectTaskType taskType, TaskPriority priority,
      ProjectTaskVisibility visibility, String relevantTeam, String ownerUserId,
      LinkedEntityType linkedEntityType, String linkedEntityId, String linkedEntityRef,
      String parentTaskId, Instant dueAt, List<ChecklistItemInput> checklist,
      /* TM-002's customer link. Optional, and a task carries a customer or a vendor, never both. */
      Long organizationId, Long customerId, Long vendorId,
      /* TM-A02 and TM-A01. All three optional at creation and editable afterwards. */
      String summary, Instant plannedStartAt, Instant plannedEndAt) {}

  /**
   * The whole editable state of a work item (TM-A03).
   *
   * <p>Whole rather than sparse on purpose: with a partial patch, "clear the summary" and "leave the
   * summary alone" are the same absent field, and one of the two has to become impossible.
   * {@code expectedVersion} is what makes sending everything safe - an edit built on a stale read is
   * refused rather than quietly overwriting somebody else's.
   *
   * <p>Status is absent, and deliberately: it moves only through {@code /transition}, which will not
   * move it without a comment.
   */
  public record UpdateProjectTaskRequest(
      String title, String summary, String description, ProjectTaskType taskType,
      TaskPriority priority, ProjectTaskVisibility visibility, String relevantTeam,
      LinkedEntityType linkedEntityType, String linkedEntityId, String linkedEntityRef,
      String parentTaskId, Instant dueAt, Instant plannedStartAt, Instant plannedEndAt,
      Instant startedAt, Instant completedAt, Long expectedVersion) {}

  /**
   * Time somebody spent on the item (TM-A05).
   *
   * <p>{@code username} is optional and defaults to the caller. It exists so a manager can record
   * effort on behalf of somebody who worked offline; the service refuses it to anybody else, because
   * hours attributed to a person who did not log them are a claim about that person.
   */
  public record TimeEntryRequest(String username, LocalDate workDate, Integer durationMinutes,
      String note) {}

  public record TimeEntryResponse(Long id, String username, LocalDate workDate, int durationMinutes,
      String note, String createdBy, Instant createdAt, Instant updatedAt, boolean mayEdit) {}

  /** One journey made for the item, with what it cost in time and money (TM-A06). */
  public record TravelEntryRequest(String traveller, LocalDate travelDate, String fromLocation,
      String toLocation, String purpose, Integer travelMinutes, BigDecimal expenseAmount,
      String currencyCode, String voucherRef) {}

  public record TravelEntryResponse(Long id, String traveller, LocalDate travelDate,
      String fromLocation, String toLocation, String purpose, int travelMinutes,
      BigDecimal expenseAmount, String currencyCode, String voucherRef, String createdBy,
      Instant createdAt, Instant updatedAt, boolean mayEdit) {}

  /**
   * What the item has cost so far.
   *
   * <p>Work and travel minutes are two figures and never one. Adding them would hide the thing worth
   * knowing - that a two-hour job carried five hours of travel - inside a single seven-hour total.
   */
  public record EffortResponse(int workMinutes, int travelMinutes, BigDecimal expenseTotal,
      String expenseCurrency) {}

  /**
   * Starts the order-to-cash chain for one customer order.
   *
   * <p>Manual today. When the Operations service publishes {@code OrderReleased.v1} this is what its
   * consumer will call, which is why the payload is the event's fields and nothing more.
   */
  public record CreateOrderChainRequest(
      String orderId, String orderReference, String customerName, String relevantTeam,
      TaskPriority priority, ProjectTaskVisibility visibility, String ownerUserId, Instant dueAt) {}

  public record TransitionRequest(ProjectTaskStatus status, String comment, Long expectedVersion) {}

  public record ClaimRequest(String comment, Long expectedVersion) {}

  public record ReassignRequest(String ownerUserId, String comment, Long expectedVersion) {}

  public record CommentRequest(String body) {}

  public record ChecklistToggleRequest(Boolean completed) {}

  /** Put someone on a work item. The username is the identity, as everywhere else on the item. */
  public record AssigneeRequest(String username) {}

  public record AssigneeResponse(Long id, String username, String addedBy, Instant addedAt) {}

  /**
   * One event on the item.
   *
   * <p>{@code onBehalfOf} is null for the ordinary case and set when the actor was standing in for
   * somebody under a delegation (REQ-0027). Both are served, because either alone is misleading: the
   * delegate did it, and the owner was accountable for it.
   */
  public record HistoryResponse(Long id, String action, ProjectTaskStatus fromStatus,
      ProjectTaskStatus toStatus, String actor, String onBehalfOf, String reason, Instant occurredAt,
      String correlationId) {}

  public record CommentResponse(Long id, String author, String body, Instant createdAt) {}

  public record ChecklistItemResponse(Long id, int sequenceNo, String title, boolean required,
      boolean completed, String completedBy, Instant completedAt) {}

  public record LinkedEntityResponse(LinkedEntityType type, String id, String reference) {}

  /**
   * The business party this task is about, or null when the viewer is not entitled to know.
   *
   * <p>TM-012 keeps customer identity with the people doing the work, so a viewer who reaches the
   * task only through its audience gets null here rather than a redacted shape - an absent field
   * says "not for you" without also saying "and there is one".
   */
  public record MasterDataResponse(Long organizationId, Long customerId, Long vendorId) {}

  /**
   * List row. Omits history, comments and checklist so a queue does not pay for detail nobody read.
   *
   * <p>{@code escalationLevel} is the highest SLA rung raised on this item: 0 for one still inside
   * its deadline, 1 for a warning, 2 for a breach and higher for each escalation beyond it. Served
   * so a queue can show that an item has been escalated - {@code overdue} alone cannot distinguish
   * a task an hour late from one nobody has touched in a week.
   */
  public record ProjectTaskSummary(
      String id, long version, String title, ProjectTaskType taskType, TaskPriority priority,
      ProjectTaskStatus status, ProjectTaskVisibility visibility, String relevantTeam,
      String ownerUserId, String parentTaskId, LinkedEntityResponse linkedEntity,
      Instant createdAt, Instant dueAt, Instant completedAt, boolean overdue, int escalationLevel,
      int childCount, boolean archived) {}

  /**
   * Full detail.
   *
   * <p>{@code allowedNextStatuses} is served rather than reimplemented in each client: the lifecycle
   * is one table in {@link ProjectTaskStatus} and a UI that hardcodes its own copy will eventually
   * offer a button the server rejects, which is exactly the defect this rework set out to fix.
   * {@code mayAct} and {@code mayClaim} are resolved for the calling user for the same reason.
   */
  public record ProjectTaskDetail(
      String id, long version, String title, String summary, String description,
      ProjectTaskType taskType,
      TaskPriority priority, ProjectTaskStatus status, ProjectTaskVisibility visibility,
      String relevantTeam, String ownerUserId, String createdBy, String parentTaskId,
      LinkedEntityResponse linkedEntity, Instant createdAt, Instant dueAt,
      Instant plannedStartAt, Instant plannedEndAt, Instant startedAt,
      Instant completedAt, Instant cancelledAt, Instant archivedAt, boolean overdue,
      int escalationLevel, List<ProjectTaskStatus> allowedNextStatuses, boolean mayAct, boolean mayClaim,
      /** Whether this viewer may change the item's fields right now - false once it closes (TM-A03). */
      boolean mayEdit,
      boolean requiredChecklistComplete, List<ProjectTaskSummary> children,
      List<ChecklistItemResponse> checklist, List<CommentResponse> comments,
      List<AssigneeResponse> assignees, boolean mayManageAssignees,
      /** Null unless the viewer is entitled to the task - see ProjectTaskVisibilityPolicy#isEntitled. */
      MasterDataResponse masterData,
      /** Empty for an audience-only viewer: an audit trail is not metadata, status or a comment. */
      List<HistoryResponse> history,
      /**
       * Whether the timesheet and travel tabs have anything to show this viewer (TM-A05).
       *
       * <p>Served rather than inferred from the three fields below, because empty and withheld are
       * different answers and a client cannot tell them apart from absence alone.
       */
      boolean mayViewEffort,
      /** Null, not zeroed, when {@code mayViewEffort} is false - absent says "not for you". */
      EffortResponse effort,
      List<TimeEntryResponse> timeEntries, List<TravelEntryResponse> travelEntries) {}
}
