package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A second person put on a work item, able to see and progress it without owning it.
 *
 * <p>Deliberately <em>beside</em> {@code ownerUserId} rather than replacing it. TM-003 says a task
 * has one accountable assignee, and three things in this service resolve to that one person: the SLA
 * ladder chases an owner, a delegation is cover for one person's workload, and claiming an unowned
 * item makes you its owner. Turning ownership into a set would leave all three with no answer to
 * "who", so the owner stays singular and this table adds the people working it alongside them.
 *
 * <p>The boundary this does <em>not</em> cross is the one policy note P-04 draws and TM-012/TM-014
 * make critical: being named here grants task metadata, status and comments, and never customer
 * identity or customer fields. Customer access is a separate entitlement, evaluated separately, and
 * is not inferred from being on a task - exactly as it is not inferred from a task's audience.
 *
 * <p>{@code username} rather than an Entra object id, matching {@code ownerUserId},
 * {@code createdBy} and every history actor on the item: one kind of identity comparison, so "is
 * this person the owner" and "is this person an assignee" are the same sort of question.
 */
@Entity
@Table(name = "project_task_assignee",
    indexes = @Index(name = "idx_project_task_assignee_task", columnList = "task_id"),
    uniqueConstraints = @UniqueConstraint(
        name = "uq_project_task_assignee_task_user", columnNames = {"task_id", "username"}))
public class ProjectTaskAssigneeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false)
  private ProjectTaskEntity task;

  /** Same length as {@code owner_user_id} on the item itself - they hold the same kind of value. */
  @Column(name = "username", nullable = false, length = 120) private String username;
  @Column(name = "added_by", nullable = false, length = 120) private String addedBy;
  @Column(name = "added_at", nullable = false) private Instant addedAt;

  protected ProjectTaskAssigneeEntity() {}

  public ProjectTaskAssigneeEntity(ProjectTaskEntity task, String username, String addedBy,
      Instant now) {
    this.task = task;
    this.username = required(username, "Username");
    this.addedBy = required(addedBy, "Added by");
    this.addedAt = now;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required.");
    String trimmed = value.trim();
    if (trimmed.length() > 120)
      throw new IllegalArgumentException(field + " must be 120 characters or fewer.");
    return trimmed;
  }

  public Long getId() { return id; }
  public String getTaskId() { return task.getId(); }
  public String getUsername() { return username; }
  public String getAddedBy() { return addedBy; }
  public Instant getAddedAt() { return addedAt; }
}
