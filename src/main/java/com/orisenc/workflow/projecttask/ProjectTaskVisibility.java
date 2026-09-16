package com.orisenc.workflow.projecttask;

/**
 * Task audience, per TM-010. Narrowest reasonable scope is the default.
 *
 * <p>Policy note P-04 governs the boundary this enum does <em>not</em> cross: a broader audience
 * grants task metadata, status and comments - never customer identity or customer fields. Customer
 * access is a separate entitlement and is never inferred from task visibility.
 */
public enum ProjectTaskVisibility {
  /** Owner and creator only. */
  INDIVIDUAL,
  /** Plus the task's relevant team. */
  RELEVANT_TEAM,
  /**
   * Any user holding task view permission - metadata, status and comments, and no more.
   *
   * <p>"No more" is enforced, not just described: a viewer who reaches a task only through its
   * audience gets no audit history and no linked customer. See
   * {@link ProjectTaskVisibilityPolicy#isEntitled}. Before that it was a comment the service did not
   * honour, which is the same defect the approval task family shipped and this package was rebuilt
   * to remove.
   */
  ALL_TEAMS
}
