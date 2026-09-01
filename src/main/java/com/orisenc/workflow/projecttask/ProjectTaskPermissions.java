package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.api.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Permission codes for project work items.
 *
 * <p>A separate family from {@code platform.task.*}, which governs approval decisions. The
 * architecture keeps project tasks and approval tasks distinct, and the permissions follow: an
 * operations coordinator who runs deliveries has no business approving credit terms, and a finance
 * approver has no business being handed every field visit. One shared permission set would have
 * forced exactly that.
 *
 * <p>Follows the platform convention {@code service.resource.action}.
 */
public final class ProjectTaskPermissions {

  private ProjectTaskPermissions() {}

  /** See work items the visibility policy admits. */
  public static final String VIEW = "platform.work.view";
  /** See RELEVANT_TEAM items you neither own nor created. */
  public static final String VIEW_TEAM = "platform.work.view.team";
  public static final String CREATE = "platform.work.create";
  /** Claim, transition, tick checklist items, comment - progressing work you hold. */
  public static final String EXECUTE = "platform.work.execute";
  /** Reassign, archive and act on items you do not own. Team-lead level. */
  public static final String MANAGE = "platform.work.manage";

  public static Set<String> granted() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) return Set.of();
    return authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .collect(Collectors.toUnmodifiableSet());
  }

  public static boolean holds(String permission) {
    return granted().contains(permission);
  }

  public static void require(String permission, String message) {
    if (!holds(permission)) throw ApiException.forbidden(message);
  }
}
