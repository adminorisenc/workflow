package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.task.TaskPriority;

import java.time.Instant;
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
      String parentTaskId, Instant dueAt, List<ChecklistItemInput> checklist) {}

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
      String id, long version, String title, String description, ProjectTaskType taskType,
      TaskPriority priority, ProjectTaskStatus status, ProjectTaskVisibility visibility,
      String relevantTeam, String ownerUserId, String createdBy, String parentTaskId,
      LinkedEntityResponse linkedEntity, Instant createdAt, Instant dueAt, Instant startedAt,
      Instant completedAt, Instant cancelledAt, Instant archivedAt, boolean overdue,
      int escalationLevel, List<ProjectTaskStatus> allowedNextStatuses, boolean mayAct, boolean mayClaim,
      boolean requiredChecklistComplete, List<ProjectTaskSummary> children,
      List<ChecklistItemResponse> checklist, List<CommentResponse> comments,
      List<HistoryResponse> history) {}
}
