package com.orisenc.workflow.notification;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "activity_notification", indexes = {
    @Index(name = "idx_act_notif_fire", columnList = "notify_at, notified_at"),
    @Index(name = "idx_act_notif_user", columnList = "user_id, dismissed_at")
})
public class ActivityNotificationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "activity_notification_seq")
  @SequenceGenerator(name = "activity_notification_seq", sequenceName = "activity_notification_seq",
      allocationSize = 1)
  private Long id;

  @Column(name = "user_id", nullable = false, length = 200)
  private String userId;

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "activity_id", nullable = false)
  private Long activityId;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Column(name = "body", length = 500)
  private String body;

  @Column(name = "due_at", nullable = false)
  private Instant dueAt;

  @Column(name = "notify_at", nullable = false)
  private Instant notifyAt;

  @Column(name = "notified_at")
  private Instant notifiedAt;

  @Column(name = "dismissed_at")
  private Instant dismissedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Long getId() { return id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public Long getOrganizationId() { return organizationId; }
  public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
  public Long getActivityId() { return activityId; }
  public void setActivityId(Long activityId) { this.activityId = activityId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }
  public Instant getDueAt() { return dueAt; }
  public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
  public Instant getNotifyAt() { return notifyAt; }
  public void setNotifyAt(Instant notifyAt) { this.notifyAt = notifyAt; }
  public Instant getNotifiedAt() { return notifiedAt; }
  public void setNotifiedAt(Instant notifiedAt) { this.notifiedAt = notifiedAt; }
  public Instant getDismissedAt() { return dismissedAt; }
  public void setDismissedAt(Instant dismissedAt) { this.dismissedAt = dismissedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
