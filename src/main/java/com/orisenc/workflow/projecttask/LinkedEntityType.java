package com.orisenc.workflow.projecttask;

/**
 * The business record a work item is about.
 *
 * <p>This is the join between task management and the order-to-cash chain: a customer order becomes
 * a parent task linked to {@link #SALES_ORDER}, and each stage of fulfilling it becomes a child task
 * linked to the record that stage produces - the vendor order, the inbound shipment, the vendor
 * bill, the outbound delivery and its POD, the customer invoice, the receipt.
 *
 * <p>Deliberately a loose reference ({@code type} + {@code id} + a human-readable {@code ref})
 * rather than a foreign key: these records live in other services' databases (operations_db,
 * accounts_db) and the architecture forbids cross-database joins. The reference stays valid whether
 * or not the owning service is reachable.
 */
public enum LinkedEntityType {
  NONE,
  CUSTOMER,
  VENDOR,
  SALES_ORDER,
  /** Not yet owned by any service - Operations will add purchase_orders. */
  PURCHASE_ORDER,
  /** Outbound, inbound, invoice-courier or internal - Operations models all four as one shipment. */
  SHIPMENT,
  PROOF_OF_DELIVERY,
  /** AR invoice or AP vendor bill; direction lives on the Accounts document itself. */
  FINANCIAL_DOCUMENT,
  PAYMENT,
  RMA
}
