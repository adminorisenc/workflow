package com.orisenc.workflow.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import com.orisenc.workflow.notify.WorkItemNotification.Kind;
import com.orisenc.workflow.sla.SlaFamily;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who is told when work moves.
 *
 * <p>The rule doing the most work here is the one that removes the actor. Telling somebody what they
 * themselves just did is how a person learns to filter these messages away, and once they have, the
 * escalation that actually needed reading goes with them.
 */
class WorkItemNotifierTest {

  private static final Instant DUE = Instant.parse("2026-09-10T12:00:00Z");

  private IntegrationServiceClient integration;
  private WorkItemNotifier notifier;

  @BeforeEach
  void setUp() {
    integration = mock(IntegrationServiceClient.class);
    notifier = new WorkItemNotifier(() -> integration, "UTC");
  }

  @Test
  void anAssignmentReachesTheNewOwnerAndNobodyElse() {
    int queued = notifier.raise(assigned("priya@orisenc.com", "ravi@orisenc.com"));

    assertThat(queued).isEqualTo(1);
    var raised = capture();
    assertThat(raised.recipient()).isEqualTo("priya@orisenc.com");
    assertThat(raised.eventType()).isEqualTo("WORK_ITEM_ASSIGNED");
    // Critical: work handed to you is not something an opt-out from routine mail should silence.
    assertThat(raised.critical()).isTrue();
  }

  @Test
  void assigningWorkToYourselfTellsNobody() {
    int queued = notifier.raise(assigned("priya@orisenc.com", "priya@ORISENC.com"));

    assertThat(queued).isZero();
    verifyNoInteractions(integration);
  }

  @Test
  void aStatusChangeReachesTheOwnerAndWhoeverRaisedTheWork() {
    var event = new WorkItemNotification(Kind.STATUS_CHANGED, SlaFamily.WORK_ITEM, "WRK-1A2B",
        "Deliver order SO-90812", "WRK-1A2B#41", "priya@orisenc.com", "ravi@orisenc.com",
        "lead@orisenc.com", "BLOCKED", "IN_PROGRESS", "Vendor has not confirmed the pickup.", DUE);

    assertThat(notifier.raise(event)).isEqualTo(2);
    assertThat(WorkItemNotifier.recipientsFor(event))
        .containsExactly("priya@orisenc.com", "ravi@orisenc.com");
  }

  @Test
  void theActorIsRemovedFromTheirOwnStatusChange() {
    var event = new WorkItemNotification(Kind.STATUS_CHANGED, SlaFamily.WORK_ITEM, "WRK-1A2B",
        "Deliver order SO-90812", "WRK-1A2B#41", "priya@orisenc.com", "ravi@orisenc.com",
        "priya@orisenc.com", "COMPLETED", "IN_PROGRESS", "Delivered and signed.", DUE);

    assertThat(WorkItemNotifier.recipientsFor(event)).containsExactly("ravi@orisenc.com");
  }

  @Test
  void somebodyWhoRaisedTheirOwnWorkIsToldOnce() {
    var event = new WorkItemNotification(Kind.STATUS_CHANGED, SlaFamily.WORK_ITEM, "WRK-1A2B",
        "Site visit", "WRK-1A2B#41", "Priya@orisenc.com", "priya@ORISENC.com", "lead@orisenc.com",
        "COMPLETED", "IN_PROGRESS", "Visited.", DUE);

    assertThat(WorkItemNotifier.recipientsFor(event)).containsExactly("Priya@orisenc.com");
  }

  @Test
  void anUnclaimedItemChangingStatusStillTellsWhoeverRaisedIt() {
    var event = new WorkItemNotification(Kind.STATUS_CHANGED, SlaFamily.WORK_ITEM, "WRK-1A2B",
        "Site visit", "WRK-1A2B#41", null, "ravi@orisenc.com", "lead@orisenc.com", "CANCELLED",
        "NOT_STARTED", "Customer postponed.", DUE);

    assertThat(WorkItemNotifier.recipientsFor(event)).containsExactly("ravi@orisenc.com");
  }

  @Test
  void anApprovalIsAnnouncedUnderItsOwnEventName() {
    var event = new WorkItemNotification(Kind.STATUS_CHANGED, SlaFamily.APPROVAL, "TSK-99AA",
        "Approve credit terms", "TSK-99AA#7", "priya@orisenc.com", "ravi@orisenc.com",
        "priya@orisenc.com", "COMPLETED", "IN_PROGRESS", "Terms match the contract.", DUE);

    notifier.raise(event);

    assertThat(capture().eventType()).isEqualTo("APPROVAL_STATUS_CHANGED");
  }

  @Test
  void theEventReferenceIsTheOccurrenceRatherThanTheTask() {
    // The Integration Service is idempotent on event type, reference and recipient. With the task id
    // alone, reassigning work away from somebody and back again would announce only the first move.
    notifier.raise(assigned("priya@orisenc.com", "ravi@orisenc.com"));

    assertThat(capture().eventRef()).isEqualTo("WRK-1A2B#41");
  }

  @Test
  void everyValueTheTemplatesCanAskForIsSupplied() {
    var values = notifier.values(assigned("priya@orisenc.com", "ravi@orisenc.com"));

    assertThat(values).containsKeys("kind", "taskId", "title", "owner", "actor", "status",
        "previousStatus", "reason", "dueAt");
    // Nothing is left blank: an unresolved placeholder is a dead letter, and a resolved empty one is
    // a sentence with a hole in it.
    assertThat(values.values()).noneMatch(String::isBlank);
  }

  @Test
  void withNoRouteNothingIsSentAndNothingThrows() {
    var offline = new WorkItemNotifier(() -> null, "UTC");

    assertThat(offline.raise(assigned("priya@orisenc.com", "ravi@orisenc.com"))).isZero();
  }

  @Test
  void aRefusedMessageIsDroppedRatherThanFailingTheOperationThatAlreadyCommitted() {
    // By the time this runs the transition is committed and the caller has been answered. Throwing
    // would surface as a failure of an operation that actually succeeded.
    doThrow(new IllegalStateException("integration is down")).when(integration).raiseNotification(any());

    assertThat(notifier.raise(assigned("priya@orisenc.com", "ravi@orisenc.com"))).isZero();
  }

  private NotificationRequest capture() {
    var captor = ArgumentCaptor.forClass(NotificationRequest.class);
    verify(integration, org.mockito.Mockito.atLeastOnce()).raiseNotification(captor.capture());
    return captor.getValue();
  }

  private static WorkItemNotification assigned(String owner, String actor) {
    return new WorkItemNotification(Kind.ASSIGNED, SlaFamily.WORK_ITEM, "WRK-1A2B",
        "Deliver order SO-90812", "WRK-1A2B#41", owner, "ravi@orisenc.com", actor, "NOT_STARTED",
        null, null, DUE);
  }
}
