package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One completion check on a work item (task_checklist_items).
 *
 * <p>The reason this exists rather than being left to free text: several stages of the order-to-cash
 * chain have conditions that must be evidenced before the stage can close - a POD captured before a
 * delivery completes, a vendor bill matched before a payment is released. A required item makes that
 * a gate the entity enforces in {@link ProjectTaskEntity#requiredChecklistComplete()}, instead of a
 * convention someone has to remember.
 */
@Entity
@Table(name = "project_task_checklist_item", indexes = {
    @Index(name = "idx_project_task_checklist_task", columnList = "task_id,sequence_no")
})
public class ProjectTaskChecklistItemEntity {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false) private ProjectTaskEntity task;

  @Column(name = "sequence_no", nullable = false) private int sequenceNo;
  @Column(nullable = false, length = 200) private String title;
  @Column(name = "is_required", nullable = false) private boolean required;
  @Enumerated(EnumType.STRING)
  // Existing PostgreSQL rows were added with the MANUAL default. Keep the JPA mapping portable so
  // Hibernate does not emit PostgreSQL-invalid SET DATA TYPE ... DEFAULT during schema update.
  @Column(name = "item_source", nullable = false)
  private ChecklistItemSource source = ChecklistItemSource.MANUAL;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "completed_by", length = 120) private String completedBy;
  @Column(name = "completed_at") private Instant completedAt;

  protected ProjectTaskChecklistItemEntity() {}

  ProjectTaskChecklistItemEntity(ProjectTaskEntity task, int sequenceNo, String title, boolean required,
      Instant createdAt) {
    this(task, sequenceNo, title, required, ChecklistItemSource.MANUAL, createdAt);
  }

  ProjectTaskChecklistItemEntity(ProjectTaskEntity task, int sequenceNo, String title, boolean required,
      ChecklistItemSource source, Instant createdAt) {
    this.task = task;
    this.sequenceNo = sequenceNo;
    rename(title);
    this.required = required;
    this.source = source == null ? ChecklistItemSource.MANUAL : source;
    this.createdAt = createdAt;
  }

  public void edit(String title, boolean required) {
    rename(title);
    this.required = required;
  }

  public void assertRemovable() {
    if (isCompleted()) throw new IllegalArgumentException("A completed checklist item cannot be removed.");
    if (source == ChecklistItemSource.SYSTEM)
      throw new IllegalArgumentException("A system evidence checklist item cannot be removed.");
  }

  private void rename(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("A checklist title is required.");
    String trimmed = value.trim();
    if (trimmed.length() > 200)
      throw new IllegalArgumentException("A checklist title cannot be longer than 200 characters.");
    this.title = trimmed;
  }

  /** Ticking is idempotent; re-ticking keeps the original actor and time. */
  public void complete(String actor, Instant time) {
    if (completedAt != null) return;
    completedBy = actor;
    completedAt = time;
  }

  public void reopen() {
    completedBy = null;
    completedAt = null;
  }

  public Long getId() { return id; }
  public int getSequenceNo() { return sequenceNo; }
  public String getTitle() { return title; }
  public boolean isRequired() { return required; }
  public ChecklistItemSource getSource() { return source; }
  public boolean isCompleted() { return completedAt != null; }
  public String getCompletedBy() { return completedBy; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
