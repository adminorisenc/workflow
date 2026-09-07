package com.orisenc.workflow.task;

import static com.orisenc.workflow.task.TaskDtos.*;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.delegation.DelegationTarget;
import com.orisenc.workflow.notify.WorkItemNotification;
import com.orisenc.workflow.sla.SlaFamily;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Business logic and persistence for REQ-0022 (Workflow / Task Management).
//
// The controller decides whether the caller may invoke an operation at all through permission
// checks; this class enforces the record-level rule
// that only the current assignee may act on a specific task.
@Service
public class TaskService {

  private final EntityManager entityManager;
  private final ApplicationEventPublisher events;
  private final DelegationService delegations;
  private final Clock clock = Clock.systemUTC();

  public TaskService(EntityManager entityManager, ApplicationEventPublisher events,
      DelegationService delegations) {
    this.entityManager = entityManager;
    this.events = events;
    this.delegations = delegations;
  }

  @Transactional(readOnly = true)
  public List<TaskResponse> list(String department, TaskStatus status, String assignee, String query) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    return entityManager
        .createQuery("select t from TaskEntity t order by t.dueAt asc", TaskEntity.class)
        .getResultList().stream()
        .filter(t -> department == null || department.isBlank() || t.getDepartment().equalsIgnoreCase(department))
        .filter(t -> status == null || t.getStatus() == status)
        .filter(t -> assignee == null || assignee.isBlank() || assignee.equalsIgnoreCase(t.getAssignee()))
        .filter(t -> q.isEmpty() || (t.getId() + " " + t.getTitle() + " " + t.getReference() + " " + t.getRequester())
            .toLowerCase(Locale.ROOT).contains(q))
        .map(t -> response(t, false)).toList();
  }

  @Transactional(readOnly = true)
  public TaskResponse get(String id) {
    return response(required(id), true);
  }

  @Transactional
  public TaskResponse create(CreateTaskRequest request, String actor, String correlationId) {
    validate(request);
    Instant now = clock.instant();
    String id = "TSK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    var task = new TaskEntity(id, request.title().trim(), request.reference().trim(), request.department().trim(),
        request.type(), request.priority(), request.requester().trim(),
        blankToNull(request.assignee()), request.summary().trim(), blankToNull(request.requestValue()), now, request.dueAt());
    task.addHistory("CREATED", actor, null, now, correlationId);
    entityManager.persist(task);
    entityManager.flush();
    // An approval raised straight onto somebody's desk is an assignment; one raised into the
    // unassigned queue is not news until a person picks it up.
    announceAssignment(task, actor);
    return response(task, true);
  }

  @Transactional
  public TaskResponse act(String id, ActionRequest request, String actor, String correlationId) {
    if (request == null || request.action() == null) throw ApiException.badRequest("An action is required");
    if (request.expectedVersion() == null) throw ApiException.badRequest("expectedVersion is required");
    var task = required(id);
    if (task.getVersion() != request.expectedVersion())
      throw ApiException.conflict("Task was changed; reload and retry");
    if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.REJECTED)
      throw ApiException.conflict("Task is already closed");
    // Checked here rather than left to the database: the comment column is 500 characters, and
    // without this a longer comment fails as a constraint violation - a 500 on an approval decision
    // instead of a message telling the approver to shorten it.
    if (request.comment() != null && request.comment().trim().length() > TaskHistoryEntity.MAX_COMMENT)
      throw ApiException.badRequest(
          "A comment cannot be longer than " + TaskHistoryEntity.MAX_COMMENT + " characters.");
    Instant now = clock.instant();
    TaskStatus previous = task.getStatus();
    // "A task may be completed only by its assignee or an authorized delegate" - the blueprint's
    // words, and REQ-0022's own acceptance criterion. Resolved once, before the switch, because all
    // four deciding actions ask the same question; claiming does not, because an unclaimed task has
    // no assignee to stand in for.
    String onBehalfOf = request.action() == TaskAction.CLAIM ? null : requireAssigneeOrDelegate(task, actor);
    switch (request.action()) {
      case CLAIM -> { task.assignTo(actor); task.addHistory("CLAIMED", actor, request.comment(), now, correlationId); }
      case START -> { task.transition(TaskStatus.IN_PROGRESS, now); task.addHistory("STARTED", actor, onBehalfOf, request.comment(), now, correlationId); }
      case APPROVE -> {
        if (task.getType() != TaskType.APPROVAL) throw ApiException.badRequest("Only approval tasks can be approved");
        task.transition(TaskStatus.COMPLETED, now); task.addHistory("APPROVED", actor, onBehalfOf, request.comment(), now, correlationId);
      }
      case REJECT -> {
        if (request.comment() == null || request.comment().isBlank()) throw ApiException.badRequest("A comment is required when rejecting");
        task.transition(TaskStatus.REJECTED, now); task.addHistory("REJECTED", actor, onBehalfOf, request.comment().trim(), now, correlationId);
      }
      case COMPLETE -> {
        if (task.getType() == TaskType.APPROVAL) throw ApiException.badRequest("Approval tasks must be approved or rejected");
        task.transition(TaskStatus.COMPLETED, now); task.addHistory("COMPLETED", actor, onBehalfOf, request.comment(), now, correlationId);
      }
    }
    entityManager.flush();
    // A claim assigns; every other action decides. Both are announced, but a decision is what the
    // requester is waiting on, which is why only that one widens beyond the assignee.
    if (task.getStatus() != previous)
      announceStatusChange(task, actor, previous, request.comment());
    else
      announceAssignment(task, actor);
    return response(task, true);
  }

  // ---------------------------------------------------------------- notification

  /**
   * Announces that this approval now sits with somebody.
   *
   * <p>Published rather than posted, so {@code WorkItemNotifier} raises it after this transaction
   * commits: a rolled-back assignment must not leave a message behind, and no HTTP call belongs
   * inside an open write transaction. Silent when nobody holds it, and silent when the assignee is
   * the actor - a person who just claimed a task does not need telling that they did.
   */
  private void announceAssignment(TaskEntity task, String actor) {
    if (task.getAssignee() == null || task.getAssignee().equalsIgnoreCase(actor)) return;
    events.publishEvent(new WorkItemNotification(WorkItemNotification.Kind.ASSIGNED, SlaFamily.APPROVAL,
        task.getId(), task.getTitle(), eventRef(task), task.getAssignee(), task.getRequester(), actor,
        task.getStatus().name(), null, null, task.getDueAt()));
  }

  /** Announces a decision to the assignee and to whoever asked for it. */
  private void announceStatusChange(TaskEntity task, String actor, TaskStatus previous, String comment) {
    events.publishEvent(new WorkItemNotification(WorkItemNotification.Kind.STATUS_CHANGED,
        SlaFamily.APPROVAL, task.getId(), task.getTitle(), eventRef(task), task.getAssignee(),
        task.getRequester(), actor, task.getStatus().name(), previous.name(), comment, task.getDueAt()));
  }

  /**
   * What makes one occurrence distinct: the id of the history row that just recorded it.
   *
   * <p>The task id alone would not do - the Integration Service is idempotent on the event, the
   * reference and the recipient, so a task claimed, released and claimed again would announce only
   * the first. The history row is written in the same call and flushed before this runs, so its id
   * is unique and points at the exact audit entry the message describes.
   */
  private static String eventRef(TaskEntity task) {
    var history = task.getHistory();
    return history.isEmpty() ? task.getId() : task.getId() + "#" + history.getLast().getId();
  }

  private void validate(CreateTaskRequest request) {
    if (request == null) throw ApiException.badRequest("A task body is required");
    require(request.title(), "title");
    require(request.reference(), "reference");
    require(request.department(), "department");
    if (request.type() == null) throw ApiException.badRequest("type is required");
    if (request.priority() == null) throw ApiException.badRequest("priority is required");
    require(request.requester(), "requester");
    require(request.summary(), "summary");
    if (request.dueAt() == null) throw ApiException.badRequest("dueAt is required");
  }

  private void require(String value, String field) {
    if (value == null || value.isBlank()) throw ApiException.badRequest(field + " is required");
  }

  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

  private TaskEntity required(String id) {
    return entityManager
        .createQuery("select t from TaskEntity t left join fetch t.history where t.id = :id", TaskEntity.class)
        .setParameter("id", id).getResultStream().findFirst()
        .orElseThrow(() -> ApiException.notFound("Task not found"));
  }

  /**
   * The assignee whose authority the actor is using, or null when it is their own.
   *
   * <p>A delegate still needs the permission for the action - {@code TaskPermissions.requireForAction}
   * has already run in the controller. What the delegation adds is the record-level right to take
   * that action on somebody else's task; it does not grant the action itself. Common Platform is the
   * RBAC store, and a row in Workflow's own database must not be able to mint an authority the
   * access service never gave.
   *
   * @throws ApiException 403 when the actor is neither the assignee nor covered by a delegation in
   *     force for this task's type and department
   */
  private String requireAssigneeOrDelegate(TaskEntity task, String actor) {
    if (task.getAssignee() != null && task.getAssignee().equalsIgnoreCase(actor)) return null;
    if (task.getAssignee() != null
        && delegations.authorising(actor, task.getAssignee(),
            DelegationTarget.approval(task.getType().name(), task.getDepartment())).isPresent())
      return task.getAssignee();
    throw ApiException.forbidden("Only the assignee or an authorized delegate can perform this action");
  }

  private TaskResponse response(TaskEntity task, boolean history) {
    var events = history ? task.getHistory().stream()
        .map(h -> new HistoryResponse(h.getId(), h.getAction(), h.getActor(), h.getOnBehalfOf(), h.getComment(), h.getOccurredAt(), h.getCorrelationId())).toList()
        : List.<HistoryResponse>of();
    return new TaskResponse(task.getId(), task.getVersion(), task.getTitle(), task.getReference(), task.getDepartment(),
        task.getType(), task.getPriority(), task.getStatus(), task.getRequester(), task.getAssignee(),
        task.getSummary(), task.getRequestValue(), task.getCreatedAt(), task.getDueAt(), task.getCompletedAt(),
        task.getCompletedAt() == null && task.getDueAt().isBefore(clock.instant()), events);
  }
}
