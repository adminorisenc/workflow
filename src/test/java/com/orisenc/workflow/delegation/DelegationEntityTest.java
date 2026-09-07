package com.orisenc.workflow.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * DB-free coverage of the rules that make a delegation a transfer of authority rather than a note.
 *
 * <p>Two of them are worth the most. The maker-checker gate is the whole content of REQ-0027's
 * "privileged-delegation approval", and it lives on the entity precisely so no caller can route
 * around it. The window is what makes the delegation time-bound, and its boundaries are the kind of
 * thing that is off by one for months without anybody noticing - until somebody approves a payment
 * one minute after their authority ended and the audit cannot say whether that was allowed.
 */
class DelegationEntityTest {

  private static final Instant NOW = Instant.parse("2026-09-10T09:00:00Z");
  private static final Instant FROM = NOW;
  private static final Instant UNTIL = NOW.plus(7, ChronoUnit.DAYS);

  private static final String DELEGATOR = "priya@orisenc.com";
  private static final String DELEGATE = "ravi@orisenc.com";
  private static final String APPROVER = "lead@orisenc.com";

  private DelegationEntity delegation(DelegationScope scope, String taskType, String department) {
    return new DelegationEntity("DLG-TEST01", DELEGATOR, DELEGATE, scope, taskType, department,
        FROM, UNTIL, "Annual leave", DELEGATOR, NOW);
  }

  @Test
  void handingOverApprovalAuthorityWaitsForSomebodyElse() {
    // The privileged case. It is created, and it authorizes nobody.
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.PENDING_APPROVAL);
    assertThat(delegation.inForce(NOW)).isFalse();
  }

  @Test
  void handingOverWorkIsLiveImmediately() {
    // Doing somebody's deliveries is not a transfer of authority, and making it wait for an
    // administrator is how cover fails to exist on the morning somebody actually goes away.
    var delegation = delegation(DelegationScope.WORK_ITEMS, null, null);

    assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.ACTIVE);
    assertThat(delegation.inForce(NOW)).isTrue();
  }

  @Test
  void theScopeDecidesWhetherApprovalIsNeededAndTheRequestCannotChangeThat() {
    assertThat(DelegationScope.ALL.privileged()).isTrue();
    assertThat(DelegationScope.APPROVALS.privileged()).isTrue();
    assertThat(DelegationScope.WORK_ITEMS.privileged()).isFalse();
    // A caller that could ask for its own delegation to skip approval would make the gate decorative,
    // which is why the constructor derives the starting status rather than accepting one.
    assertThat(delegation(DelegationScope.ALL, null, null).getStatus())
        .isEqualTo(DelegationStatus.PENDING_APPROVAL);
  }

  @Test
  void youCannotApproveYourOwnDelegation() {
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    assertThatThrownBy(() -> delegation.approve(DELEGATOR, "fine", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("your own delegation");
    assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.PENDING_APPROVAL);
  }

  @Test
  void youCannotApproveADelegationThatHandsWorkToYou() {
    // The other half of maker-checker, and the one an implementation forgets: the beneficiary is as
    // interested a party as the requester.
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    assertThatThrownBy(() -> delegation.approve(DELEGATE, "fine", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hands work to you");
  }

  @Test
  void approvingItRecordsWhoDecidedAndWhen() {
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    delegation.approve(APPROVER, "Cover agreed with the team.", NOW, "c");

    assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.ACTIVE);
    assertThat(delegation.getDecidedBy()).isEqualTo(APPROVER);
    assertThat(delegation.getDecidedAt()).isEqualTo(NOW);
    assertThat(delegation.getEvents()).extracting(DelegationEventEntity::getAction)
        .containsExactly("APPROVED");
  }

  @Test
  void refusingItNeedsAReason() {
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    assertThatThrownBy(() -> delegation.reject(APPROVER, "  ", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason is required");
  }

  @Test
  void aDelegationNobodyApprovedCanStillBeWithdrawn() {
    // Revocation is legal from either open state. Somebody who arranged cover and then did not go
    // away should not have to wait for an approval in order to cancel it.
    var delegation = delegation(DelegationScope.APPROVALS, null, null);

    delegation.revoke(DELEGATOR, "Trip cancelled.", NOW, "c");

    assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.REVOKED);
    assertThat(delegation.getEndedAt()).isEqualTo(NOW);
  }

  @Test
  void aDelegationThatHasEndedCannotEndTwice() {
    // How an overlapping expiry pass is stopped from writing a second event, and how a double-clicked
    // revoke stops at one.
    var delegation = delegation(DelegationScope.WORK_ITEMS, null, null);
    delegation.revoke(DELEGATOR, "Trip cancelled.", NOW, "c");

    assertThatThrownBy(() -> delegation.expire(NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already ended as revoked");
  }

  @Test
  void theWindowIsInclusiveAtTheStartAndExclusiveAtTheEnd() {
    // A delegation running until Monday 09:00 stops authorizing at 09:00, rather than covering a
    // decision taken during that minute. Somebody has to choose; this is the choice, written down.
    var delegation = delegation(DelegationScope.WORK_ITEMS, null, null);

    assertThat(delegation.inForce(FROM.minusSeconds(1))).isFalse();
    assertThat(delegation.inForce(FROM)).isTrue();
    assertThat(delegation.inForce(UNTIL.minusSeconds(1))).isTrue();
    assertThat(delegation.inForce(UNTIL)).isFalse();
  }

  @Test
  void anExpiredDelegationAuthorizesNobodyEvenInsideItsOwnWindow() {
    var delegation = delegation(DelegationScope.WORK_ITEMS, null, null);
    delegation.expire(NOW.plus(1, ChronoUnit.DAYS), "c");

    assertThat(delegation.inForce(NOW.plus(2, ChronoUnit.DAYS))).isFalse();
  }

  @Test
  void narrowingIsAbsentRatherThanEmpty() {
    // "No department set" means every department, which is what somebody who typed nothing meant.
    // Reading it as "match nothing" would make the commonest delegation cover no work at all.
    var wide = delegation(DelegationScope.WORK_ITEMS, null, null);

    assertThat(wide.covers(DelegationTarget.workItem("DELIVERABLE", "Operations"))).isTrue();
    assertThat(wide.covers(DelegationTarget.workItem("FIELD_VISIT", "Tech"))).isTrue();
  }

  @Test
  void aDelegationNarrowedToATypeCoversOnlyThatType() {
    var narrow = delegation(DelegationScope.WORK_ITEMS, "DELIVERABLE", null);

    assertThat(narrow.covers(DelegationTarget.workItem("DELIVERABLE", "Operations"))).isTrue();
    assertThat(narrow.covers(DelegationTarget.workItem("FIELD_VISIT", "Operations"))).isFalse();
  }

  @Test
  void aDelegationNarrowedToADepartmentCoversOnlyThatDepartment() {
    var narrow = delegation(DelegationScope.APPROVALS, null, "Finance");

    assertThat(narrow.covers(DelegationTarget.approval("APPROVAL", "Finance"))).isTrue();
    // Case-insensitive, because relevant_team and department are free text a person typed.
    assertThat(narrow.covers(DelegationTarget.approval("APPROVAL", "finance"))).isTrue();
    assertThat(narrow.covers(DelegationTarget.approval("APPROVAL", "Operations"))).isFalse();
  }

  @Test
  void aDelegationNeverReachesTheOtherFamily() {
    // The distinction the whole scope enum exists for: covering somebody's deliveries must not hand
    // over their approvals.
    var work = delegation(DelegationScope.WORK_ITEMS, null, null);

    assertThat(work.covers(DelegationTarget.approval("APPROVAL", "Finance"))).isFalse();
    assertThat(delegation(DelegationScope.ALL, null, null)
        .covers(DelegationTarget.approval("APPROVAL", "Finance"))).isTrue();
  }

  @Test
  void authorisingIsTheWindowTheDelegateAndTheCoverTogether() {
    var delegation = delegation(DelegationScope.WORK_ITEMS, null, "Operations");
    var target = DelegationTarget.workItem("DELIVERABLE", "Operations");

    assertThat(delegation.authorizes(DELEGATE, target, NOW)).isTrue();
    // Not the delegate.
    assertThat(delegation.authorizes("someone.else@orisenc.com", target, NOW)).isFalse();
    // Outside the window.
    assertThat(delegation.authorizes(DELEGATE, target, UNTIL)).isFalse();
    // Not covered.
    assertThat(delegation.authorizes(DELEGATE, DelegationTarget.workItem("DELIVERABLE", "Tech"), NOW))
        .isFalse();
  }
}
