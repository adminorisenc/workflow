package com.orisenc.workflow.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import com.orisenc.workflow.notify.DelegationNotification.Kind;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The audience and template contract for delegation lifecycle messages. */
class DelegationNotifierTest {

  private static final Instant FROM = Instant.parse("2026-09-10T03:30:00Z");
  private static final Instant UNTIL = Instant.parse("2026-09-20T12:30:00Z");

  private IntegrationServiceClient integration;
  private DelegationNotifier notifier;

  @BeforeEach
  void setUp() {
    integration = mock(IntegrationServiceClient.class);
    notifier = new DelegationNotifier(() -> integration, "Asia/Kolkata");
  }

  @Test
  void activationReachesTheDelegate() {
    assertThat(notifier.raise(active("manager@orisenc.com"))).isEqualTo(1);

    var raised = capture();
    assertThat(raised.recipient()).isEqualTo("delegate@orisenc.com");
    assertThat(raised.eventType()).isEqualTo("DELEGATION_ACTIVE");
    assertThat(raised.templateCode()).isEqualTo("DELEGATION_ACTIVE");
    assertThat(raised.critical()).isTrue();
  }

  @Test
  void anEndedDelegationReachesBothParties() {
    var event = ended("administrator@orisenc.com");

    assertThat(notifier.raise(event)).isEqualTo(2);
    assertThat(DelegationNotifier.recipientsFor(event))
        .containsExactly("delegate@orisenc.com", "delegator@orisenc.com");
  }

  @Test
  void theActorIsExcludedFromTheAudience() {
    assertThat(DelegationNotifier.recipientsFor(ended("Delegate@ORISENC.com")))
        .containsExactly("delegator@orisenc.com");
    assertThat(DelegationNotifier.recipientsFor(ended("delegator@orisenc.com")))
        .containsExactly("delegate@orisenc.com");
  }

  @Test
  void everyTemplateValueIsPresentAndReadable() {
    assertThat(notifier.values(active("manager@orisenc.com")))
        .containsEntry("delegationId", "DLG-1234")
        .containsEntry("delegator", "delegator@orisenc.com")
        .containsEntry("delegate", "delegate@orisenc.com")
        .containsEntry("actor", "manager@orisenc.com")
        .containsEntry("scope", "approvals and work items")
        .containsEntry("taskType", "DELIVERABLE")
        .containsEntry("department", "Operations")
        .containsEntry("validFrom", "10 Sep 2026, 09:00 (Asia/Kolkata)")
        .containsEntry("validUntil", "20 Sep 2026, 18:00 (Asia/Kolkata)")
        .containsEntry("reason", "Annual leave")
        .containsEntry("outcome", "approved");
  }

  @Test
  void withNoRouteNothingIsSentAndNothingThrows() {
    var offline = new DelegationNotifier(() -> null, "Asia/Kolkata");

    assertThat(offline.raise(active("manager@orisenc.com"))).isZero();
    verifyNoInteractions(integration);
  }

  private NotificationRequest capture() {
    var captor = ArgumentCaptor.forClass(NotificationRequest.class);
    verify(integration).raiseNotification(captor.capture());
    return captor.getValue();
  }

  private static DelegationNotification active(String actor) {
    return event(Kind.ACTIVE, actor, "approved");
  }

  private static DelegationNotification ended(String actor) {
    return event(Kind.ENDED, actor, "revoked");
  }

  private static DelegationNotification event(Kind kind, String actor, String outcome) {
    return new DelegationNotification(kind, "DLG-1234", "DLG-1234#7",
        "delegator@orisenc.com", "delegate@orisenc.com", actor, "ALL", "DELIVERABLE",
        "Operations", FROM, UNTIL, "Annual leave", outcome);
  }
}
