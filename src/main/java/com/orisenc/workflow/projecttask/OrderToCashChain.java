package com.orisenc.workflow.projecttask;

import java.util.List;

/**
 * The stages of fulfilling one customer order, as work items.
 *
 * <p>This is the flow the business actually runs: an order arrives from a customer, the goods are
 * ordered from a vendor, the vendor bill arrives, the goods are received, they are delivered, the
 * POD comes back, the customer is invoiced and the money is collected. Each stage becomes a child
 * work item of the order, linked to the record that stage produces in whichever service owns it.
 *
 * <p>Held as data rather than code so the chain can be read and argued about in one place. It is
 * the interim stand-in for {@code workflow_definitions}, the versioned workflow graph the
 * architecture specifies but which is not built: when that lands, this list becomes a seeded
 * definition row and the service stops referring to this class. Until then the chain is created
 * on request instead of by {@code OrderReleased.v1}, because the Operations service that would
 * publish that event does not yet exist.
 *
 * <p>The required checklist items are the evidence gates: a delivery cannot complete without its
 * POD, and a collection cannot complete without the receipt being allocated.
 */
final class OrderToCashChain {

  private OrderToCashChain() {}

  /**
   * One stage of the chain.
   *
   * @param titleFormat title with a single {@code %s} placeholder for the order reference
   * @param checklist evidence required before the stage may complete
   */
  record Stage(String titleFormat, String description, ProjectTaskType taskType,
      LinkedEntityType linkedEntityType, List<String> checklist) {}

  /**
   * Ordered stages. Sequence matters: the service spaces due dates across them in this order, and a
   * reader should be able to see the business process by reading down the list.
   */
  static final List<Stage> STAGES = List.of(
      new Stage("Confirm sales order %s",
          "Validate the customer order: pricing, quantities, requested delivery date and that credit is clear.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.SALES_ORDER,
          List.of("Order lines and pricing confirmed", "Customer credit status cleared")),

      new Stage("Raise purchase order for %s",
          "Select the vendor and raise the purchase order covering the ordered items.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.PURCHASE_ORDER,
          List.of("Vendor selected", "Purchase order approved and sent")),

      new Stage("Receive vendor bill for %s",
          "Record the vendor bill and match it against the purchase order and the goods received.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.FINANCIAL_DOCUMENT,
          List.of("Bill matched to purchase order")),

      new Stage("Receive goods for %s",
          "Book the inbound shipment in and verify quantities and condition against the purchase order.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.SHIPMENT,
          List.of("Quantities verified against purchase order", "Shortages or damage raised as an exception")),

      new Stage("Deliver order %s to customer",
          "Dispatch the outbound shipment and track it to delivery.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.SHIPMENT,
          List.of("Dispatch details and carrier recorded")),

      new Stage("Capture proof of delivery for %s",
          "Obtain the signed POD from the customer and attach it to the delivery.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.PROOF_OF_DELIVERY,
          List.of("POD received and attached")),

      new Stage("Raise customer invoice for %s",
          "Raise the customer invoice against the delivered lines.",
          ProjectTaskType.DELIVERABLE, LinkedEntityType.FINANCIAL_DOCUMENT,
          List.of("Invoice raised and sent to the customer")),

      new Stage("Collect payment for %s",
          "Follow up until the receipt is banked and allocated against the invoice.",
          ProjectTaskType.FOLLOW_UP, LinkedEntityType.PAYMENT,
          List.of("Payment received", "Receipt allocated against the invoice")));
}
