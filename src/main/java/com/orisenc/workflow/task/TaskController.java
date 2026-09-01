package com.orisenc.workflow.task;

import static com.orisenc.workflow.task.TaskDtos.*;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.security.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST layer for REQ-0022, now owned by the independent Workflow service.
 *
 * <p>Paths, methods, query parameters and payload shapes are preserved exactly - the UI is frozen
 * except for the service base URL. With {@code server.servlet.context-path} set to
 * {@code /workflow-service/ws}, the UI can call this service directly using its own Entra audience.
 *
 * <p>Authorization is on permissions, never roles.
 */
@RestController
@RequestMapping(path = "/api/tasks", produces = "application/json")
public class TaskController {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  private final TaskService service;
  private final CurrentUserProvider currentUser;

  public TaskController(TaskService service, CurrentUserProvider currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('" + TaskPermissions.VIEW + "')")
  public List<TaskResponse> list(@RequestParam(required = false) String department,
                                 @RequestParam(required = false) TaskStatus status,
                                 @RequestParam(required = false) String assignee,
                                 @RequestParam(required = false) String q) {
    return service.list(department, status, assignee, q);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('" + TaskPermissions.VIEW + "')")
  public TaskResponse get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasAuthority('" + TaskPermissions.CREATE + "')")
  public ResponseEntity<TaskResponse> create(@RequestBody CreateTaskRequest body,
                                             @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    var created = service.create(body, actor(), correlationId(correlationId));
    return ResponseEntity.status(201).body(created);
  }

  @PostMapping(path = "/{id}/actions", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + TaskPermissions.VIEW + "')")
  public TaskResponse act(@PathVariable String id, @RequestBody ActionRequest body,
                          @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    if (body != null && body.action() != null) {
      TaskPermissions.requireForAction(body.action());
    }
    return service.act(id, body, actor(), correlationId(correlationId));
  }

  /**
   * The acting identity is the token's {@code preferred_username} (UPN/email), matching the values
   * stored in a task's {@code assignee}/{@code requester} fields and compared case-insensitively by
   * {@code TaskService.assertAssignee}. It is never taken from the request body.
   */
  private String actor() {
    String name = currentUser.get().preferredUsername();
    if (name == null || name.isBlank()) {
      throw ApiException.forbidden("A valid signed-in identity is required.");
    }
    return name;
  }

  private String correlationId(String supplied) {
    return supplied != null && !supplied.isBlank() ? supplied : UUID.randomUUID().toString();
  }
}
