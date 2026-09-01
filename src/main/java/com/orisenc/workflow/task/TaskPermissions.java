package com.orisenc.workflow.task;

import com.orisenc.workflow.api.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Stable permission codes for Workflow / Task Management.
 *
 * <p>Replaces the previous {@code TaskRoles} role checks. Authorizing on permissions rather than
 * roles means a new role (say {@code OPERATIONS_LEAD}) can be granted task approval by adding the
 * permission to it, with no code change here. A role check would have silently excluded it.
 */
public final class TaskPermissions {

  private TaskPermissions() {}

  // These values remain unchanged during the service extraction so existing roles, grants and UI
  // controls continue to work. Common Platform remains the central permission catalogue, while this
  // service owns the task records and enforces the permissions.
  public static final String VIEW = "platform.task.view";
  public static final String CREATE = "platform.task.create";
  /** Claim, start and complete - progressing a task you are assigned. */
  public static final String EXECUTE = "platform.task.execute";
  /** Approve and reject - deciding an approval task. */
  public static final String APPROVE = "platform.task.approve";

  /**
   * Checked in code rather than with {@code @PreAuthorize} because a single endpoint
   * ({@code POST /api/tasks/{id}/actions}) carries several actions whose required permission is
   * only known once the request body is read. The method-level annotation still gates the endpoint
   * on {@link #VIEW}; this narrows it per action.
   */
  public static void requireForAction(TaskAction action) {
    String required = switch (action) {
      case APPROVE, REJECT -> APPROVE;
      case CLAIM, START, COMPLETE -> EXECUTE;
    };
    boolean granted = SecurityContextHolder.getContext().getAuthentication() != null
        && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> required.equals(a.getAuthority()));
    if (!granted) {
      throw ApiException.forbidden("Your role does not permit this action.");
    }
  }
}
