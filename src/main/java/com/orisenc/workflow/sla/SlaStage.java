package com.orisenc.workflow.sla;

/**
 * What a crossed rung means, as opposed to which rung it was.
 *
 * <p>The stage decides the wording and who hears about it; the level decides whether anything is
 * raised at all. Keeping them apart is what lets an operator add a third escalation step without
 * anyone writing a third template.
 */
public enum SlaStage {

  /** The deadline is close and has not passed. A warning to whoever holds the item. */
  DUE_SOON,

  /** The deadline has passed. The owner and the person who raised the work both hear about it. */
  BREACHED,

  /** Still open some way past the deadline. It now goes over the owner's head. */
  ESCALATED;

  /** Not muteable above a warning: an opt-out should silence a nudge, never a missed commitment. */
  public boolean critical() {
    return this != DUE_SOON;
  }
}
