package com.orisenc.workflow.notification;

import static com.orisenc.workflow.notification.ActivityNotificationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The scheduling rules, the guard conditions, and the fire pass — all of which have consequences
 * that only show up at runtime unless they are pinned here.
 *
 * <p>The thirty-minute window is a constant, not a configuration value; these tests assert the exact
 * value so a future change cannot slip through unnoticed.
 */
class ActivityNotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
  private static final Instant DUE_FUTURE = NOW.plus(2, ChronoUnit.HOURS);

  private ActivityNotificationRepository repository;
  private ActivityNotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(ActivityNotificationRepository.class);
    when(repository.findByActivityId(any())).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    service = new ActivityNotificationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ------------------------------------------------------------ schedule()

  @Test
  void scheduleCreatesAnEntityWithNotifyAtThirtyMinutesBeforeDue() {
    service.schedule(request(42L, "asha@orisenc.com", DUE_FUTURE));

    var captor = ArgumentCaptor.forClass(ActivityNotificationEntity.class);
    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getActivityId()).isEqualTo(42L);
    assertThat(saved.getUserId()).isEqualTo("asha@orisenc.com");
    assertThat(saved.getDueAt()).isEqualTo(DUE_FUTURE);
    assertThat(saved.getNotifyAt()).isEqualTo(DUE_FUTURE.minus(30, ChronoUnit.MINUTES));
    assertThat(saved.getCreatedAt()).isEqualTo(NOW);
  }

  @Test
  void scheduleSkipsWhenTheNotifyWindowHasAlreadyPassed() {
    // An activity whose 30-minute window is in the past would fire immediately on the next scheduler
    // tick, which is not a reminder but a surprise. Skip it rather than send noise.
    Instant dueInTenMinutes = NOW.plus(10, ChronoUnit.MINUTES);

    service.schedule(request(99L, "asha@orisenc.com", dueInTenMinutes));

    verify(repository, never()).save(any());
  }

  @Test
  void scheduleReplacesAnExistingEntryForTheSameActivity() {
    // Rescheduling an activity must not leave a stale notification behind at the old time.
    var existing = entity(7L, "asha@orisenc.com", DUE_FUTURE, null, null);
    when(repository.findByActivityId(7L)).thenReturn(Optional.of(existing));

    service.schedule(request(7L, "asha@orisenc.com", DUE_FUTURE.plus(1, ChronoUnit.HOURS)));

    verify(repository).delete(existing);
    verify(repository).save(any());
  }

  @Test
  void scheduleIsANoOpForBlankOrNullUserId() {
    service.schedule(new ScheduleRequest(null, 1L, 1L, "Call", null, DUE_FUTURE));
    service.schedule(new ScheduleRequest("  ", 1L, 1L, "Call", null, DUE_FUTURE));
    verify(repository, never()).save(any());
  }

  @Test
  void scheduleIsANoOpWhenActivityIdOrDueAtIsMissing() {
    service.schedule(new ScheduleRequest("asha@orisenc.com", 1L, null, "Call", null, DUE_FUTURE));
    service.schedule(new ScheduleRequest("asha@orisenc.com", 1L, 1L, "Call", null, null));
    verify(repository, never()).save(any());
  }

  // ------------------------------------------------------------ cancel()

  @Test
  void cancelDeletesTheEntryForTheGivenActivity() {
    var existing = entity(55L, "asha@orisenc.com", DUE_FUTURE, null, null);
    when(repository.findByActivityId(55L)).thenReturn(Optional.of(existing));

    service.cancel(55L);

    verify(repository).delete(existing);
  }

  @Test
  void cancelIsANoOpWhenNoEntryExistsAndWhenActivityIdIsNull() {
    service.cancel(99L);
    service.cancel(null);
    verify(repository, never()).delete(any());
  }

  // ------------------------------------------------------------ activeFor()

  @Test
  void activeForReturnsMappedResponsesForTheGivenUser() {
    var n = entity(1L, "asha@orisenc.com", DUE_FUTURE, NOW.minusSeconds(30), null);
    n.setTitle("Follow-up call");
    when(repository.findActiveForUser("asha@orisenc.com")).thenReturn(List.of(n));

    var results = service.activeFor("asha@orisenc.com");

    assertThat(results).singleElement().satisfies(r -> {
      assertThat(r.title()).isEqualTo("Follow-up call");
      assertThat(r.dueAt()).isEqualTo(DUE_FUTURE);
      assertThat(r.notifiedAt()).isEqualTo(NOW.minusSeconds(30));
    });
  }

  @Test
  void activeForReturnsEmptyWhenUserHasNoNotifications() {
    when(repository.findActiveForUser("quiet@orisenc.com")).thenReturn(List.of());
    assertThat(service.activeFor("quiet@orisenc.com")).isEmpty();
  }

  // ------------------------------------------------------------ dismiss()

  @Test
  void dismissStampsDismissedAtForTheOwnerOfTheNotification() {
    var n = entity(10L, "asha@orisenc.com", DUE_FUTURE, NOW.minusSeconds(10), null);
    when(repository.findById(10L)).thenReturn(Optional.of(n));

    service.dismiss(10L, "asha@orisenc.com");

    assertThat(n.getDismissedAt()).isEqualTo(NOW);
  }

  @Test
  void dismissDoesNothingWhenTheCallerDoesNotOwnTheNotification() {
    // A user must not be able to dismiss another user's notification by guessing its id.
    var n = entity(10L, "asha@orisenc.com", DUE_FUTURE, NOW.minusSeconds(10), null);
    when(repository.findById(10L)).thenReturn(Optional.of(n));

    service.dismiss(10L, "intruder@orisenc.com");

    assertThat(n.getDismissedAt()).isNull();
  }

  @Test
  void dismissIsANoOpWhenNoNotificationExistsForTheId() {
    when(repository.findById(99L)).thenReturn(Optional.empty());
    service.dismiss(99L, "asha@orisenc.com");
    // No exception, no mutation — nothing to verify beyond the lack of a failure.
  }

  // ------------------------------------------------------------ fireNotifications()

  @Test
  void fireNotificationsStampsNotifiedAtOnEveryDueRow() {
    var a = entity(1L, "asha@orisenc.com", DUE_FUTURE, null, null);
    var b = entity(2L, "ravi@orisenc.com", DUE_FUTURE, null, null);
    when(repository.findDue(NOW)).thenReturn(List.of(a, b));

    service.fireNotifications();

    assertThat(a.getNotifiedAt()).isEqualTo(NOW);
    assertThat(b.getNotifiedAt()).isEqualTo(NOW);
  }

  @Test
  void fireNotificationsDoesNothingWhenNothingIsDue() {
    when(repository.findDue(NOW)).thenReturn(List.of());
    service.fireNotifications();
    // No interaction beyond the query itself.
    verify(repository).findDue(NOW);
    verifyNoMoreInteractions(repository);
  }

  // ---------------------------------------------------------------- helpers

  private static ScheduleRequest request(Long activityId, String userId, Instant dueAt) {
    return new ScheduleRequest(userId, 1000L, activityId, "CALL: Intro call", null, dueAt);
  }

  private static ActivityNotificationEntity entity(Long activityId, String userId,
      Instant dueAt, Instant notifiedAt, Instant dismissedAt) {
    var e = new ActivityNotificationEntity();
    e.setActivityId(activityId);
    e.setUserId(userId);
    e.setOrganizationId(1000L);
    e.setTitle("CALL: Intro call");
    e.setDueAt(dueAt);
    e.setNotifyAt(dueAt.minus(30, ChronoUnit.MINUTES));
    e.setNotifiedAt(notifiedAt);
    e.setDismissedAt(dismissedAt);
    e.setCreatedAt(NOW);
    return e;
  }
}
