package com.orisenc.workflow.notification;

import static com.orisenc.workflow.notification.ActivityNotificationDtos.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional
public class ActivityNotificationService {

  static final long NOTIFY_BEFORE_MINUTES = 30;

  private final ActivityNotificationRepository repository;
  private final Clock clock;

  @Autowired
  public ActivityNotificationService(ActivityNotificationRepository repository) {
    this(repository, Clock.systemUTC());
  }

  ActivityNotificationService(ActivityNotificationRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public void schedule(ScheduleRequest request) {
    if (request.userId() == null || request.userId().isBlank()) return;
    if (request.activityId() == null || request.dueAt() == null) return;

    repository.findByActivityId(request.activityId()).ifPresent(repository::delete);

    Instant notifyAt = request.dueAt().minus(NOTIFY_BEFORE_MINUTES, ChronoUnit.MINUTES);
    // Nothing useful to do if the window has already passed
    if (!notifyAt.isAfter(clock.instant())) return;

    var entity = new ActivityNotificationEntity();
    entity.setUserId(request.userId());
    entity.setOrganizationId(request.organizationId());
    entity.setActivityId(request.activityId());
    entity.setTitle(request.title());
    entity.setBody(request.body());
    entity.setDueAt(request.dueAt());
    entity.setNotifyAt(notifyAt);
    entity.setCreatedAt(clock.instant());
    repository.save(entity);
  }

  public void cancel(Long activityId) {
    if (activityId == null) return;
    repository.findByActivityId(activityId).ifPresent(repository::delete);
  }

  @Transactional(readOnly = true)
  public List<NotificationResponse> activeFor(String userId) {
    return repository.findActiveForUser(userId).stream()
        .map(n -> new NotificationResponse(n.getId(), n.getTitle(), n.getBody(),
            n.getDueAt(), n.getNotifiedAt()))
        .toList();
  }

  public void dismiss(Long id, String userId) {
    repository.findById(id).ifPresent(n -> {
      if (!userId.equals(n.getUserId())) return;
      n.setDismissedAt(clock.instant());
    });
  }

  /** Stamps notifiedAt on every notification whose window has arrived. */
  @Scheduled(fixedDelay = 60_000)
  public void fireNotifications() {
    Instant now = clock.instant();
    List<ActivityNotificationEntity> due = repository.findDue(now);
    for (var n : due) {
      n.setNotifiedAt(now);
    }
  }
}
