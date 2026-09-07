package com.orisenc.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * One pass over the deadlines: what it raises, what it deliberately leaves alone, and what it
 * refuses to do when it cannot reach anybody.
 *
 * <p>The two rules worth the most here are the ones that are invisible when they work. A pass that
 * re-raises every overdue item every quarter hour looks fine in a demo and empties a mailbox in a
 * week. A pass that stamps an escalation level after failing to send it looks fine in the database
 * and means nobody was ever told.
 */
class SlaEscalationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

  private SlaCandidates candidates;
  private IntegrationServiceClient integration;
  private DelegationService delegations;
  private SlaProperties properties;
  private SlaEscalationService service;

  @BeforeEach
  void setUp() {
    candidates = mock(SlaCandidates.class);
    integration = mock(IntegrationServiceClient.class);
    delegations = mock(DelegationService.class);
    // Nobody is standing in for anybody, unless a test says otherwise.
    when(delegations.delegatesOf(any(), any())).thenReturn(List.of());
    properties = new SlaProperties(true, null, "UTC", Duration.ofHours(24),
        List.of(Duration.ofHours(24), Duration.ofHours(72)), "ops-lead@orisenc.com", 500);
    when(candidates.openWorkItems(any(), anyInt())).thenReturn(List.of());
    when(candidates.openApprovals(any(), anyInt())).thenReturn(List.of());
    when(candidates.recordEscalation(any(), anyString(), anyInt(), anyString(), any(), anyString()))
        .thenReturn(true);
    service = new SlaEscalationService(candidates, properties, delegations, () -> integration,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void withNoRouteToTheIntegrationServiceNothingIsReadAndNothingIsStamped() {
    // An escalation is somebody being told. Writing the level with no way to tell anybody would make
    // the item look chased when it had not been, which is worse than the pass not running.
    var offline = new SlaEscalationService(candidates, properties, delegations, () -> null,
        Clock.fixed(NOW, ZoneOffset.UTC));

    var run = offline.runOnce();

    assertThat(run.routeAvailable()).isFalse();
    assertThat(run.escalated()).isZero();
    verifyNoInteractions(candidates);
  }

  @Test
  void anItemAtARungItHasAlreadyReachedIsLeftAlone() {
    // The overwhelming majority of every quarter-hourly pass. Without the stored rung this is where
    // the same breach would be re-sent ninety-six times a day.
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofHours(1)), 2)));

    var run = service.runOnce();

    assertThat(run.examined()).isEqualTo(1);
    assertThat(run.escalated()).isZero();
    verifyNoInteractions(integration);
  }

  @Test
  void anItemFourDaysLateIsRaisedOnceAtTheRungItHasActuallyReached() {
    // Not four messages. This is the first pass over a neglected backlog, and it is the moment the
    // ladder either behaves or floods somebody's inbox.
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofDays(4)), 0)));

    var run = service.runOnce();

    assertThat(run.escalated()).isEqualTo(1);
    var raised = captureRaised();
    assertThat(raised).extracting(NotificationRequest::eventType)
        .containsOnly("WORK_ITEM_ESCALATED_2");
    verify(candidates).recordEscalation(eq(SlaFamily.WORK_ITEM), eq("WRK-1A2B"), eq(4), anyString(),
        eq(NOW), anyString());
  }

  @Test
  void anEscalationWidensToEveryoneAboveTheOwner() {
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofDays(4)), 0)));

    service.runOnce();

    assertThat(captureRaised()).extracting(NotificationRequest::recipient)
        .containsExactly("priya@orisenc.com", "ravi@orisenc.com", "chain-owner@orisenc.com",
            "ops-lead@orisenc.com");
  }

  @Test
  void aWarningGoesOnlyToTheOwnerAndStaysMuteable() {
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.plus(Duration.ofHours(6)), 0)));

    var run = service.runOnce();

    assertThat(run.notified()).isEqualTo(1);
    var raised = captureRaised().getFirst();
    assertThat(raised.eventType()).isEqualTo("WORK_ITEM_DUE_SOON");
    assertThat(raised.recipient()).isEqualTo("priya@orisenc.com");
    assertThat(raised.critical()).isFalse();
    assertThat(raised.values()).containsEntry("dueIn", "6 hours");
  }

  @Test
  void anApprovalIsChasedThroughTheSameLadderUnderItsOwnEventName() {
    // One ladder for both families - an approval waiting on a decision and a delivery waiting on a
    // driver are the same problem - but separate event names, so a delivery log can answer "was
    // this approver ever chased" without being read past every work item.
    when(candidates.openApprovals(any(), anyInt()))
        .thenReturn(List.of(new SlaCandidate(SlaFamily.APPROVAL, "TSK-99AA", "Approve credit terms",
            "APPROVAL", NOW.minus(Duration.ofHours(2)), "priya@orisenc.com", "ravi@orisenc.com",
            "Finance", null, 0)));

    var run = service.runOnce();

    assertThat(run.escalated()).isEqualTo(1);
    var raised = captureRaised();
    assertThat(raised).extracting(NotificationRequest::eventType)
        .containsOnly("APPROVAL_SLA_BREACHED");
    assertThat(raised).extracting(NotificationRequest::critical).containsOnly(Boolean.TRUE);
  }

  @Test
  void anItemNobodyCouldBeToldAboutKeepsItsOldRungSoTheNextPassTriesAgain() {
    // Stamping first would mean a failed raise silently consumed the rung and the escalation was
    // never sent to anybody, ever - the failure this ordering exists to prevent.
    doThrow(new IllegalStateException("integration is down")).when(integration)
        .raiseNotification(any());
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofHours(2)), 0)));

    var run = service.runOnce();

    assertThat(run.failed()).isEqualTo(1);
    assertThat(run.escalated()).isZero();
    verify(candidates, never()).recordEscalation(any(), anyString(), anyInt(), anyString(), any(),
        anyString());
  }

  @Test
  void reachingSomeOfTheAudienceIsEnoughToRecordTheRung() {
    // Telling three of four people beats telling none, and the item must not stay at its old rung -
    // the next pass would raise the whole set again and the three who heard would hear twice.
    doThrow(new IllegalStateException("one bad address")).doNothing().when(integration)
        .raiseNotification(any());
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofHours(2)), 0)));

    var run = service.runOnce();

    assertThat(run.failed()).isZero();
    assertThat(run.escalated()).isEqualTo(1);
    assertThat(run.notified()).isEqualTo(1);
  }

  @Test
  void aRungAlreadyRecordedByAnOverlappingPassIsNotCountedTwice() {
    // Two passes overlapping - a slow run still going when the next starts. The entity refuses the
    // second stamp; the run simply reports that it did not record one.
    when(candidates.recordEscalation(any(), anyString(), anyInt(), anyString(), any(), anyString()))
        .thenReturn(false);
    when(candidates.openWorkItems(any(), anyInt()))
        .thenReturn(List.of(workItem(NOW.minus(Duration.ofHours(2)), 0)));

    var run = service.runOnce();

    assertThat(run.escalated()).isZero();
    assertThat(run.notified()).isEqualTo(2);
  }

  @Test
  void theMessageCarriesEveryValueTheTemplatesCanAskFor() {
    // The Integration Service refuses to render an unresolved placeholder, so a missing key here is
    // a dead letter rather than a bad sentence. Supplying the full set for every stage is what keeps
    // a template edit from becoming an outage.
    var values = service.values(workItem(NOW.minus(Duration.ofHours(51)), 0), SlaStage.ESCALATED, 3,
        NOW);

    assertThat(values).containsKeys("kind", "taskId", "title", "owner", "team", "dueAt", "dueIn",
        "overdueFor", "step");
    assertThat(values).containsEntry("kind", "work item").containsEntry("overdueFor", "2 days, 3 hours")
        .containsEntry("step", "1");
  }

  @Test
  void anUnclaimedItemSaysSoRatherThanLeavingTheSentenceEmpty() {
    var unclaimed = new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1A2B", "Deliver order SO-90812",
        "DELIVERABLE", NOW.plus(Duration.ofHours(3)), null, "ravi@orisenc.com", null, null, 0);

    var values = service.values(unclaimed, SlaStage.DUE_SOON, 1, NOW);

    assertThat(values.get("owner")).isEqualTo("nobody - it is unclaimed");
    assertThat(values.get("team")).isEqualTo("unassigned");
  }

  @Test
  void spansAreWrittenTheWayAPersonReadsThem() {
    assertThat(SlaEscalationService.readableDuration(Duration.ofMinutes(45))).isEqualTo("45 minutes");
    assertThat(SlaEscalationService.readableDuration(Duration.ofMinutes(1))).isEqualTo("1 minute");
    assertThat(SlaEscalationService.readableDuration(Duration.ofMinutes(90)))
        .isEqualTo("1 hour, 30 minutes");
    assertThat(SlaEscalationService.readableDuration(Duration.ofHours(54)))
        .isEqualTo("2 days, 6 hours");
    // Seconds round up to the smallest unit worth printing rather than reading as "0 minutes".
    assertThat(SlaEscalationService.readableDuration(Duration.ofSeconds(20))).isEqualTo("1 minute");
  }

  private List<NotificationRequest> captureRaised() {
    var captor = ArgumentCaptor.forClass(NotificationRequest.class);
    verify(integration, org.mockito.Mockito.atLeastOnce()).raiseNotification(captor.capture());
    return captor.getAllValues();
  }

  private static SlaCandidate workItem(Instant dueAt, int level) {
    return new SlaCandidate(SlaFamily.WORK_ITEM, "WRK-1A2B", "Deliver order SO-90812", "DELIVERABLE",
        dueAt, "priya@orisenc.com", "ravi@orisenc.com", "Operations", "chain-owner@orisenc.com", level);
  }
}
