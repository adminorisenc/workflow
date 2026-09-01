package com.orisenc.workflow.projecttask;

/**
 * What kind of work an item represents - not where it sits in a business process.
 *
 * <p>The business stage is carried by {@link LinkedEntityType} and by the parent/child hierarchy
 * instead, so that "deliver the goods" and "collect the payment" are the same type of work against
 * different entities rather than two entries in an ever-growing enum.
 */
public enum ProjectTaskType {
  /** Produces a business outcome in the order-to-cash chain: raise, dispatch, deliver, collect. */
  DELIVERABLE,
  /** Someone physically attends a customer or vendor site. */
  FIELD_VISIT,
  /** Chasing a response, a document or a payment. */
  FOLLOW_UP,
  /** Something went wrong and needs resolution and a root cause (REQ-0011). */
  EXCEPTION,
  /** Internal work with no external counterparty. */
  INTERNAL
}
