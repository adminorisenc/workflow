package com.orisenc.workflow.projecttask;

import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import com.orisenc.workflow.task.TaskPriority;

/**
 * REST layer for project work items.
 *
 * <p>A separate resource from {@code /api/tasks}, which remains the approval API. Keeping them apart
 * is what lets the approval contract stay frozen while this one is built out, and it reflects the
 * architecture's rule that project tasks and approval tasks are distinct.
 *
 * <p>Endpoint-level authorization is coarse - most methods only require {@code platform.work.view},
 * because whether a caller may act on a <em>particular</em> item depends on who owns it. That
 * record-level decision belongs to {@link ProjectTaskVisibilityPolicy} and is made in the service.
 */
@RestController
@RequestMapping(path = "/api/project-tasks", produces = "application/json")
public class ProjectTaskController {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  private final ProjectTaskService service;
  private final CurrentUserProvider currentUser;

  public ProjectTaskController(ProjectTaskService service, CurrentUserProvider currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public List<ProjectTaskSummary> list(
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String team,
      @RequestParam(required = false) ProjectTaskType type,
      @RequestParam(required = false) String parent,
      @RequestParam(required = false) LinkedEntityType linkedType,
      @RequestParam(required = false) String linkedId,
      @RequestParam(required = false) Boolean openOnly,
      @RequestParam(required = false) Boolean includeArchived,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean mine,
      @RequestParam(required = false) Boolean topLevelOnly,
      @RequestParam(required = false, name = "status") List<ProjectTaskStatus> statuses,
      @RequestParam(required = false, name = "priority") List<TaskPriority> priorities,
      @RequestParam(required = false) String assignee, @RequestParam(required = false) Boolean unclaimed,
      @RequestParam(required = false) Instant dueFrom, @RequestParam(required = false) Instant dueTo,
      @RequestParam(required = false) Instant createdFrom, @RequestParam(required = false) Instant createdTo,
      @RequestParam(required = false) Instant completedFrom, @RequestParam(required = false) Instant completedTo,
      @RequestParam(required = false) Boolean overdue, @RequestParam(required = false) Boolean escalated,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query = new ProjectTaskService.ListQuery(null, owner, team, type, parent, linkedType, linkedId,
        openOnly, includeArchived, q, mine, page, size, topLevelOnly, statuses, priorities, assignee, unclaimed,
        dueFrom, dueTo, createdFrom, createdTo, completedFrom, completedTo, overdue, escalated);
    return service.list(query, actor(), ProjectTaskPermissions.granted());
  }

  @GetMapping("/count")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public java.util.Map<String, Long> count(@RequestParam(required=false) List<ProjectTaskStatus> status,
      @RequestParam(required=false) List<TaskPriority> priority) {
    var q = new ProjectTaskService.ListQuery(null,null,null,null,null,null,null,null,null,null,null,null,null,null,
        status,priority,null,null,null,null,null,null,null,null,null,null);
    return java.util.Map.of("total", service.count(q, actor(), ProjectTaskPermissions.granted()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail get(@PathVariable String id) {
    return service.get(id, actor(), ProjectTaskPermissions.granted());
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.CREATE + "')")
  public ResponseEntity<ProjectTaskDetail> create(@RequestBody CreateProjectTaskRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return ResponseEntity.status(201).body(service.create(body, actor(), correlationId(correlationId)));
  }

  @PostMapping(path = "/{id}/subtasks", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ResponseEntity<ProjectTaskDetail> addSubtasks(@PathVariable String id,
      @RequestBody AddSubtasksRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return ResponseEntity.status(201).body(service.addSubtasks(id, body, actor(), correlationId(correlationId)));
  }

  /** Creates the fulfilment chain for one customer order: the parent item and every stage under it. */
  @PostMapping(path = "/order-chain", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.CREATE + "')")
  public ResponseEntity<ProjectTaskDetail> createOrderChain(@RequestBody CreateOrderChainRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return ResponseEntity.status(201)
        .body(service.createOrderChain(body, actor(), correlationId(correlationId)));
  }

  @PostMapping(path = "/{id}/transition", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail transition(@PathVariable String id, @RequestBody TransitionRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.transition(id, body, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/claim", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail claim(@PathVariable String id, @RequestBody(required = false) ClaimRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.claim(id, body, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/reassign", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail reassign(@PathVariable String id, @RequestBody ReassignRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.reassign(id, body, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/archive", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail archive(@PathVariable String id, @RequestBody CommentRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.archive(id, body == null ? null : body.body(), actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/comments", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail comment(@PathVariable String id, @RequestBody CommentRequest body) {
    return service.comment(id, body, actor());
  }

  @PostMapping(path = "/{id}/followers", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail addFollower(@PathVariable String id, @RequestBody FollowerRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.addFollower(id, body, actor(), correlationId(correlationId));
  }

  @DeleteMapping("/{id}/followers/{username}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail removeFollower(@PathVariable String id, @PathVariable String username,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.removeFollower(id, username, actor(), correlationId(correlationId));
  }

  @PostMapping("/{id}/follow")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail follow(@PathVariable String id,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.follow(id, actor(), correlationId(correlationId));
  }

  @PostMapping("/{id}/unfollow")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail unfollow(@PathVariable String id,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.unfollow(id, actor(), correlationId(correlationId));
  }

  /**
   * Edits an open work item (TM-A03).
   *
   * <p>{@code PUT} rather than {@code PATCH} because the body is the item's whole editable state.
   * With a partial patch, "clear the summary" and "leave the summary alone" are both an absent
   * field; sending everything makes them different requests, and {@code expectedVersion} is what
   * stops a full-state write built on a stale read from overwriting somebody else's edit.
   *
   * <p>Gated on view like the rest of this resource: whether this particular caller may edit this
   * particular item depends on who owns it and whether it is still open, which is a record-level
   * decision the service makes.
   */
  @PutMapping(path = "/{id}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail update(@PathVariable String id, @RequestBody UpdateProjectTaskRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.update(id, body, actor(), correlationId(correlationId));
  }

  /* ------------------------------------------------------------------- time and travel */

  /**
   * Effort and journeys against the item (TM-A05, TM-A06).
   *
   * <p>Each returns the whole item rather than the entry alone, so a screen that logs an hour gets
   * the recomputed totals, the new history row and the fresh version in the same response instead of
   * making three calls to find out what its own write did.
   */
  @PostMapping(path = "/{id}/time", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail logTime(@PathVariable String id, @RequestBody TimeEntryRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.logTime(id, body, actor(), correlationId(correlationId));
  }

  @PutMapping(path = "/{id}/time/{entryId}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail correctTime(@PathVariable String id, @PathVariable Long entryId,
      @RequestBody TimeEntryRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.correctTime(id, entryId, body, actor(), correlationId(correlationId));
  }

  @DeleteMapping("/{id}/time/{entryId}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail removeTime(@PathVariable String id, @PathVariable Long entryId,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.removeTime(id, entryId, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/travel", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail logTravel(@PathVariable String id, @RequestBody TravelEntryRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.logTravel(id, body, actor(), correlationId(correlationId));
  }

  @PutMapping(path = "/{id}/travel/{entryId}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail correctTravel(@PathVariable String id, @PathVariable Long entryId,
      @RequestBody TravelEntryRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.correctTravel(id, entryId, body, actor(), correlationId(correlationId));
  }

  @DeleteMapping("/{id}/travel/{entryId}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.EXECUTE + "')")
  public ProjectTaskDetail removeTravel(@PathVariable String id, @PathVariable Long entryId,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.removeTravel(id, entryId, actor(), correlationId(correlationId));
  }

  /* ------------------------------------------------------------------------- assignees */

  /**
   * Gated on view rather than the manage code: seeing who else is on an item you can already see is
   * not sensitive. Changing the list is, which is why the two below need their own permission.
   */
  @GetMapping("/{id}/assignees")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public List<AssigneeResponse> assignees(@PathVariable String id) {
    return service.assignees(id, actor());
  }

  @PostMapping(path = "/{id}/assignees", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.ASSIGNEES_MANAGE + "')")
  public ProjectTaskDetail addAssignee(@PathVariable String id, @RequestBody AssigneeRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.addAssignee(id, body, actor(), correlationId(correlationId));
  }

  @DeleteMapping("/{id}/assignees/{username}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.ASSIGNEES_MANAGE + "')")
  public ProjectTaskDetail removeAssignee(@PathVariable String id, @PathVariable String username,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.removeAssignee(id, username, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/checklist/{itemId}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail toggleChecklistItem(@PathVariable String id, @PathVariable Long itemId,
      @RequestBody(required = false) ChecklistToggleRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.toggleChecklistItem(id, itemId, body, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/checklist", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail addChecklistItems(@PathVariable String id,
      @RequestBody ChecklistItemsWriteRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.addChecklistItems(id, body, actor(), correlationId(correlationId));
  }

  @PutMapping(path = "/{id}/checklist/{itemId}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail editChecklistItem(@PathVariable String id, @PathVariable Long itemId,
      @RequestBody ChecklistItemUpdateRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.editChecklistItem(id, itemId, body, actor(), correlationId(correlationId));
  }

  @DeleteMapping("/{id}/checklist/{itemId}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail removeChecklistItem(@PathVariable String id, @PathVariable Long itemId,
      @RequestParam Long expectedVersion,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.removeChecklistItem(id, itemId, expectedVersion, actor(), correlationId(correlationId));
  }

  /**
   * The acting identity, taken from the token's {@code preferred_username} and never from the
   * request body - the same rule the approval API follows, and the reason its history is worth
   * anything as an audit record.
   */
  private String actor() {
    String name = currentUser.get().preferredUsername();
    if (name == null || name.isBlank())
      throw ApiException.forbidden("A valid signed-in identity is required.");
    return name;
  }

  private String correlationId(String supplied) {
    return supplied != null && !supplied.isBlank() ? supplied : UUID.randomUUID().toString();
  }
}
