package com.orisenc.workflow.delegation;

/**
 * Where a delegation is in its life.
 *
 * <p>{@link #ACTIVE} means approved and not yet ended. It does <em>not</em> mean in force right now:
 * a delegation covering next week is approved today and does nothing until Monday. Whether it
 * currently authorizes anybody is {@code DelegationEntity.inForce(now)}, which is status plus
 * window - and keeping those two questions apart is what lets somebody arrange cover before they
 * leave rather than on the morning they go.
 */
public enum DelegationStatus {

  /** A privileged delegation waiting on somebody other than its delegator and delegate. */
  PENDING_APPROVAL,

  /** Approved, or never needed approval. In force during its window and not before. */
  ACTIVE,

  /** An approver refused it. Terminal, and the reason is on the record. */
  REJECTED,

  /** Ended early by the delegator or an administrator. Terminal. */
  REVOKED,

  /** Its window closed. Terminal, and written by the scheduled pass rather than by a person. */
  EXPIRED;

  /** True for the three states nothing can move out of. */
  public boolean terminal() {
    return this == REJECTED || this == REVOKED || this == EXPIRED;
  }
}
