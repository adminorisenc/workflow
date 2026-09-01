package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only discussion on a work item (task_comments).
 *
 * <p>Separate from the status history because the two answer different questions: history explains
 * why the task moved, comments carry the working conversation. Folding conversation into history -
 * which is what the approval task family does today - means either the history stops being a clean
 * audit record or the conversation has nowhere to go.
 *
 * <p>No edit or delete operation is exposed. TM-018 requires that normal users cannot alter what was
 * said, and an edited-comment model is only worth its complexity once mentions and notifications
 * exist to make editing consequential.
 */
@Entity
@Table(name = "project_task_comment", indexes = {
    @Index(name = "idx_project_task_comment_task", columnList = "task_id,created_at")
})
public class ProjectTaskCommentEntity {

  /** Matches the column width so that over-long input is rejected as a validation error, not a 500. */
  public static final int MAX_BODY = 2000;

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false) private ProjectTaskEntity task;

  @Column(name = "author_user_id", nullable = false, length = 120) private String author;
  @Column(nullable = false, length = MAX_BODY) private String body;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected ProjectTaskCommentEntity() {}

  ProjectTaskCommentEntity(ProjectTaskEntity task, String author, String body, Instant createdAt) {
    this.task = task;
    this.author = author;
    this.body = body;
    this.createdAt = createdAt;
  }

  public Long getId() { return id; }
  public String getAuthor() { return author; }
  public String getBody() { return body; }
  public Instant getCreatedAt() { return createdAt; }
}
