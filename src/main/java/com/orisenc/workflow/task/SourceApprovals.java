package com.orisenc.workflow.task;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Approval tasks whose decision another service owns (OPS margins, Sales quotes). Each kind is recognised
 * by its task reference prefix: only holders of the kind's approval permission see the task (404 otherwise),
 * the generic task actions refuse it, and only that service's application identity reports its state.
 */
final class SourceApprovals {
  private SourceApprovals() {}

  record Kind(String prefix, String approvePermission, String refusal) {}

  static final List<Kind> KINDS = List.of(
      new Kind(OpsMarginTaskService.PREFIX, OpsMarginTaskService.APPROVE_PERMISSION,
          "Margin approval is disabled."),
      new Kind(SalesQuoteTaskService.PREFIX, SalesQuoteTaskService.APPROVE_PERMISSION,
          "Decide this quote with Approve or Reject on the quote approval."));

  static Optional<Kind> of(TaskEntity task) {
    String reference = task.getReference();
    if (reference == null) return Optional.empty();
    return KINDS.stream().filter(kind -> reference.startsWith(kind.prefix())).findFirst();
  }

  /** These tasks carry the source's commercial figures, so only its approvers see them. */
  static boolean visible(TaskEntity task) {
    // Legacy margin tasks stay hidden and cannot fall through to generic Workflow actions.
    if (task.getReference()!=null && task.getReference().startsWith(OpsMarginTaskService.PREFIX)) return false;
    return of(task).map(kind -> callerHolds(kind.approvePermission())).orElse(true);
  }

  static boolean callerHolds(String permission) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
  }

  /** A person never syncs source state, even one holding the permission: only an application token has no sign-in name. */
  static String serviceActor(CurrentUserProvider users) {
    var u = users.get();
    if (u.objectId() == null || u.objectId().isBlank() || (u.preferredUsername() != null && !u.preferredUsername().isBlank()))
      throw ApiException.forbidden("A service identity is required.");
    return u.objectId();
  }
}
