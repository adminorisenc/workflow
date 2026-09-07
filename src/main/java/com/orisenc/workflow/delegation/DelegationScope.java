package com.orisenc.workflow.delegation;

/**
 * What a delegation covers.
 *
 * <p>The two task families are separate here for the same reason they are separate everywhere else
 * in this service: an approval is decided and a work item is progressed. Somebody going on leave
 * usually wants a colleague to keep the deliveries moving, which is a very different thing from
 * handing them the authority to approve credit terms - and a single "delegate everything" switch
 * would make the smaller request impossible to express.
 *
 * <p>That distinction is also what decides whether a delegation needs a second person's approval:
 * see {@link #privileged()}.
 */
public enum DelegationScope {

  /** Both families. Privileged, because it includes approval authority. */
  ALL,

  /** Approval decisions on {@code /api/tasks}. Privileged. */
  APPROVALS,

  /** Project work items on {@code /api/project-tasks}. Not privileged: this is doing a job. */
  WORK_ITEMS;

  /**
   * Whether handing this over is a transfer of authority rather than of work.
   *
   * <p>REQ-0027 asks for "privileged-delegation approval", and this is the line: deciding on the
   * organization's behalf is privileged, running a delivery is not. A privileged delegation does not
   * take effect until somebody other than the delegator and the delegate approves it.
   */
  public boolean privileged() {
    return this != WORK_ITEMS;
  }

  /** True when a delegation of this scope covers work in the given family. */
  public boolean covers(DelegationScope family) {
    return this == ALL || this == family;
  }
}
