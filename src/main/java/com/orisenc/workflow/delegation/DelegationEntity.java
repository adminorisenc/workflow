package com.orisenc.workflow.delegation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One person standing in for another, for a while (REQ-0027).
 *
 * <p>Four things make this a delegation record rather than a note that somebody is away.
 *
 * <ol>
 *   <li><b>It is time-bound.</b> {@code validFrom} and {@code validUntil} are both required, and the
 *       scheduled expiry pass closes the window without anybody remembering to. A standing
 *       delegation with no end is how authority quietly becomes permanent.
 *   <li><b>Privileged ones are approved by a third person.</b> Handing over approval authority is
 *       not the delegator's decision alone - see {@link DelegationScope#privileged()} - and the
 *       approver may be neither the delegator nor the delegate.
 *   <li><b>It narrows.</b> Scope, task type and department each optionally restrict what it covers,
 *       so "approve my Finance credit reviews while I am away" does not also hand over every field
 *       visit.
 *   <li><b>Every move is on the record.</b> Status changes append a {@link DelegationEventEntity}
 *       in the same call, for the same reason a work item's transition does: a status without its
 *       reason is an audit hole.
 * </ol>
 *
 * <h2>What a delegation does not do</h2>
 *
 * <p><b>It grants no permission.</b> A delegate must already hold the permission for the action they
 * are taking; what the delegation adds is the record-level right to take it on somebody else's
 * items. Common Platform is the RBAC store, and a row in this table must not be able to mint an
 * authority the access service never granted - that is precisely the back door the platform's
 * deny-by-default stance exists to close. In practice this means a delegate who cannot approve
 * anything still cannot approve; the fix is a role, granted and audited where roles live.
 */
@Entity
@Table(name = "workflow_delegation", indexes = {
    @Index(name = "idx_delegation_delegate_status", columnList = "delegate_user_id,status"),
    @Index(name = "idx_delegation_delegator_status", columnList = "delegator_user_id,status"),
    @Index(name = "idx_delegation_status_until", columnList = "status,valid_until")
})
public class DelegationEntity {

  /** The actor on an event row nobody chose to write. */
  public static final String SYSTEM_ACTOR = "delegation-monitor";

  public static final int MAX_REASON = 500;

  @Id @Column(length = 40) private String id;
  @Version private long version;

  /** Whose work is being handed over. Their UPN, as every other identity in this service. */
  @Column(name = "delegator_user_id", nullable = false, length = 120) private String delegatorUserId;
  /** Who is standing in. */
  @Column(name = "delegate_user_id", nullable = false, length = 120) private String delegateUserId;

  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DelegationScope scope;

  /**
   * The enum name of a single task type this is narrowed to, or null for every type in scope.
   *
   * <p>A plain string rather than an enum column because the two families have different type enums
   * and this one column serves both. The service validates it against whichever enum the scope
   * implies, and refuses a type on a delegation scoped to {@link DelegationScope#ALL}: a name that
   * exists in only one family cannot narrow both, and silently applying it to one would be a rule
   * nobody could read off the record.
   */
  @Column(name = "task_type", length = 30) private String taskType;

  /** Department or relevant team this is narrowed to, or null for all. Compared case-insensitively. */
  @Column(length = 60) private String department;

  @Column(name = "valid_from", nullable = false) private Instant validFrom;
  @Column(name = "valid_until", nullable = false) private Instant validUntil;

  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DelegationStatus status;

  /** Why the delegator is away. Required: a delegation with no stated reason is not reviewable. */
  @Column(nullable = false, length = MAX_REASON) private String reason;

  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  /** Null on a delegation that never needed approval, which is not the same as never approved. */
  @Column(name = "decided_by", length = 120) private String decidedBy;
  @Column(name = "decided_at") private Instant decidedAt;
  @Column(name = "decision_reason", length = MAX_REASON) private String decisionReason;

  @Column(name = "ended_at") private Instant endedAt;

  @OneToMany(mappedBy = "delegation", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("occurredAt ASC") private List<DelegationEventEntity> events = new ArrayList<>();

  protected DelegationEntity() {}

  public DelegationEntity(String id, String delegatorUserId, String delegateUserId,
      DelegationScope scope, String taskType, String department, Instant validFrom, Instant validUntil,
      String reason, String createdBy, Instant createdAt) {
    this.id = id;
    this.delegatorUserId = delegatorUserId;
    this.delegateUserId = delegateUserId;
    this.scope = scope;
    this.taskType = taskType;
    this.department = department;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.reason = reason;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    // The scope decides the starting state, not the caller. A request that could ask for its own
    // delegation to skip approval would make the approval gate decorative.
    this.status = scope.privileged() ? DelegationStatus.PENDING_APPROVAL : DelegationStatus.ACTIVE;
  }

  /**
   * Whether this authorizes anybody at this instant.
   *
   * <p>Status and window, both. The window is inclusive at the start and exclusive at the end, so a
   * delegation running until Monday 09:00 stops authorizing at 09:00 rather than covering a
   * decision taken during that minute.
   */
  public boolean inForce(Instant now) {
    return status == DelegationStatus.ACTIVE
        && !now.isBefore(validFrom)
        && now.isBefore(validUntil);
  }

  /**
   * Whether this delegation covers a particular piece of work.
   *
   * <p>The workbook's criterion in one method: scope, task type, department. Absent narrowing means
   * "do not narrow on this", never "match nothing" - a delegation with no department set covers
   * every department, which is what somebody who typed nothing meant.
   */
  public boolean covers(DelegationTarget target) {
    if (target == null || !scope.covers(target.family())) return false;
    if (taskType != null && !taskType.equalsIgnoreCase(target.taskType())) return false;
    return department == null || department.equalsIgnoreCase(trim(target.department()));
  }

  /** True when {@code actor} may act as the delegator on this work right now. */
  public boolean authorizes(String actor, DelegationTarget target, Instant now) {
    return inForce(now) && delegateUserId.equalsIgnoreCase(trim(actor)) && covers(target);
  }

  /**
   * Approves a delegation waiting on a decision.
   *
   * @throws IllegalArgumentException when it is not waiting on one, or the approver is the delegator
   *     or the delegate. Enforced here rather than in the service so no caller can route around it.
   */
  public void approve(String approver, String comment, Instant time, String correlationId) {
    assertDecidable(approver);
    status = DelegationStatus.ACTIVE;
    decidedBy = approver;
    decidedAt = time;
    decisionReason = comment;
    events.add(new DelegationEventEntity(this, "APPROVED", approver, comment, time, correlationId));
  }

  /** Refuses it, permanently. The reason is mandatory: a refusal nobody can read is not a decision. */
  public void reject(String approver, String comment, Instant time, String correlationId) {
    assertDecidable(approver);
    if (comment == null || comment.isBlank())
      throw new IllegalArgumentException("A reason is required when refusing a delegation");
    status = DelegationStatus.REJECTED;
    decidedBy = approver;
    decidedAt = time;
    decisionReason = comment.trim();
    endedAt = time;
    events.add(new DelegationEventEntity(this, "REJECTED", approver, comment.trim(), time, correlationId));
  }

  /** Ends it early. Legal from either open state: a delegation nobody approved can still be withdrawn. */
  public void revoke(String actor, String comment, Instant time, String correlationId) {
    if (status.terminal())
      throw new IllegalArgumentException("This delegation has already ended as " + readable(status));
    if (comment == null || comment.isBlank())
      throw new IllegalArgumentException("A reason is required when revoking a delegation");
    status = DelegationStatus.REVOKED;
    endedAt = time;
    events.add(new DelegationEventEntity(this, "REVOKED", actor, comment.trim(), time, correlationId));
  }

  /**
   * Closes the window. Written by the scheduled pass, never by a person.
   *
   * @throws IllegalArgumentException when it has already ended, which is how an overlapping pass is
   *     stopped from writing a second expiry event
   */
  public void expire(Instant time, String correlationId) {
    if (status.terminal())
      throw new IllegalArgumentException("This delegation has already ended as " + readable(status));
    status = DelegationStatus.EXPIRED;
    endedAt = time;
    events.add(new DelegationEventEntity(this, "EXPIRED", SYSTEM_ACTOR,
        "The delegation window closed.", time, correlationId));
  }

  public void addEvent(String action, String actor, String comment, Instant time, String correlationId) {
    events.add(new DelegationEventEntity(this, action, actor, comment, time, correlationId));
  }

  private void assertDecidable(String approver) {
    if (status != DelegationStatus.PENDING_APPROVAL)
      throw new IllegalArgumentException("This delegation is " + readable(status)
          + ", not waiting on a decision");
    // Maker-checker, the same rule the platform applies to a credit exception: the person who asked
    // does not decide, and neither does the person who benefits.
    if (delegatorUserId.equalsIgnoreCase(trim(approver)))
      throw new IllegalArgumentException("You cannot approve your own delegation");
    if (delegateUserId.equalsIgnoreCase(trim(approver)))
      throw new IllegalArgumentException("You cannot approve a delegation that hands work to you");
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String readable(DelegationStatus value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  public String getId() { return id; }
  public long getVersion() { return version; }
  public String getDelegatorUserId() { return delegatorUserId; }
  public String getDelegateUserId() { return delegateUserId; }
  public DelegationScope getScope() { return scope; }
  public String getTaskType() { return taskType; }
  public String getDepartment() { return department; }
  public Instant getValidFrom() { return validFrom; }
  public Instant getValidUntil() { return validUntil; }
  public DelegationStatus getStatus() { return status; }
  public String getReason() { return reason; }
  public String getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public String getDecidedBy() { return decidedBy; }
  public Instant getDecidedAt() { return decidedAt; }
  public String getDecisionReason() { return decisionReason; }
  public Instant getEndedAt() { return endedAt; }

  public List<DelegationEventEntity> getEvents() { return List.copyOf(events); }
}
