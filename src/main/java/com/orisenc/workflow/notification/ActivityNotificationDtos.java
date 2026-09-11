package com.orisenc.workflow.notification;

import java.time.Instant;

class ActivityNotificationDtos {

  record ScheduleRequest(
      String userId,
      Long organizationId,
      Long activityId,
      String title,
      String body,
      Instant dueAt) {}

  record NotificationResponse(
      Long id,
      String title,
      String body,
      Instant dueAt,
      Instant notifiedAt) {}
}
