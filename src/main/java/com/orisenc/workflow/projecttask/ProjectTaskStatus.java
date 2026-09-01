package com.orisenc.workflow.projecttask;

import java.util.EnumSet;
import java.util.Set;

/**
 * The seven project-task states from {@code Task_Management_MVP_Scope.xlsx} (TM-015, "Workflow"
 * sheet), with the transitions that sheet declares legal.
 *
 * <p>Distinct from {@link com.orisenc.workflow.task.TaskStatus}, which is the four-state approval
 * lifecycle of REQ-0022. Both families live in this service because the architecture assigns both
 * to it, but they are deliberately separate: an approval is decided, a work item is progressed.
 * Collapsing them is what produced the status mismatch between the UI and the approval API.
 *
 * <p>{@link #COMPLETED} and {@link #CANCELLED} are final only by default - the sheet allows a
 * reopen and a restore respectively, each requiring its own reason, so neither is terminal.
 */
public enum ProjectTaskStatus {
  DRAFT,
  NOT_STARTED,
  IN_PROGRESS,
  BLOCKED,
  AWAITING_INPUT,
  COMPLETED,
  CANCELLED;

  private static final Set<ProjectTaskStatus> CLOSED = EnumSet.of(COMPLETED, CANCELLED);

  /** The states this one may move to. An empty result would mean a dead end; none exists. */
  public Set<ProjectTaskStatus> allowedNext() {
    return switch (this) {
      case DRAFT -> EnumSet.of(NOT_STARTED, CANCELLED);
      case NOT_STARTED -> EnumSet.of(IN_PROGRESS, BLOCKED, CANCELLED);
      case IN_PROGRESS -> EnumSet.of(BLOCKED, AWAITING_INPUT, COMPLETED, CANCELLED);
      case BLOCKED -> EnumSet.of(IN_PROGRESS, AWAITING_INPUT, CANCELLED);
      case AWAITING_INPUT -> EnumSet.of(IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED);
      // Reopen and restore. Both are ordinary transitions that require a comment like any other,
      // which is what makes the reason for reopening auditable.
      case COMPLETED -> EnumSet.of(IN_PROGRESS);
      case CANCELLED -> EnumSet.of(NOT_STARTED, IN_PROGRESS);
    };
  }

  public boolean canMoveTo(ProjectTaskStatus next) {
    return next != null && allowedNext().contains(next);
  }

  /** True for the two states that stamp a closure timestamp and stop counting against SLA. */
  public boolean closed() {
    return CLOSED.contains(this);
  }
}
