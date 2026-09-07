package com.orisenc.workflow.delegation;

import com.orisenc.workflow.api.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Permission codes for delegation.
 *
 * <p>Four rather than two, because the four things a person does here are done by different people.
 * Arranging cover for your own absence is an everyday act; approving somebody else's transfer of
 * approval authority is an administrator's; and revoking a delegation you did not create is a
 * different job again from either.
 *
 * <p>{@code platform.delegation.view} predates this implementation - it has been in Common
 * Platform's catalogue since the operations workspace was scoped - and is kept unchanged.
 */
public final class DelegationPermissions {

  private DelegationPermissions() {}

  /** See your own delegations and the ones that hand work to you. */
  public static final String VIEW = "platform.delegation.view";
  /** Arrange cover for your own work. */
  public static final String CREATE = "platform.delegation.create";
  /** Decide a privileged delegation somebody else asked for. */
  public static final String APPROVE = "platform.delegation.approve";
  /** See every delegation, arrange one on somebody else's behalf, and revoke any of them. */
  public static final String MANAGE = "platform.delegation.manage";

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
