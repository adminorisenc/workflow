package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;

@Entity
@Table(name = "project_task_comment_mention", uniqueConstraints =
    @UniqueConstraint(name = "uq_project_task_comment_mention_user", columnNames = {"comment_id", "username"}))
public class ProjectTaskCommentMentionEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "comment_id", nullable = false)
  private ProjectTaskCommentEntity comment;
  @Column(nullable = false, length = 120) private String username;
  protected ProjectTaskCommentMentionEntity() {}
  ProjectTaskCommentMentionEntity(ProjectTaskCommentEntity comment, String username) {
    this.comment = comment; this.username = username.trim().toLowerCase(java.util.Locale.ROOT);
  }
  public String getUsername() { return username; }
}
