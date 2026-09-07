package com.orisenc.workflow.sla;

import java.time.Instant;

/**
 * One open item with a deadline, flattened out of whichever table it came from.
 *
 * <p>A projection rather than an entity so the scan reads only the seven columns it needs, and so
 * the two task families - approval decisions in {@code workflow_task}, work items in
 * {@code project_task} - can go through one ladder without either entity learning about SLAs.
 *
 * @param family which table this came from, and therefore what to call it in a message
 * @param owner the person accountable today: the work item's owner, or the approval's assignee.
 *     Null when nobody has claimed it, which is itself a reason to warn somebody.
 * @param creator who raised it: the work item's creator, or the approval's requester. Always set.
 * @param escalationContact the owner of the parent work item, where there is one. A stage of a
 *     fulfilment chain escalates to whoever holds the order, which is the nearest thing to a
 *     reporting line the platform can currently derive. Null for approvals and for parentless items.
 * @param level the highest rung already raised for this item, from its own row
 */
public record SlaCandidate(
    SlaFamily family,
    String id,
    String title,
    /** The item's own type name, needed to test a delegation narrowed to one type. */
    String taskType,
    Instant dueAt,
    String owner,
    String creator,
    String team,
    String escalationContact,
    int level) {}
