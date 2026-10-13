package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "project_task_follower", uniqueConstraints =
    @UniqueConstraint(name = "uq_project_task_follower_task_user", columnNames = {"task_id", "username"}),
    indexes = @Index(name = "idx_project_task_follower_user", columnList = "username"))
public class ProjectTaskFollowerEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false)
  private ProjectTaskEntity task;
  @Column(nullable = false, length = 120) private String username;
  @Column(name = "added_by", nullable = false, length = 120) private String addedBy;
  @Column(name = "added_at", nullable = false) private Instant addedAt;
  protected ProjectTaskFollowerEntity() {}
  ProjectTaskFollowerEntity(ProjectTaskEntity task, String username, String addedBy, Instant addedAt) {
    this.task = task; this.username = username.trim().toLowerCase(java.util.Locale.ROOT);
    this.addedBy = addedBy; this.addedAt = addedAt;
  }
  public Long getId() { return id; }
  public String getUsername() { return username; }
  public String getAddedBy() { return addedBy; }
  public Instant getAddedAt() { return addedAt; }
}
