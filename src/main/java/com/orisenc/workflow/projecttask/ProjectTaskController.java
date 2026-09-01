package com.orisenc.workflow.projecttask;

import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
      @RequestParam(required = false) ProjectTaskStatus status,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String team,
      @RequestParam(required = false) ProjectTaskType type,
      @RequestParam(required = false) String parent,
      @RequestParam(required = false) LinkedEntityType linkedType,
      @RequestParam(required = false) String linkedId,
      @RequestParam(required = false) Boolean openOnly,
      @RequestParam(required = false) Boolean includeArchived,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query = new ProjectTaskService.ListQuery(status, owner, team, type, parent, linkedType, linkedId,
        openOnly, includeArchived, q, page, size);
    return service.list(query, actor(), ProjectTaskPermissions.granted());
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

  @PostMapping(path = "/{id}/checklist/{itemId}", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.VIEW + "')")
  public ProjectTaskDetail toggleChecklistItem(@PathVariable String id, @PathVariable Long itemId,
      @RequestBody(required = false) ChecklistToggleRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.toggleChecklistItem(id, itemId, body, actor(), correlationId(correlationId));
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
