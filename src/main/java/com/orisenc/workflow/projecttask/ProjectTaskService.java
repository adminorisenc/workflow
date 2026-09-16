package com.orisenc.workflow.projecttask;

import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.delegation.DelegationCover;
import com.orisenc.workflow.delegation.DelegationScope;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.notify.WorkItemNotification;
import com.orisenc.workflow.sla.SlaFamily;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
  private final ApplicationEventPublisher events;
  private final DelegationService delegations;
  private final Clock clock;

  // Two constructors: this one for Spring, the package-private one below for tests that need a
  // fixed Clock. Without @Autowired Spring cannot choose between them, falls back to looking for
  // a no-arg constructor, and the context fails to start with "No default constructor found" -
  // a failure no unit test can see, because tests call the other constructor directly.
  @Autowired
  public ProjectTaskService(EntityManager entityManager, ApplicationEventPublisher events,
      DelegationService delegations) {
    this(entityManager, events, delegations, Clock.systemUTC());
  }

  ProjectTaskService(EntityManager entityManager, ApplicationEventPublisher events,
      DelegationService delegations, Clock clock) {
    this.entityManager = entityManager;
    this.events = events;
    this.delegations = delegations;
    this.clock = clock;
  }

  /**
   * The work this caller may reach as somebody else, read once per request.
   *
   * <p>Once, not per item: a queue of two hundred rows must not become two hundred delegation
   * lookups, and every audience decision in one request should be made against the same answer.
   */
  private List<DelegationCover> cover(String actor) {
    return delegations.coverFor(actor, DelegationScope.WORK_ITEMS);
  }

  /** Filter criteria. Every field is optional; nulls mean "do not narrow on this". */
  public record ListQuery(ProjectTaskStatus status, String ownerUserId, String relevantTeam,
      ProjectTaskType taskType, String parentTaskId, LinkedEntityType linkedEntityType,
      String linkedEntityId, Boolean openOnly, Boolean includeArchived, String search,
      Boolean mine, Integer page, Integer size) {}

  @Transactional(readOnly = true)
  public List<ProjectTaskSummary> list(ListQuery query, String actor, Set<String> permissions) {
    if (!permissions.contains(ProjectTaskPermissions.VIEW))
      throw ApiException.forbidden("You do not have permission to view work items.");

    var conditions = new ArrayList<String>();
    var parameters = new LinkedHashMap<String, Object>();

    var audience = ProjectTaskVisibilityPolicy.audience(permissions, actor, cover(actor));
    if (!audience.unrestricted()) {
      conditions.add(audience.jpql());
      parameters.putAll(audience.parameters());
    }
    if (query.status() != null) { conditions.add("t.status = :status"); parameters.put("status", query.status()); }
    if (Boolean.TRUE.equals(query.mine())) {
      // "My work" is the work that is mine to do, which since named assignees is not the same as the
      // work I own: a task somebody put me on is mine, and a list that hid it would make being
      // assigned invisible to the person assigned. Kept separate from the owner filter rather than
      // folded into it - a manager filtering by somebody's ownership means ownership.
      conditions.add("(lower(t.ownerUserId) = :mine"
          + " or exists (select 1 from ProjectTaskAssigneeEntity a"
          + " where a.task = t and lower(a.username) = :mine))");
      parameters.put("mine", actor == null ? "" : actor.toLowerCase(Locale.ROOT));
    }
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
    var cover = cover(actor);
    return detail(readable(id, actor, permissions, cover), actor, permissions, cover);
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

    // TM-A02 and TM-A01: optional at creation, editable for the rest of the item's life.
    task.describe(request.summary(), request.plannedStartAt(), request.plannedEndAt());
    // TM-002's customer link. The entity refuses a task that claims both a customer and a vendor.
    if (request.organizationId() != null)
      task.linkToMaster(request.organizationId(), request.customerId(), request.vendorId());
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
    // Creating a task already assigned to somebody is an assignment, and the person it landed on is
    // the one who needs to know. Creating it unassigned announces nothing: an empty queue entry is
    // not news until somebody is accountable for it.
    announceAssignment(task, actor);
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
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    assertVersion(task, request.expectedVersion());
    if (!ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions, cover))
      throw ApiException.forbidden(
          "Only the owner of this work item, or somebody they have delegated it to, can change its status.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    if (request.status() == ProjectTaskStatus.COMPLETED && !task.requiredChecklistComplete())
      throw ApiException.badRequest("Every required checklist item must be completed first.");

    ProjectTaskStatus previous = task.getStatus();
    // Who the actor is standing in for, or null when it is their own work. Written onto the history
    // row so the audit answers both "who did it" and "whose authority was used" (REQ-0027).
    String onBehalfOf = ProjectTaskVisibilityPolicy.actingFor(task, actor, cover);
    try {
      task.transition(request.status(), actor, onBehalfOf, request.comment(), clock.instant(),
          correlationId);
    } catch (IllegalArgumentException rejected) {
      // The entity guards the lifecycle; the service only translates its refusal to the API contract.
      throw ApiException.badRequest(rejected.getMessage());
    }
    entityManager.flush();
    announceStatusChange(task, actor, request.status().name(), previous.name(), request.comment());
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail claim(String id, ClaimRequest request, String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    assertVersion(task, request == null ? null : request.expectedVersion());
    if (!ProjectTaskVisibilityPolicy.mayClaim(task, actor, permissions, cover))
      throw ApiException.forbidden("This work item is already owned by someone else.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");
    if (task.getStatus().closed()) throw ApiException.conflict("This work item is already closed.");

    task.assignTo(actor);
    // Claiming is an assignment, not a lifecycle move, so it does not require a comment.
    task.addHistory("CLAIMED", actor, request == null ? null : blankToNull(request.comment()),
        clock.instant(), correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail reassign(String id, ReassignRequest request, String actor, String correlationId) {
    if (request == null || !notBlank(request.ownerUserId()))
      throw ApiException.badRequest("A new owner is required");
    if (!notBlank(request.comment())) throw ApiException.badRequest("A comment is required when reassigning");
    var permissions = ProjectTaskPermissions.granted();
    ProjectTaskPermissions.require(ProjectTaskPermissions.MANAGE,
        "Your role does not permit reassigning work items.");
    var task = readable(id, actor, permissions, List.of());
    assertVersion(task, request.expectedVersion());
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    String previous = task.getOwnerUserId() == null ? "nobody" : task.getOwnerUserId();
    task.assignTo(request.ownerUserId().trim());
    task.addHistory("REASSIGNED", actor,
        "From " + previous + " to " + request.ownerUserId().trim() + ": " + request.comment().trim(),
        clock.instant(), correlationId);
    entityManager.flush();
    announceAssignment(task, actor);
    return detail(task, actor, permissions);
  }

  @Transactional
  public ProjectTaskDetail archive(String id, String comment, String actor, String correlationId) {
    if (!notBlank(comment)) throw ApiException.badRequest("A comment is required when archiving");
    var permissions = ProjectTaskPermissions.granted();
    ProjectTaskPermissions.require(ProjectTaskPermissions.MANAGE,
        "Your role does not permit archiving work items.");
    // No cover needed: this path already required MANAGE, which sees everything.
    var task = readable(id, actor, permissions, List.of());
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
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    if (!permissions.contains(ProjectTaskPermissions.EXECUTE)
        && !permissions.contains(ProjectTaskPermissions.MANAGE))
      throw ApiException.forbidden("Your role does not permit commenting on work items.");

    task.addComment(actor, request.body().trim(), clock.instant());
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  /**
   * Who is on this item besides its owner. Gated on view, not on the manage code: knowing who else
   * is working something you can already see is not itself sensitive, and hiding the list from the
   * people on it would make "why can she see this?" unanswerable without an administrator.
   */
  @Transactional(readOnly = true)
  public List<AssigneeResponse> assignees(String id, String actor) {
    var permissions = ProjectTaskPermissions.granted();
    var task = readable(id, actor, permissions, cover(actor));
    return task.getAssignees().stream().map(ProjectTaskService::assignee).toList();
  }

  /**
   * Puts a second person on the item.
   *
   * <p>Requires {@link ProjectTaskPermissions#ASSIGNEES_MANAGE} in the actor's own right. A delegate
   * covering the owner deliberately cannot do this: cover is temporary, and letting it hand out
   * standing access would make it permanent by the back door.
   */
  @Transactional
  public ProjectTaskDetail addAssignee(String id, AssigneeRequest request, String actor,
      String correlationId) {
    if (request == null || !notBlank(request.username()))
      throw ApiException.badRequest("A username is required.");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    requireAssigneeManagement(permissions);

    String username = request.username().trim();
    try {
      task.addAssignee(username, actor, clock.instant());
    } catch (IllegalArgumentException refused) {
      // The entity's own words - "already owns this task", "already assigned to this task".
      throw ApiException.conflict(refused.getMessage());
    }
    // TM-018: assignment changes belong in the immutable trail, not only in the current row.
    task.addHistory("ASSIGNEE_ADDED", actor, username, clock.instant(), correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail removeAssignee(String id, String username, String actor,
      String correlationId) {
    if (!notBlank(username)) throw ApiException.badRequest("A username is required.");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    requireAssigneeManagement(permissions);

    if (!task.removeAssignee(username.trim()))
      throw ApiException.notFound("That person is not assigned to this task.");
    task.addHistory("ASSIGNEE_REMOVED", actor, username.trim(), clock.instant(), correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  private static void requireAssigneeManagement(Set<String> permissions) {
    if (!permissions.contains(ProjectTaskPermissions.ASSIGNEES_MANAGE))
      throw ApiException.forbidden("Your role does not permit changing who is assigned to work items.");
  }

  private static AssigneeResponse assignee(ProjectTaskAssigneeEntity entry) {
    return new AssigneeResponse(entry.getId(), entry.getUsername(), entry.getAddedBy(),
        entry.getAddedAt());
  }

  @Transactional
  public ProjectTaskDetail toggleChecklistItem(String id, Long itemId, ChecklistToggleRequest request,
      String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    if (!ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions, cover))
      throw ApiException.forbidden(
          "Only the owner of this work item, or somebody they have delegated it to, can update its checklist.");
    if (task.getArchivedAt() != null) throw ApiException.conflict("This work item is archived.");

    var item = task.getChecklist().stream().filter(candidate -> candidate.getId().equals(itemId)).findFirst()
        .orElseThrow(() -> ApiException.notFound("Checklist item not found"));
    boolean complete = request == null || request.completed() == null || request.completed();
    Instant now = clock.instant();
    String onBehalfOf = ProjectTaskVisibilityPolicy.actingFor(task, actor, cover);
    if (complete) {
      item.complete(actor, now);
      task.addHistory("CHECKLIST_COMPLETED", actor, onBehalfOf, item.getTitle(), now, correlationId);
    } else {
      item.reopen();
      task.addHistory("CHECKLIST_REOPENED", actor, onBehalfOf, item.getTitle(), now, correlationId);
    }
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  // ---------------------------------------------------------------- editing (TM-A03)

  /**
   * Edits an open work item, recording every field that changed.
   *
   * <p>The entity decides what actually changed and this method turns each of those into its own
   * history row. One row per field rather than one per edit: an activity feed saying "the task was
   * edited" answers nothing, and TM-018 asks for who changed what.
   *
   * <p>A no-op edit writes nothing. Somebody opening the form and saving it unchanged should not
   * leave a mark on the audit trail claiming they did something.
   */
  @Transactional
  public ProjectTaskDetail update(String id, UpdateProjectTaskRequest request, String actor,
      String correlationId) {
    if (request == null) throw ApiException.badRequest("A work item body is required");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    assertVersion(task, request.expectedVersion());
    if (!ProjectTaskVisibilityPolicy.mayEdit(task, actor, permissions, cover)) {
      // Two different refusals, because they need two different answers from the person reading
      // them: one means reopen it first, the other means ask somebody else.
      if (!task.open())
        throw ApiException.conflict(
            "This work item is closed. Reopen it before changing its details.");
      throw ApiException.forbidden(
          "Only the owner of this work item, whoever raised it, or a manager can change its details.");
    }

    String newParent = blankToNull(request.parentTaskId());
    // Checked before the entity applies anything: a cycle needs the other rows in the table to
    // detect, which is knowledge the entity does not and should not have.
    if (newParent != null && !newParent.equals(task.getParentTaskId()))
      assertUsableParent(newParent, task.getId());

    List<ProjectTaskEntity.FieldChange> changes;
    try {
      changes = task.applyEdit(new ProjectTaskEntity.Edit(request.title(), request.summary(),
          request.description(), request.taskType(), request.priority(), request.visibility(),
          request.relevantTeam(), request.linkedEntityType(), request.linkedEntityId(),
          request.linkedEntityRef(), newParent, request.dueAt(), request.plannedStartAt(),
          request.plannedEndAt(), request.startedAt(), request.completedAt()));
    } catch (IllegalArgumentException rejected) {
      throw ApiException.badRequest(rejected.getMessage());
    }

    Instant now = clock.instant();
    String onBehalfOf = ProjectTaskVisibilityPolicy.actingFor(task, actor, cover);
    for (var change : changes)
      task.addHistory("FIELD_CHANGED", actor, onBehalfOf, describe(change), now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  private static String describe(ProjectTaskEntity.FieldChange change) {
    if (change.from() == null) return change.field() + " set to " + change.to();
    if (change.to() == null) return change.field() + " cleared, was " + change.from();
    return change.field() + " changed from " + change.from() + " to " + change.to();
  }

  // ---------------------------------------------------------------- time and travel

  /**
   * Records effort against the item (TM-A05).
   *
   * <p>Gated on {@link ProjectTaskVisibilityPolicy#mayAct} rather than on view: logging hours is a
   * claim about work done, and only the people actually working an item are in a position to make
   * one.
   *
   * <p>{@code username} defaults to the caller. Naming somebody else is a manager's act - attributing
   * hours to a person is a statement about that person, and anyone who could do it freely could put
   * a day's work against a colleague who was on leave.
   */
  @Transactional
  public ProjectTaskDetail logTime(String id, TimeEntryRequest request, String actor,
      String correlationId) {
    if (request == null) throw ApiException.badRequest("A time entry is required");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    assertMayRecord(task, actor, permissions, cover, "log time against");

    String username = blankToNull(request.username());
    if (username != null && !username.equalsIgnoreCase(actor)
        && !permissions.contains(ProjectTaskPermissions.MANAGE))
      throw ApiException.forbidden("You can only log time against your own name.");

    Instant now = clock.instant();
    var entry = guard(() -> task.logTime(username == null ? actor : username, request.workDate(),
        minutes(request.durationMinutes(), "Time spent"), request.note(), actor, now));
    entityManager.flush();
    task.addHistory("TIME_LOGGED", actor,
        entry.getUsername() + " logged " + duration(entry.getDurationMinutes()) + " on "
            + entry.getWorkDate(), now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  /**
   * Corrects an entry somebody logged.
   *
   * <p>Restricted to the person it belongs to - the worker it is attributed to, or whoever recorded
   * it for them. A manager is deliberately not included: a correction is a restatement of what
   * somebody did, and the trail is worth more if only they can make it.
   */
  @Transactional
  public ProjectTaskDetail correctTime(String id, Long entryId, TimeEntryRequest request,
      String actor, String correlationId) {
    if (request == null) throw ApiException.badRequest("A time entry is required");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    var entry = guard(() -> task.timeEntry(entryId));
    assertOwnEntry(entry.belongsTo(actor), "time");

    Instant now = clock.instant();
    int previous = entry.getDurationMinutes();
    guard(() -> {
      task.correctTime(entry, request.workDate(), minutes(request.durationMinutes(), "Time spent"),
          request.note(), now);
      return entry;
    });
    task.addHistory("TIME_CORRECTED", actor,
        entry.getUsername() + " corrected " + duration(previous) + " to "
            + duration(entry.getDurationMinutes()) + " on " + entry.getWorkDate(), now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail removeTime(String id, Long entryId, String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    var entry = guard(() -> task.timeEntry(entryId));
    assertOwnEntry(entry.belongsTo(actor), "time");

    Instant now = clock.instant();
    String removed = entry.getUsername() + "'s " + duration(entry.getDurationMinutes()) + " on "
        + entry.getWorkDate();
    guard(() -> { task.removeTimeEntry(entry); return entry; });
    // The entry goes, the fact that it existed does not: a deletion nobody can see is a hole in the
    // total that the audit trail cannot explain.
    task.addHistory("TIME_REMOVED", actor, "Removed " + removed, now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  /** Records a journey made for the item (TM-A06). Same gate and same attribution rule as time. */
  @Transactional
  public ProjectTaskDetail logTravel(String id, TravelEntryRequest request, String actor,
      String correlationId) {
    if (request == null) throw ApiException.badRequest("A travel entry is required");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    assertMayRecord(task, actor, permissions, cover, "log travel against");

    String traveller = blankToNull(request.traveller());
    if (traveller != null && !traveller.equalsIgnoreCase(actor)
        && !permissions.contains(ProjectTaskPermissions.MANAGE))
      throw ApiException.forbidden("You can only log travel against your own name.");

    Instant now = clock.instant();
    var entry = guard(() -> task.logTravel(traveller == null ? actor : traveller,
        request.travelDate(), request.fromLocation(), request.toLocation(), request.purpose(),
        minutes(request.travelMinutes(), "Travel time"), request.expenseAmount(),
        request.currencyCode(), request.voucherRef(), actor, now));
    entityManager.flush();
    task.addHistory("TRAVEL_LOGGED", actor,
        entry.getTraveller() + " travelled " + entry.getFromLocation() + " to "
            + entry.getToLocation() + " on " + entry.getTravelDate() + ", "
            + duration(entry.getTravelMinutes()) + ", " + entry.getCurrencyCode() + " "
            + entry.getExpenseAmount().toPlainString(), now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail correctTravel(String id, Long entryId, TravelEntryRequest request,
      String actor, String correlationId) {
    if (request == null) throw ApiException.badRequest("A travel entry is required");
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    var entry = guard(() -> task.travelEntry(entryId));
    assertOwnEntry(entry.belongsTo(actor), "travel");

    Instant now = clock.instant();
    String previous = duration(entry.getTravelMinutes()) + " and " + entry.getCurrencyCode() + " "
        + entry.getExpenseAmount().toPlainString();
    guard(() -> {
      task.correctTravel(entry, request.travelDate(), request.fromLocation(), request.toLocation(),
          request.purpose(), minutes(request.travelMinutes(), "Travel time"),
          request.expenseAmount(), request.currencyCode(), request.voucherRef(), now);
      return entry;
    });
    task.addHistory("TRAVEL_CORRECTED", actor,
        entry.getTraveller() + " corrected " + previous + " to " + duration(entry.getTravelMinutes())
            + " and " + entry.getCurrencyCode() + " " + entry.getExpenseAmount().toPlainString(),
        now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  @Transactional
  public ProjectTaskDetail removeTravel(String id, Long entryId, String actor, String correlationId) {
    var permissions = ProjectTaskPermissions.granted();
    var cover = cover(actor);
    var task = readable(id, actor, permissions, cover);
    var entry = guard(() -> task.travelEntry(entryId));
    assertOwnEntry(entry.belongsTo(actor), "travel");

    Instant now = clock.instant();
    String removed = entry.getTraveller() + "'s journey on " + entry.getTravelDate() + ", "
        + entry.getCurrencyCode() + " " + entry.getExpenseAmount().toPlainString();
    guard(() -> { task.removeTravelEntry(entry); return entry; });
    task.addHistory("TRAVEL_REMOVED", actor, "Removed " + removed, now, correlationId);
    entityManager.flush();
    return detail(task, actor, permissions, cover);
  }

  /**
   * Whether this caller may record effort or travel here at all.
   *
   * <p>Separated from the entity's own open/archived refusal because the two answer different
   * questions - "are you one of the people doing this" and "is this item still live" - and a caller
   * denied for the first reason should not be told to reopen anything.
   */
  private static void assertMayRecord(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover, String what) {
    if (!ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions, cover))
      throw ApiException.forbidden("Only the people working this item can " + what + " it.");
  }

  private static void assertOwnEntry(boolean own, String kind) {
    if (!own)
      throw ApiException.forbidden("You can only change " + kind + " entries you recorded yourself.");
  }

  private static int minutes(Integer supplied, String what) {
    if (supplied == null) throw ApiException.badRequest(what + " is required.");
    return supplied;
  }

  /** Turns the entity's refusals into the API error contract, as {@code transition} already does. */
  private static <T> T guard(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalArgumentException rejected) {
      throw ApiException.badRequest(rejected.getMessage());
    }
  }

  /** "2h 30m", for a history row a person reads. */
  private static String duration(int totalMinutes) {
    int hours = totalMinutes / 60;
    int remainder = totalMinutes % 60;
    if (hours == 0) return remainder + "m";
    return remainder == 0 ? hours + "h" : hours + "h " + remainder + "m";
  }

  // ---------------------------------------------------------------- notification

  /**
   * Announces that this item now belongs to somebody.
   *
   * <p>Published rather than posted: {@code WorkItemNotifier} listens after the transaction commits,
   * so a rolled-back assignment cannot leave a message behind telling somebody about work they were
   * never given, and no HTTP call happens with this write transaction open.
   *
   * <p>Silent when the item is unowned, and silent when somebody assigned it to themselves - the
   * notifier drops the actor from every audience, and this saves the round trip.
   */
  private void announceAssignment(ProjectTaskEntity task, String actor) {
    if (task.getOwnerUserId() == null || task.getOwnerUserId().equalsIgnoreCase(actor)) return;
    events.publishEvent(new WorkItemNotification(WorkItemNotification.Kind.ASSIGNED, SlaFamily.WORK_ITEM,
        task.getId(), task.getTitle(), eventRef(task), task.getOwnerUserId(), task.getCreatedBy(),
        actor, task.getStatus().name(), null, null, task.getDueAt()));
  }

  /** Announces a lifecycle move to the owner and to whoever raised the work. */
  private void announceStatusChange(ProjectTaskEntity task, String actor, String status,
      String previousStatus, String reason) {
    events.publishEvent(new WorkItemNotification(WorkItemNotification.Kind.STATUS_CHANGED,
        SlaFamily.WORK_ITEM, task.getId(), task.getTitle(), eventRef(task), task.getOwnerUserId(),
        task.getCreatedBy(), actor, status, previousStatus, reason, task.getDueAt()));
  }

  /**
   * What makes one occurrence distinct: the id of the history row that just recorded it.
   *
   * <p>The task id alone would not do. The Integration Service is idempotent on the event, the
   * reference and the recipient, so reassigning work away from somebody and back again would raise
   * the first message and silently drop the second. The history row is written in the same call as
   * the change and is already flushed by the time this runs, so its id is both unique and a pointer
   * to the exact audit entry the message describes.
   */
  private static String eventRef(ProjectTaskEntity task) {
    var history = task.getHistory();
    return history.isEmpty() ? task.getId() : task.getId() + "#" + history.getLast().getId();
  }

  // ---------------------------------------------------------------- internals

  private ProjectTaskEntity readable(String id, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    var task = entityManager.find(ProjectTaskEntity.class, id);
    // A task the caller may not see reports as missing rather than forbidden: a 403 would confirm
    // that a work item with this id exists, which is itself information the caller is not entitled
    // to (policy P-06 - the same decision in every channel).
    if (task == null || !ProjectTaskVisibilityPolicy.mayView(task, actor, permissions, cover))
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
    if (request.summary() != null && request.summary().trim().length() > ProjectTaskEntity.MAX_SUMMARY)
      throw ApiException.badRequest(
          "summary cannot be longer than " + ProjectTaskEntity.MAX_SUMMARY + " characters.");
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
        task.getCompletedAt(), task.overdue(now), task.getEscalationLevel(), childCount,
        task.getArchivedAt() != null);
  }

  private ProjectTaskDetail detail(ProjectTaskEntity task, String actor, Set<String> permissions) {
    return detail(task, actor, permissions, cover(actor));
  }

  private ProjectTaskDetail detail(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    Instant now = clock.instant();
    // Seeing a task because somebody published it to everyone is not the same as being on it. The
    // audit trail and the linked customer hang off this; TM-014 and TM-012 draw the line here.
    boolean entitled = ProjectTaskVisibilityPolicy.isEntitled(task, actor, permissions, cover);
    var childEntities = children(task.getId());
    var childSummaries = childEntities.stream().map(child -> summary(child, 0, now)).toList();
    // Individual effort is held closer than the item itself: an all-teams task is readable across
    // the organization, and "this person spent fourteen hours on it" should not be (TM-A05).
    boolean mayViewEffort = ProjectTaskVisibilityPolicy.mayViewEffort(task, actor, permissions, cover);
    return new ProjectTaskDetail(task.getId(), task.getVersion(), task.getTitle(), task.getSummary(),
        task.getDescription(),
        task.getTaskType(), task.getPriority(), task.getStatus(), task.getVisibility(),
        task.getRelevantTeam(), task.getOwnerUserId(), task.getCreatedBy(), task.getParentTaskId(),
        linked(task), task.getCreatedAt(), task.getDueAt(),
        task.getPlannedStartAt(), task.getPlannedEndAt(),
        task.getStartedAt(), task.getCompletedAt(),
        task.getCancelledAt(), task.getArchivedAt(), task.overdue(now), task.getEscalationLevel(),
        List.copyOf(task.getStatus().allowedNext()),
        ProjectTaskVisibilityPolicy.mayAct(task, actor, permissions, cover),
        ProjectTaskVisibilityPolicy.mayClaim(task, actor, permissions, cover),
        ProjectTaskVisibilityPolicy.mayEdit(task, actor, permissions, cover),
        task.requiredChecklistComplete(), childSummaries,
        task.getChecklist().stream().map(item -> new ChecklistItemResponse(item.getId(), item.getSequenceNo(),
            item.getTitle(), item.isRequired(), item.isCompleted(), item.getCompletedBy(),
            item.getCompletedAt())).toList(),
        task.getComments().stream().map(entry -> new CommentResponse(entry.getId(), entry.getAuthor(),
            entry.getBody(), entry.getCreatedAt())).toList(),
        task.getAssignees().stream().map(ProjectTaskService::assignee).toList(),
        permissions.contains(ProjectTaskPermissions.ASSIGNEES_MANAGE),
        entitled ? new MasterDataResponse(task.getOrganizationId(), task.getCustomerId(),
            task.getVendorId()) : null,
        entitled ? task.getHistory().stream().map(event -> new HistoryResponse(event.getId(), event.getAction(),
            event.getFromStatus(), event.getToStatus(), event.getActor(), event.getOnBehalfOf(),
            event.getReason(), event.getOccurredAt(), event.getCorrelationId())).toList()
            : List.of(),
        mayViewEffort,
        // Null rather than a zeroed total: absent says "not for you" without also saying "and there
        // is nothing there", the same way masterData above withholds customer identity.
        mayViewEffort ? new EffortResponse(task.totalWorkMinutes(), task.totalTravelMinutes(),
            task.totalExpense(), task.expenseCurrency()) : null,
        mayViewEffort ? task.getTimeEntries().stream()
            .map(entry -> timeEntry(entry, actor)).toList() : List.of(),
        mayViewEffort ? task.getTravelEntries().stream()
            .map(entry -> travelEntry(entry, actor)).toList() : List.of());
  }

  /**
   * {@code mayEdit} is resolved per entry for the calling user, like {@code mayAct} on the item
   * itself: a screen that works out for itself whose entries are whose will eventually offer a
   * control the service refuses.
   */
  private static TimeEntryResponse timeEntry(ProjectTaskTimeEntryEntity entry, String actor) {
    return new TimeEntryResponse(entry.getId(), entry.getUsername(), entry.getWorkDate(),
        entry.getDurationMinutes(), entry.getNote(), entry.getCreatedBy(), entry.getCreatedAt(),
        entry.getUpdatedAt(), entry.belongsTo(actor));
  }

  private static TravelEntryResponse travelEntry(ProjectTaskTravelEntryEntity entry, String actor) {
    return new TravelEntryResponse(entry.getId(), entry.getTraveller(), entry.getTravelDate(),
        entry.getFromLocation(), entry.getToLocation(), entry.getPurpose(), entry.getTravelMinutes(),
        entry.getExpenseAmount(), entry.getCurrencyCode(), entry.getVoucherRef(),
        entry.getCreatedBy(), entry.getCreatedAt(), entry.getUpdatedAt(), entry.belongsTo(actor));
  }

  private static LinkedEntityResponse linked(ProjectTaskEntity task) {
    return new LinkedEntityResponse(task.getLinkedEntityType(), task.getLinkedEntityId(),
        task.getLinkedEntityRef());
  }
}
