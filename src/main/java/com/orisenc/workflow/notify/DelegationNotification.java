package com.orisenc.workflow.notify;

import java.time.Instant;

/**
 * A delegation started or ended, and the two people it concerns should know.
 *
 * <p>Worth sending for a reason the task notifications do not share: a delegate who does not know
 * they hold somebody's authority will not use it, and a delegate who does not know it lapsed will
 * try to and be refused. Both failures look like a broken platform from the inside.
 *
 * <p>Published by the delegation service and raised by {@link DelegationNotifier} after the
 * transaction commits, for the same reasons as {@link WorkItemNotification}.
 *
 * @param eventRef the id of the delegation event row that recorded this, so a delegation approved,
 *     revoked and re-created is three notifications rather than one
 * @param actor whoever did it, excluded from the audience. On an expiry this is the scheduled pass,
 *     which is nobody, so both parties hear about it
 */
public record DelegationNotification(
    Kind kind,
    String delegationId,
    String eventRef,
    String delegator,
    String delegate,
    String actor,
    String scope,
    String taskType,
    String department,
    Instant validFrom,
    Instant validUntil,
    String reason,
    String outcome) {

  /** What happened. Two kinds, because starting and stopping have different audiences. */
  public enum Kind {

    /** It is now approved and will be in force for its window. The delegate hears about it. */
    ACTIVE,

    /** It ended - refused, revoked or expired. Both parties hear about it. */
    ENDED
  }
}
