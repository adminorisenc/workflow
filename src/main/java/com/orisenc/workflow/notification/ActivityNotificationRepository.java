package com.orisenc.workflow.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface ActivityNotificationRepository extends JpaRepository<ActivityNotificationEntity, Long> {

  @Query("SELECT n FROM ActivityNotificationEntity n "
      + "WHERE n.notifyAt <= :now AND n.notifiedAt IS NULL AND n.dismissedAt IS NULL")
  List<ActivityNotificationEntity> findDue(Instant now);

  @Query("SELECT n FROM ActivityNotificationEntity n "
      + "WHERE n.userId = :userId AND n.notifiedAt IS NOT NULL AND n.dismissedAt IS NULL "
      + "ORDER BY n.dueAt ASC")
  List<ActivityNotificationEntity> findActiveForUser(String userId);

  Optional<ActivityNotificationEntity> findByActivityId(Long activityId);
}
