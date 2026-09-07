package com.orisenc.workflow.sla;

/**
 * The two task families this service owns, as the SLA pass sees them.
 *
 * <p>They are kept apart here for the same reason they are kept apart everywhere else in Workflow:
 * an approval is decided and a work item is progressed, and a person chased about one should be able
 * to tell which from the subject line. The event-type prefix keeps their delivery logs separable
 * too, so "was this approver ever chased" is answerable without reading past every work item.
 */
public enum SlaFamily {

  /** {@code project_task} - the order-to-cash chain and the ad-hoc work around it. */
  WORK_ITEM("WORK_ITEM", "work item"),

  /** {@code workflow_task} - the frozen approval contract. */
  APPROVAL("APPROVAL", "approval");

  private final String eventPrefix;
  private final String label;

  SlaFamily(String eventPrefix, String label) {
    this.eventPrefix = eventPrefix;
    this.label = label;
  }

  /** First half of the notification event type; the rung supplies the second. */
  public String eventPrefix() { return eventPrefix; }

  /** What to call this in a sentence a person reads. */
  public String label() { return label; }
}
