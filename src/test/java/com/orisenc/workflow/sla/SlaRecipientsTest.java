package com.orisenc.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who hears about a crossed rung, and who deliberately does not.
 *
 * <p>An escalation ladder that does not widen its audience is just a louder reminder to the person
 * who was already ignoring the first one.
 */
class SlaRecipientsTest {

  private static final Instant DUE = Instant.parse("2026-09-10T12:00:00Z");
  private static final String CONTACT = "operations-lead@orisenc.com";

  @Test
  void aWarningIsTheOwnersBusinessAndNobodyElses() {
    var recipients = SlaRecipients.forStage(owned(), SlaStage.DUE_SOON, CONTACT);

    assertThat(recipients).containsExactly("priya@orisenc.com");
  }

  @Test
  void anUnclaimedItemWarnsWhoeverRaisedIt() {
    // The item most likely to be missed is the one nobody has picked up. Warning nobody about it is
    // the failure mode this branch exists to prevent.
    var recipients = SlaRecipients.forStage(unclaimed(), SlaStage.DUE_SOON, CONTACT);

    assertThat(recipients).containsExactly("ravi@orisenc.com");
  }

  @Test
  void aBreachReachesTheOwnerAndWhoeverIsWaitingOnIt() {
    var recipients = SlaRecipients.forStage(owned(), SlaStage.BREACHED, CONTACT);

    assertThat(recipients).containsExactly("priya@orisenc.com", "ravi@orisenc.com");
  }

  @Test
  void anEscalationGoesOverTheOwnersHead() {
    var recipients = SlaRecipients.forStage(owned(), SlaStage.ESCALATED, CONTACT);

    assertThat(recipients).containsExactly("priya@orisenc.com", "ravi@orisenc.com",
        "chain-owner@orisenc.com", CONTACT);
  }

  @Test
  void withNoConfiguredContactAnEscalationStillReachesTheChainOwner() {
    // The configured mailbox stands in for a manager the platform cannot resolve. Without one, the
    // owner of the parent work item is the nearest thing to a reporting line there is.
    var recipients = SlaRecipients.forStage(owned(), SlaStage.ESCALATED, null);

    assertThat(recipients).containsExactly("priya@orisenc.com", "ravi@orisenc.com",
        "chain-owner@orisenc.com");
  }

  @Test
  void somebodyWhoRaisedAndThenClaimedTheWorkIsToldOnce() {
    // Deduplication has to be case-insensitive: UPNs are compared that way everywhere else, and the
    // Integration Service's idempotency key uses the recipient exactly as written, so it would not
    // catch a second copy addressed differently.
    var selfServed = new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1", "Site visit", "DELIVERABLE", DUE,
        "Priya@orisenc.com", "priya@ORISENC.com", "Operations", null, 0);

    assertThat(SlaRecipients.forStage(selfServed, SlaStage.BREACHED, null))
        .containsExactly("Priya@orisenc.com");
  }

  @Test
  void anApprovalHasNoChainOwnerSoItsEscalationIsTheConfiguredMailbox() {
    var approval = new SlaCandidate(SlaFamily.APPROVAL, "TSK-1", "Approve credit terms", "APPROVAL", DUE,
        "priya@orisenc.com", "ravi@orisenc.com", "Finance", null, 0);

    assertThat(SlaRecipients.forStage(approval, SlaStage.ESCALATED, CONTACT))
        .containsExactly("priya@orisenc.com", "ravi@orisenc.com", CONTACT);
  }

  @Test
  void blanksAreNotRecipients() {
    var thin = new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1", "Site visit", "DELIVERABLE", DUE, "   ",
        "ravi@orisenc.com", "Operations", "", 0);

    assertThat(SlaRecipients.forStage(thin, SlaStage.ESCALATED, "  "))
        .containsExactly("ravi@orisenc.com");
  }

  @Test
  void theOwnersDelegateAppearsAtEveryStage() {
    var delegates = List.of("cover@orisenc.com");

    assertThat(SlaRecipients.forStage(owned(), SlaStage.DUE_SOON, CONTACT, delegates))
        .containsExactly("priya@orisenc.com", "cover@orisenc.com");
    assertThat(SlaRecipients.forStage(owned(), SlaStage.BREACHED, CONTACT, delegates))
        .containsExactly("priya@orisenc.com", "cover@orisenc.com", "ravi@orisenc.com");
    assertThat(SlaRecipients.forStage(owned(), SlaStage.ESCALATED, CONTACT, delegates))
        .containsExactly("priya@orisenc.com", "cover@orisenc.com", "ravi@orisenc.com",
            "chain-owner@orisenc.com", CONTACT);
  }

  private static SlaCandidate owned() {
    return new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1A2B", "Deliver order SO-90812", "DELIVERABLE",
        DUE, "priya@orisenc.com", "ravi@orisenc.com", "Operations", "chain-owner@orisenc.com", 0);
  }

  private static SlaCandidate unclaimed() {
    return new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1A2B", "Deliver order SO-90812", "DELIVERABLE",
        DUE, null, "ravi@orisenc.com", "Operations", "chain-owner@orisenc.com", 0);
  }
}
