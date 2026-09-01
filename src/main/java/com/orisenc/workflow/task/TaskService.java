package com.orisenc.workflow.task;

import static com.orisenc.workflow.task.TaskDtos.*;

import com.orisenc.workflow.api.ApiException;
import jakarta.persistence.EntityManager;
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
  private final Clock clock = Clock.systemUTC();

  public TaskService(EntityManager entityManager) {
    this.entityManager = entityManager;
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
    switch (request.action()) {
      case CLAIM -> { task.assignTo(actor); task.addHistory("CLAIMED", actor, request.comment(), now, correlationId); }
      case START -> { assertAssignee(task, actor); task.transition(TaskStatus.IN_PROGRESS, now); task.addHistory("STARTED", actor, request.comment(), now, correlationId); }
      case APPROVE -> {
        assertAssignee(task, actor);
        if (task.getType() != TaskType.APPROVAL) throw ApiException.badRequest("Only approval tasks can be approved");
        task.transition(TaskStatus.COMPLETED, now); task.addHistory("APPROVED", actor, request.comment(), now, correlationId);
      }
      case REJECT -> {
        assertAssignee(task, actor);
        if (request.comment() == null || request.comment().isBlank()) throw ApiException.badRequest("A comment is required when rejecting");
        task.transition(TaskStatus.REJECTED, now); task.addHistory("REJECTED", actor, request.comment().trim(), now, correlationId);
      }
      case COMPLETE -> {
        assertAssignee(task, actor);
        if (task.getType() == TaskType.APPROVAL) throw ApiException.badRequest("Approval tasks must be approved or rejected");
        task.transition(TaskStatus.COMPLETED, now); task.addHistory("COMPLETED", actor, request.comment(), now, correlationId);
      }
    }
    entityManager.flush();
    return response(task, true);
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

  private void assertAssignee(TaskEntity task, String actor) {
    if (task.getAssignee() == null || !task.getAssignee().equalsIgnoreCase(actor))
      throw ApiException.forbidden("Only the assignee can perform this action");
  }

  private TaskResponse response(TaskEntity task, boolean history) {
    var events = history ? task.getHistory().stream()
        .map(h -> new HistoryResponse(h.getId(), h.getAction(), h.getActor(), h.getComment(), h.getOccurredAt(), h.getCorrelationId())).toList()
        : List.<HistoryResponse>of();
    return new TaskResponse(task.getId(), task.getVersion(), task.getTitle(), task.getReference(), task.getDepartment(),
        task.getType(), task.getPriority(), task.getStatus(), task.getRequester(), task.getAssignee(),
        task.getSummary(), task.getRequestValue(), task.getCreatedAt(), task.getDueAt(), task.getCompletedAt(),
        task.getCompletedAt() == null && task.getDueAt().isBefore(clock.instant()), events);
  }
}
