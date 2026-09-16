package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One journey made for a work item, with what it cost in time and money (TM-A06).
 *
 * <p>This is a <em>work record, not a reimbursement claim</em>, and that decision is what keeps it in
 * Workflow beside the task instead of in Accounts. Nothing here raises a payable, reaches Zoho Books
 * or touches payroll. If travel ever needs to be reimbursed, the claim is a separate thing in the
 * service that owns money, and this table is its evidence rather than its replacement.
 *
 * <p>{@code travelMinutes} is kept apart from {@link ProjectTaskTimeEntryEntity}'s duration on
 * purpose. The two are totalled separately and shown side by side, so "a two-hour job that cost five
 * hours of travel" stays a visible fact rather than disappearing into a single seven-hour figure.
 *
 * <h2>Two column names chosen to avoid a misleading failure</h2>
 *
 * <p>{@code from_location} and {@code to_location}, not {@code from} and {@code to}: both are
 * reserved words, and H2 refuses to create the whole table rather than naming the offending column,
 * so the symptom would be a "table not found" error pointing nowhere near the cause.
 */
@Entity
@Table(name = "project_task_travel_entry", indexes = {
    @Index(name = "idx_project_task_travel_task", columnList = "task_id,travel_date"),
    @Index(name = "idx_project_task_travel_user", columnList = "traveller,travel_date")
})
public class ProjectTaskTravelEntryEntity {

  public static final int MAX_MINUTES = 24 * 60;
  public static final int MAX_PURPOSE = 500;
  public static final int MAX_PLACE = 200;
  /** ISO 4217, and the default when a caller sends none. Interim pending REQ-0030. */
  public static final String DEFAULT_CURRENCY = "INR";

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false) private ProjectTaskEntity task;

  @Column(name = "traveller", nullable = false, length = 120) private String traveller;
  @Column(name = "travel_date", nullable = false) private LocalDate travelDate;
  @Column(name = "from_location", nullable = false, length = MAX_PLACE) private String fromLocation;
  @Column(name = "to_location", nullable = false, length = MAX_PLACE) private String toLocation;
  @Column(name = "purpose", nullable = false, length = MAX_PURPOSE) private String purpose;
  @Column(name = "travel_minutes", nullable = false) private int travelMinutes;

  /**
   * What the journey cost. One amount with no category, by decision - no fuel/lodging/food
   * breakdown, no per-km rate.
   *
   * <p>{@code precision = 18, scale = 2} matches every other money column in the platform
   * ({@code CashBalanceEntity}, {@code CreditTermsRequestEntity}), so an amount means the same thing
   * wherever it is read.
   */
  @Column(name = "expense_amount", nullable = false, precision = 18, scale = 2)
  private BigDecimal expenseAmount;
  @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;

  /**
   * A bill or voucher number.
   *
   * <p>Stands in for a receipt because attachments are TM-025 and deferred - there is no way to
   * attach the actual document today, and an expense with no reference at all is unauditable. When
   * TM-025 lands this becomes the thing the attachment is filed against rather than a substitute
   * for it.
   */
  @Column(name = "voucher_ref", length = 80) private String voucherRef;

  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
  @Column(name = "updated_at") private Instant updatedAt;

  protected ProjectTaskTravelEntryEntity() {}

  ProjectTaskTravelEntryEntity(ProjectTaskEntity task, String traveller, LocalDate travelDate,
      String fromLocation, String toLocation, String purpose, int travelMinutes,
      BigDecimal expenseAmount, String currencyCode, String voucherRef, String createdBy, Instant now) {
    this.task = task;
    this.traveller = requiredIdentity(traveller, "The traveller");
    this.createdBy = requiredIdentity(createdBy, "Created by");
    this.createdAt = now;
    apply(travelDate, fromLocation, toLocation, purpose, travelMinutes, expenseAmount, currencyCode,
        voucherRef);
  }

  /**
   * Corrects an entry in place. Only the person who logged it may reach this, and the correction
   * writes its own history row.
   */
  void correct(LocalDate travelDate, String fromLocation, String toLocation, String purpose,
      int travelMinutes, BigDecimal expenseAmount, String currencyCode, String voucherRef, Instant now) {
    apply(travelDate, fromLocation, toLocation, purpose, travelMinutes, expenseAmount, currencyCode,
        voucherRef);
    this.updatedAt = now;
  }

  private void apply(LocalDate travelDate, String fromLocation, String toLocation, String purpose,
      int travelMinutes, BigDecimal expenseAmount, String currencyCode, String voucherRef) {
    if (travelDate == null) throw new IllegalArgumentException("A travel date is required.");
    this.travelDate = travelDate;
    this.fromLocation = requiredText(fromLocation, "Travelled from", MAX_PLACE);
    this.toLocation = requiredText(toLocation, "Travelled to", MAX_PLACE);
    this.purpose = requiredText(purpose, "The purpose of the journey", MAX_PURPOSE);
    if (travelMinutes < 0) throw new IllegalArgumentException("Travel time cannot be negative.");
    if (travelMinutes > MAX_MINUTES)
      throw new IllegalArgumentException("A single journey cannot be longer than 24 hours.");
    this.travelMinutes = travelMinutes;
    // Zero is allowed: a journey somebody made at no cost is still a journey worth recording.
    BigDecimal amount = expenseAmount == null ? BigDecimal.ZERO : expenseAmount;
    if (amount.signum() < 0) throw new IllegalArgumentException("An expense cannot be negative.");
    this.expenseAmount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
    this.currencyCode = currency(currencyCode);
    this.voucherRef = optionalText(voucherRef, 80);
  }

  /** The traveller or whoever recorded the journey - see {@code ProjectTaskTimeEntryEntity#belongsTo}. */
  public boolean belongsTo(String candidate) {
    if (candidate == null || candidate.isBlank()) return false;
    String trimmed = candidate.trim();
    return traveller.equalsIgnoreCase(trimmed) || createdBy.equalsIgnoreCase(trimmed);
  }

  private static String currency(String value) {
    if (value == null || value.isBlank()) return DEFAULT_CURRENCY;
    String code = value.trim().toUpperCase(java.util.Locale.ROOT);
    if (code.length() != 3 || !code.chars().allMatch(Character::isLetter))
      throw new IllegalArgumentException("A currency code must be three letters, such as INR.");
    return code;
  }

  private static String requiredText(String value, String field, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
    String trimmed = value.trim();
    if (trimmed.length() > max)
      throw new IllegalArgumentException(field + " must be " + max + " characters or fewer.");
    return trimmed;
  }

  private static String optionalText(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    if (trimmed.length() > max)
      throw new IllegalArgumentException("A reference must be " + max + " characters or fewer.");
    return trimmed;
  }

  private static String requiredIdentity(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
    String trimmed = value.trim();
    if (trimmed.length() > 120)
      throw new IllegalArgumentException(field + " must be 120 characters or fewer.");
    return trimmed;
  }

  public Long getId() { return id; }
  public String getTaskId() { return task.getId(); }
  public String getTraveller() { return traveller; }
  public LocalDate getTravelDate() { return travelDate; }
  public String getFromLocation() { return fromLocation; }
  public String getToLocation() { return toLocation; }
  public String getPurpose() { return purpose; }
  public int getTravelMinutes() { return travelMinutes; }
  public BigDecimal getExpenseAmount() { return expenseAmount; }
  public String getCurrencyCode() { return currencyCode; }
  public String getVoucherRef() { return voucherRef; }
  public Instant getCreatedAt() { return createdAt; }
  public String getCreatedBy() { return createdBy; }
  public Instant getUpdatedAt() { return updatedAt; }
}
