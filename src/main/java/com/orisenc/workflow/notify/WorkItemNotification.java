package com.orisenc.workflow.notify;

import com.orisenc.workflow.sla.SlaFamily;

import java.time.Instant;

/**
 * Something happened to a task that somebody other than the person who did it should know about.
 *
 * <p>Published by the task services and consumed by {@link WorkItemNotifier} <em>after</em> the
 * transaction commits. That ordering is the reason this is an event rather than a method call: the
 * services hold a write transaction open while they work, and posting to another service inside one
 * means a mail queue's latency is charged to a database lock. It also means a rolled-back
 * transaction cannot leave a notification behind describing a change that never happened.
 *
 * <p>Carries the people rather than the recipients. Who should hear about an assignment is a policy
 * question with one answer, and {@link WorkItemNotifier} owns it - a service that computed its own
 * recipient list would be the second place that answer lives.
 *
 * @param eventRef what makes this occurrence distinct, and the Integration Service's idempotency key
 *     together with the event type and the recipient. The id of the history row that recorded the
 *     change, so re-raising the same change is suppressed while a genuine second change - reassigned
 *     away and back again - is not
 * @param actor whoever did it. Excluded from every recipient list: telling somebody what they just
 *     did is the fastest way to teach them to filter these messages away
 */
public record WorkItemNotification(
    Kind kind,
    SlaFamily family,
    String taskId,
    String title,
    String eventRef,
    String owner,
    String creator,
    String actor,
    String status,
    String previousStatus,
    String reason,
    Instant dueAt) {

  /** What happened. The two kinds have different audiences, which is why they are not one event. */
  public enum Kind {

    /** Work was handed to somebody. They hear about it; nobody else needs to. */
    ASSIGNED,

    /** The task moved. The owner and whoever raised it hear about it. */
    STATUS_CHANGED
  }
}
