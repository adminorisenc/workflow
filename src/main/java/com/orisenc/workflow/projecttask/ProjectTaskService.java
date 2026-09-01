package com.orisenc.workflow.projecttask;

import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Project work items: the order-to-cash chain and the ad-hoc work around it (USR-TASK-001).
 *
 * <p>Three rules shape this class, each one a correction of something the approval task family got
 * wrong:
 *
 * <ol>
 *   <li>Every read goes through {@link ProjectTaskVisibilityPolicy}. The list query carries the
 *       audience predicate in its {@code where} clause rather than filtering afterwards, so an
 *       unauthorized row is never loaded and paging counts stay honest.
 *   <li>Filtering and paging happen in the database. The approval list loads the whole table and
 *       filters in the JVM, which makes its indexes ornamental.
 *   <li>Status changes go through {@link ProjectTaskEntity#transition}, which will not move a task
 *       without writing the reason. The service never sets a status directly.
 * </ol>
 */
@Service
public class ProjectTaskService {

  /** Caps an unbounded request; a queue page nobody reads should not be able to load the table. */
  private static final int MAX_PAGE_SIZE = 200;
  private static final int DEFAULT_PAGE_SIZE = 50;
  /** Depth bound for the parent walk. Chains are a handful deep; anything more is a mistake. */
  private static final int MAX_HIERARCHY_DEPTH = 10;

  private final EntityManager entityManager;
  private final Clock clock;

  public ProjectTaskService(EntityManager entityManager) {
    this(entityManager, Clock.systemUTC());
  }

  ProjectTaskService(EntityManager entityManager, Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /** Filter criteria. Every field is optional; nulls mean "do not narrow on this". */
  public record ListQuery(ProjectTaskStatus status, String ownerUserId, String relevantTeam,
      ProjectTaskType taskType, String parentTaskId, LinkedEntityType linkedEntityType,
      String linkedEntityId, Boolean openOnly, Boolean includeArchived, String search,
      Integer page, Integer size) {}

  @Transactional(readOnly = true)
  public List<ProjectTaskSummary> list(ListQuery query, String actor, Set<String> permissions) {
    if (!permissions.contains(ProjectTaskPermissions.VIEW))
      throw ApiException.forbidden("You do not have permission to view work items.");

    var conditions = new ArrayList<String>();
    var parameters = new LinkedHashMap<String, Object>();

    String audience = ProjectTaskVisibilityPolicy.listPredicate(permissions);
    if (!audience.isEmpty()) {
      conditions.add(audience);
      parameters.put("actor", actor);
    }
    if (query.status() != null) { conditions.add("t.status = :status"); parameters.put("status", query.status()); }
    if (notBlank(query.ownerUserId())) {
      // "me" resolves to the caller here rather than in the client. A UI cannot know which of
      // several identity strings the service stores against ownership, and a wrong guess returns an
      // empty list that looks like "you have no work" instead of an error.
      String owner = "me".equalsIgnoreCase(query.ownerUserId().trim()) ? actor : query.ownerUserId().trim();
      conditions.add("lower(t.ownerUserId) = :owner");
      parameters.put("owner", owner.toLowerCase(Locale.ROOT));
    }
    if (notBlank(query.relevantTeam())) {
      conditions.add("lower(t.relevantTeam) = :team");
      parameters.put("team", query.relevantTeam().trim().toLowerCase(Locale.ROOT));
    }
    if (query.taskType() != null) { conditions.add("t.taskType = :type"); parameters.put("type", query.taskType()); }
    if (notBlank(query.parentTaskId())) {
      conditions.add("t.parentTaskId = :parent");
      parameters.put("parent", query.parentTaskId().trim());
    }
    if (query.linkedEntityType() != null) {
      conditions.add("t.linkedEntityType = :linkedType");
      parameters.put("linkedType", query.linkedEntityType());
    }
    if (notBlank(query.linkedEntityId())) {
      conditions.add("t.linkedEntityId = :linkedId");
      parameters.put("linkedId", query.linkedEntityId().trim());
    }
    if (Boolean.TRUE.equals(query.openOnly())) {
      conditions.add("t.status not in (:closed)");
      parameters.put("closed", List.of(ProjectTaskStatus.COMPLETED, ProjectTaskStatus.CANCELLED));
    }
    if (!Boolean.TRUE.equals(query.includeArchived())) conditions.add("t.archivedAt is null");
    if (notBlank(query.search())) {
      conditions.add("(lower(t.title) like :q or lower(t.id) like :q or lower(t.linkedEntityRef) like :q"
          + " or lower(t.ownerUserId) like :q)");
      parameters.put("q", "%" + query.search().trim().toLowerCase(Locale.ROOT) + "%");
    }

    String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    TypedQuery<ProjectTaskEntity> jpql = entityManager.createQuery(
        "select t from ProjectTaskEntity t" + where + " order by t.dueAt asc, t.id asc", ProjectTaskEntity.class);
    parameters.forEach(jpql::setParameter);

    int size = Math.min(query.size() == null || query.size() < 1 ? DEFAULT_PAGE_SIZE : query.size(), MAX_PAGE_SIZE);
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    jpql.setFirstResult(page * size);
    jpql.setMaxResults(size);

    var rows = jpql.getResultList();
    Map<String, Integer> childCounts = childCounts(rows.stream().map(ProjectTaskEntity::getId).toList());
    Instant now = clock.instant();
    return rows.stream().map(task -> summary(task, childCounts.getOrDefault(task.getId(), 0), now)).toList();
  }

  @Transactional(readOnly = true)
  public ProjectTaskDetail get(String id, String actor, Set<String> permissions) {
    var task = readable(id, actor, permissions);
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail create(CreateProjectTaskRequest request, String actor, String correlationId) {
    validate(request);
    Instant now = clock.instant();
    String parentId = notBlank(request.parentTaskId()) ? request.parentTaskId().trim() : null;
    if (parentId != null) assertUsableParent(parentId, null);

    var task = new ProjectTaskEntity(newId(), request.title().trim(), request.description().trim(),
        request.taskType(), request.priority(),
        // Narrowest reasonable scope by default, per TM-010.
        request.visibility() == null ? ProjectTaskVisibility.INDIVIDUAL : request.visibility(),
        request.relevantTeam().trim(), blankToNull(request.ownerUserId()), actor,
        request.linkedEntityType(), blankToNull(request.linkedEntityId()), blankToNull(request.linkedEntityRef()),
        parentId, now, request.dueAt());

    if (request.checklist() != null) {
      for (var item : request.checklist()) {
        if (item == null || !notBlank(item.title())) continue;
        task.addChecklistItem(item.title().trim(), Boolean.TRUE.equals(item.required()), now);
      }
    }
    // The one history row TM-016 exempts from needing a comment.
    task.addHistory("CREATED", actor, null, now, correlationId);
    entityManager.persist(task);
    entityManager.flush();
    return detail(task, actor, ProjectTaskPermissions.granted());
  }

  /**
   * Creates the parent work item for a customer order plus one child per stage of fulfilling it.
   *
   * <p>Stage due dates are spread evenly between now and the order's due date. Even spacing is a
   * placeholder for real per-stage SLA policy ({@code workflow_policies}), which is designed but not
   * built; it is at least monotonic, so the chain reads in order in a queue sorted by due date.
   */
  @Transactional
  public ProjectTaskDetail createOrderChain(CreateOrderChainRequest request, String actor, String correlationId) {
    if (request == null) throw ApiException.badRequest("An order body is required");
    require(request.orderReference(), "orderReference");
    require(request.relevantTeam(), "relevantTeam");
    if (request.dueAt() == null) throw ApiException.badRequest("dueAt is required");

    Instant now = clock.instant();
    if (!request.dueAt().isAfter(now)) throw ApiException.badRequest("dueAt must be in the future");

    String reference = request.orderReference().trim();
    String team = request.relevantTeam().trim();
    TaskPriority priority = request.priority() == null ? TaskPriority.MEDIUM : request.priority();
    ProjectTaskVisibility visibility =
        request.visibility() == null ? ProjectTaskVisibility.RELEVANT_TEAM : request.visibility();
    String owner = blankToNull(request.ownerUserId());
    String customer = notBlank(request.customerName()) ? request.customerName().trim() : "the customer";

    var parent = new ProjectTaskEntity(newId(), "Fulfil order " + reference,
        "End-to-end fulfilment of order " + reference + " for " + customer
            + ": procurement, receipt, delivery, proof of delivery, invoicing and collection.",
        ProjectTaskType.DELIVERABLE, priority, visibility, team, owner, actor,
        LinkedEntityType.SALES_ORDER, blankToNull(request.orderId()), reference, null, now, request.dueAt());
    parent.addHistory("CREATED", actor, null, now, correlationId);
    entityManager.persist(parent);

    var stages = OrderToCashChain.STAGES;
    long span = Math.max(Duration.between(now, request.dueAt()).toMinutes(), stages.size());
    for (int index = 0; index < stages.size(); index++) {
      var stage = stages.get(index);
      Instant stageDue = now.plus(Duration.ofMinutes(span * (index + 1) / stages.size()));
      var child = new ProjectTaskEntity(newId(), String.format(stage.titleFormat(), reference),
          stage.description(), stage.taskType(), priority, visibility, team, owner, actor,
          stage.linkedEntityType(), null, reference, parent.getId(), now, stageDue);
      stage.checklist().forEach(item -> child.addChecklistItem(item, true, now));
      child.addHistory("CREATED", actor, null, now, correlationId);
      entityManager.persist(child);
    }
    entityManager.flush();
    return detail(parent, actor, ProjectTaskPermissions.granted());
  }

  @Transactional
  public ProjectTaskDetail transition(String id, TransitionRequest request, String actor, String correlationId) {
    if (request == null || request.status() == null) throw ApiException.badRequest("A target status is required");
    var permissions = ProjectTaskPermissions.granted();
    var task = readable(id, actor, permissions);
    assertVersion(task, request.expectedVersion());
    if (!ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions))
      throw ApiException.forbidden("Only the owner of this work item can change its status.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    if (request.status() == ProjectTaskStatus.COMPLETED && !task.requiredChecklistComplete())
      throw ApiException.badRequest("Every required checklist item must be completed first.");

    try {
      task.transition(request.status(), actor, request.comment(), clock.instant(), correlationId);
    } catch (IllegalArgumentException rejected) {
      // The entity guards the lifecycle; the service only translates its refusal to the API contract.
      throw ApiException.badRequest(rejected.getMessage());
    }
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail claim(String id, ClaimRequest request, String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var task = readable(id, actor, permissions);
    assertVersion(task, request == null ? null : request.expectedVersion());
    if (!ProjectTaskVisibilityPolicy.mayClaim(task, actor, permissions))
      throw ApiException.forbidden("This work item is already owned by someone else.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");
    if (task.getStatus().closed()) throw ApiException.conflict("This work item is already closed.");

    task.assignTo(actor);
    // Claiming is an assignment, not a lifecycle move, so it does not require a comment.
    task.addHistory("CLAIMED", actor, request == null ? null : blankToNull(request.comment()),
        clock.instant(), correlationId);
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail reassign(String id, ReassignRequest request, String actor, String correlationId) {
    if (request == null || !notBlank(request.ownerUserId()))
      throw ApiException.badRequest("A new owner is required");
    if (!notBlank(request.comment())) throw ApiException.badRequest("A comment is required when reassigning");
    var permissions = ProjectTaskPermissions.granted();
    ProjectTaskPermissions.require(ProjectTaskPermissions.MANAGE,
        "Your role does not permit reassigning work items.");
    var task = readable(id, actor, permissions);
    assertVersion(task, request.expectedVersion());
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    String previous = task.getOwnerUserId() == null ? "nobody" : task.getOwnerUserId();
    task.assignTo(request.ownerUserId().trim());
    task.addHistory("REASSIGNED", actor,
        "From " + previous + " to " + request.ownerUserId().trim() + ": " + request.comment().trim(),
        clock.instant(), correlationId);
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail archive(String id, String comment, String actor, String correlationId) {
    if (!notBlank(comment)) throw ApiException.badRequest("A comment is required when archiving");
    var permissions = ProjectTaskPermissions.granted();
    ProjectTaskPermissions.require(ProjectTaskPermissions.MANAGE,
        "Your role does not permit archiving work items.");
    var task = readable(id, actor, permissions);
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is already archived.");

    Instant now = clock.instant();
    task.archive(now);
    task.addHistory("ARCHIVED", actor, comment.trim(), now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail comment(String id, CommentRequest request, String actor) {
    if (request == null || !notBlank(request.body())) throw ApiException.badRequest("A comment is required");
    if (request.body().trim().length() > ProjectTaskCommentEntity.MAX_BODY)
      throw ApiException.badRequest("A comment cannot be longer than "
          + ProjectTaskCommentEntity.MAX_BODY + " characters.");
    var permissions = ProjectTaskPermissions.granted();
    var task = readable(id, actor, permissions);
    if (!permissions.contains(ProjectTaskPermissions.EXECUTE)
        && !permissions.contains(ProjectTaskPermissions.MANAGE))
      throw ApiException.forbidden("Your role does not permit commenting on work items.");

    task.addComment(actor, request.body().trim(), clock.instant());
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail toggleChecklistItem(String id, Long itemId, ChecklistToggleRequest request,
      String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var task = readable(id, actor, permissions);
    if (!ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions))
      throw ApiException.forbidden("Only the owner of this work item can update its checklist.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    var item = task.getChecklist().stream().filter(candidate -> candidate.getId().equals(itemId)).findFirst()
        .orElseThrow(() -> ApiException.notFound("Checklist item not found"));
    boolean complete = request == null || request.completed() == null || request.completed();
    Instant now = clock.instant();
    if (complete) {
      item.complete(actor, now);
      task.addHistory("CHECKLIST_COMPLETED", actor, item.getTitle(), now, correlationId);
    } else {
      item.reopen();
      task.addHistory("CHECKLIST_REOPENED", actor, item.getTitle(), now, correlationId);
    }
    entityManager.flush();
    return detail(task, actor, permissions);
  }

  // ---------------------------------------------------------------- internals

  private ProjectTaskEntity readable(String id, String actor, Set<String> permissions) {
    var task = entityManager.find(ProjectTaskEntity.class, id);
    // A task the caller may not see reports as missing rather than forbidden: a 403 would confirm
    // that a work item with this id exists, which is itself information the caller is not entitled
    // to (policy P-06 - the same decision in every channel).
    if (task == null || !ProjectTaskVisibilityPolicy.mayView(task, actor, permissions))
      throw ApiException.notFound("Work item not found");
    return task;
  }

  private void assertVersion(ProjectTaskEntity task, Long expectedVersion) {
    if (expectedVersion == null) throw ApiException.badRequest("expectedVersion is required");
    if (task.getVersion() != expectedVersion)
      throw ApiException.conflict("Work item was changed; reload and retry");
  }

  private void validate(CreateProjectTaskRequest request) {
    if (request == null) throw ApiException.badRequest("A work item body is required");
    require(request.title(), "title");
    require(request.description(), "description");
    require(request.relevantTeam(), "relevantTeam");
    if (request.taskType() == null) throw ApiException.badRequest("taskType is required");
    if (request.priority() == null) throw ApiException.badRequest("priority is required");
    if (request.dueAt() == null) throw ApiException.badRequest("dueAt is required");
    if (request.title().trim().length() > 200)
      throw ApiException.badRequest("title cannot be longer than 200 characters.");
    if (request.description().trim().length() > 4000)
      throw ApiException.badRequest("description cannot be longer than 4000 characters.");
    if (request.linkedEntityType() != null && request.linkedEntityType() != LinkedEntityType.NONE
        && !notBlank(request.linkedEntityId()) && !notBlank(request.linkedEntityRef()))
      throw ApiException.badRequest("A linked record needs an id or a reference.");
  }

  /**
   * Rejects a parent that does not exist, is the task itself, or would close a cycle.
   *
   * @param childId the task being re-parented, or null when creating a new one
   */
  private void assertUsableParent(String parentId, String childId) {
    if (parentId.equals(childId)) throw ApiException.badRequest("A work item cannot be its own parent.");
    var cursor = entityManager.find(ProjectTaskEntity.class, parentId);
    if (cursor == null) throw ApiException.badRequest("The parent work item does not exist.");
    for (int depth = 0; cursor != null && depth < MAX_HIERARCHY_DEPTH; depth++) {
      if (childId != null && cursor.getId().equals(childId))
        throw ApiException.badRequest("That parent would create a loop in the work item hierarchy.");
      String next = cursor.getParentTaskId();
      cursor = next == null ? null : entityManager.find(ProjectTaskEntity.class, next);
    }
    if (cursor != null)
      throw ApiException.badRequest("The work item hierarchy is too deep.");
  }

  private Map<String, Integer> childCounts(List<String> parentIds) {
    if (parentIds.isEmpty()) return Map.of();
    var counts = new LinkedHashMap<String, Integer>();
    entityManager.createQuery(
            "select t.parentTaskId, count(t) from ProjectTaskEntity t"
                + " where t.parentTaskId in :ids and t.archivedAt is null group by t.parentTaskId", Object[].class)
        .setParameter("ids", parentIds).getResultList()
        .forEach(row -> counts.put((String) row[0], ((Number) row[1]).intValue()));
    return counts;
  }

  private List<ProjectTaskEntity> children(String parentId) {
    return entityManager.createQuery(
            "select t from ProjectTaskEntity t where t.parentTaskId = :parent and t.archivedAt is null"
                + " order by t.dueAt asc, t.id asc", ProjectTaskEntity.class)
        .setParameter("parent", parentId).getResultList();
  }

  private static String newId() {
    return "WRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private static void require(String value, String field) {
    if (!notBlank(value)) throw ApiException.badRequest(field + " is required");
  }

  private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

  private static String blankToNull(String value) { return notBlank(value) ? value.trim() : null; }

  // ---------------------------------------------------------------- mapping

  private ProjectTaskSummary summary(ProjectTaskEntity task, int childCount, Instant now) {
    return new ProjectTaskSummary(task.getId(), task.getVersion(), task.getTitle(), task.getTaskType(),
        task.getPriority(), task.getStatus(), task.getVisibility(), task.getRelevantTeam(),
        task.getOwnerUserId(), task.getParentTaskId(), linked(task), task.getCreatedAt(), task.getDueAt(),
        task.getCompletedAt(), task.overdue(now), childCount, task.getArchivedAt() != null);
  }

  private ProjectTaskDetail detail(ProjectTaskEntity task, String actor, Set<String> permissions) {
    Instant now = clock.instant();
    var childEntities = children(task.getId());
    var childSummaries = childEntities.stream().map(child -> summary(child, 0, now)).toList();
    return new ProjectTaskDetail(task.getId(), task.getVersion(), task.getTitle(), task.getDescription(),
        task.getTaskType(), task.getPriority(), task.getStatus(), task.getVisibility(),
        task.getRelevantTeam(), task.getOwnerUserId(), task.getCreatedBy(), task.getParentTaskId(),
        linked(task), task.getCreatedAt(), task.getDueAt(), task.getStartedAt(), task.getCompletedAt(),
        task.getCancelledAt(), task.getArchivedAt(), task.overdue(now),
        List.copyOf(task.getStatus().allowedNext()),
        ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions),
        ProjectTaskVisibilityPolicy.mayClaim(task, actor, permissions),
        task.requiredChecklistComplete(), childSummaries,
        task.getChecklist().stream().map(item -> new ChecklistItemResponse(item.getId(), item.getSequenceNo(),
            item.getTitle(), item.isRequired(), item.isCompleted(), item.getCompletedBy(),
            item.getCompletedAt())).toList(),
        task.getComments().stream().map(entry -> new CommentResponse(entry.getId(), entry.getAuthor(),
            entry.getBody(), entry.getCreatedAt())).toList(),
        task.getHistory().stream().map(event -> new HistoryResponse(event.getId(), event.getAction(),
            event.getFromStatus(), event.getToStatus(), event.getActor(), event.getReason(),
            event.getOccurredAt(), event.getCorrelationId())).toList());
  }

  private static LinkedEntityResponse linked(ProjectTaskEntity task) {
    return new LinkedEntityResponse(task.getLinkedEntityType(), task.getLinkedEntityId(),
        task.getLinkedEntityRef());
  }
}
