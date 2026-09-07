package com.orisenc.workflow.delegation;

import java.util.Locale;

/**
 * One person's work, currently reachable by somebody else.
 *
 * <p>The flattened form of an in-force delegation, carrying only what an audience rule needs: whose
 * items are reachable, and how the delegation narrows them. It exists so the visibility policy can
 * answer "may this delegate see that item" without loading delegation entities, and so the same
 * three fields can be turned into a {@code where} clause - a delegate whose queue does not show the
 * work they were given is a delegation that exists only on paper.
 *
 * @param delegator whose items these are
 * @param taskType a single type name this is narrowed to, or null for every type
 * @param department a department or relevant team this is narrowed to, or null for all
 */
public record DelegationCover(String delegator, String taskType, String department) {

  public static DelegationCover of(DelegationEntity delegation) {
    return new DelegationCover(delegation.getDelegatorUserId(), delegation.getTaskType(),
        delegation.getDepartment());
  }

  /** Whether this cover reaches an item of that type in that team. Null narrowing means "any". */
  public boolean covers(String itemTaskType, String itemDepartment) {
    if (taskType != null && !taskType.equalsIgnoreCase(itemTaskType)) return false;
    return department == null || department.equalsIgnoreCase(itemDepartment);
  }

  /** True when this cover reaches items owned by that person. */
  public boolean isFor(String ownerUserId) {
    return ownerUserId != null && delegator.equalsIgnoreCase(ownerUserId.trim());
  }

  public String delegatorKey() {
    return delegator.trim().toLowerCase(Locale.ROOT);
  }

  public String taskTypeKey() {
    return taskType == null ? null : taskType.toUpperCase(Locale.ROOT);
  }

  public String departmentKey() {
    return department == null ? null : department.trim().toLowerCase(Locale.ROOT);
  }
}
