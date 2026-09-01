package com.orisenc.workflow.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "workflow_task", indexes = {
    @Index(name = "idx_workflow_task_assignee_status", columnList = "assignee,status"),
    @Index(name = "idx_workflow_task_department_due", columnList = "department,due_at")
})
public class TaskEntity {
  @Id @Column(length = 40) private String id;
  @Version private long version;
  @Column(nullable = false, length = 160) private String title;
  @Column(nullable = false, length = 120) private String reference;
  @Column(nullable = false, length = 60) private String department;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskType type;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskPriority priority;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskStatus status;
  @Column(nullable = false, length = 120) private String requester;
  @Column(length = 120) private String assignee;
  @Column(nullable = false, length = 2000) private String summary;
  @Column(length = 100) private String requestValue;
  @Column(nullable = false) private Instant createdAt;
  @Column(name = "due_at", nullable = false) private Instant dueAt;
  private Instant completedAt;
  @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("occurredAt ASC") private List<TaskHistoryEntity> history = new ArrayList<>();

  protected TaskEntity() {}
  public TaskEntity(String id, String title, String reference, String department, TaskType type,
      TaskPriority priority, String requester, String assignee, String summary, String requestValue,
      Instant createdAt, Instant dueAt) {
    this.id=id; this.title=title; this.reference=reference; this.department=department; this.type=type;
    this.priority=priority; this.requester=requester; this.assignee=assignee; this.summary=summary;
    this.requestValue=requestValue; this.createdAt=createdAt; this.dueAt=dueAt; this.status=TaskStatus.PENDING;
  }
  public void addHistory(String action, String actor, String comment, Instant time, String correlationId) {
    history.add(new TaskHistoryEntity(this, action, actor, comment, time, correlationId));
  }
  public void assignTo(String actor) { assignee = actor; }
  public void transition(TaskStatus newStatus, Instant time) { status = newStatus; if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.REJECTED) completedAt = time; }
  public String getId(){return id;} public long getVersion(){return version;} public String getTitle(){return title;}
  public String getReference(){return reference;} public String getDepartment(){return department;} public TaskType getType(){return type;}
  public TaskPriority getPriority(){return priority;} public TaskStatus getStatus(){return status;} public String getRequester(){return requester;}
  public String getAssignee(){return assignee;} public String getSummary(){return summary;} public String getRequestValue(){return requestValue;}
  public Instant getCreatedAt(){return createdAt;} public Instant getDueAt(){return dueAt;} public Instant getCompletedAt(){return completedAt;}
  public List<TaskHistoryEntity> getHistory(){return List.copyOf(history);}
}
