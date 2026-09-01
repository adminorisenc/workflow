package com.orisenc.workflow.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

// DB-free unit coverage for TaskEntity's state-transition logic.
class TaskEntityTest {

  @Test
  void taskEntitiesUseWorkflowOwnedTables() {
    assertThat(TaskEntity.class.getAnnotation(Table.class).name()).isEqualTo("workflow_task");
    assertThat(TaskHistoryEntity.class.getAnnotation(Table.class).name())
        .isEqualTo("workflow_task_history");
  }

  @Test
  void newTaskStartsPendingWithNoHistory() {
    Instant now = Instant.now();
    var task = new TaskEntity("TSK-TEST01", "Title", "Ref", "Accounts", TaskType.APPROVAL, TaskPriority.HIGH,
        "requester@orisenc.com", null, "summary", null, now, now.plus(1, ChronoUnit.DAYS));

    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(task.getAssignee()).isNull();
    assertThat(task.getHistory()).isEmpty();
    assertThat(task.getCompletedAt()).isNull();
  }

  @Test
  void claimAndStartRecordHistoryWithoutClosingTheTask() {
    Instant now = Instant.now();
    var task = new TaskEntity("TSK-TEST02", "Title", "Ref", "Tech", TaskType.ACTION, TaskPriority.MEDIUM,
        "requester@orisenc.com", null, "summary", null, now, now.plus(1, ChronoUnit.DAYS));

    task.assignTo("assignee@orisenc.com");
    task.addHistory("CLAIMED", "assignee@orisenc.com", null, now, "corr-1");
    task.transition(TaskStatus.IN_PROGRESS, now);
    task.addHistory("STARTED", "assignee@orisenc.com", null, now, "corr-2");

    assertThat(task.getAssignee()).isEqualTo("assignee@orisenc.com");
    assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    assertThat(task.getCompletedAt()).isNull();
    assertThat(task.getHistory()).hasSize(2);
  }

  @Test
  void completingATaskStampsCompletedAt() {
    Instant now = Instant.now();
    var task = new TaskEntity("TSK-TEST03", "Title", "Ref", "Sales", TaskType.ACTION, TaskPriority.LOW,
        "requester@orisenc.com", "assignee@orisenc.com", "summary", null, now, now.plus(1, ChronoUnit.DAYS));

    Instant completedAt = now.plus(2, ChronoUnit.HOURS);
    task.transition(TaskStatus.COMPLETED, completedAt);

    assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(task.getCompletedAt()).isEqualTo(completedAt);
  }

  @Test
  void rejectingATaskAlsoStampsCompletedAt() {
    Instant now = Instant.now();
    var task = new TaskEntity("TSK-TEST04", "Title", "Ref", "Operations", TaskType.APPROVAL, TaskPriority.CRITICAL,
        "requester@orisenc.com", "assignee@orisenc.com", "summary", null, now, now.plus(1, ChronoUnit.DAYS));

    task.transition(TaskStatus.REJECTED, now);

    assertThat(task.getStatus()).isEqualTo(TaskStatus.REJECTED);
    assertThat(task.getCompletedAt()).isEqualTo(now);
  }
}
