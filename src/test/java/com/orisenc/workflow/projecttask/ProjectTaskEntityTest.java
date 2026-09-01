package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** DB-free coverage of the work item lifecycle, which is where the audit rules actually live. */
class ProjectTaskEntityTest {

  private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");

  private ProjectTaskEntity task() {
    return new ProjectTaskEntity("WRK-TEST01", "Deliver order SO-1", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, ProjectTaskVisibility.RELEVANT_TEAM,
        "Operations", "owner@orisenc.com", "creator@orisenc.com", LinkedEntityType.SALES_ORDER,
        "SO-1", "SO-1", null, NOW, NOW.plus(2, ChronoUnit.DAYS));
  }

  @Test
  void workItemTablesAreOwnedByTheWorkflowService() {
    assertThat(ProjectTaskEntity.class.getAnnotation(Table.class).name()).isEqualTo("project_task");
    assertThat(ProjectTaskHistoryEntity.class.getAnnotation(Table.class).name())
        .isEqualTo("project_task_history");
    assertThat(ProjectTaskCommentEntity.class.getAnnotation(Table.class).name())
        .isEqualTo("project_task_comment");
    assertThat(ProjectTaskChecklistItemEntity.class.getAnnotation(Table.class).name())
        .isEqualTo("project_task_checklist_item");
  }

  @Test
  void aNewWorkItemStartsAsADraftWithNoHistory() {
    var task = task();
    assertThat(task.getStatus()).isEqualTo(ProjectTaskStatus.DRAFT);
    assertThat(task.getHistory()).isEmpty();
    assertThat(task.getCompletedAt()).isNull();
    assertThat(task.getStartedAt()).isNull();
  }

  @Test
  void everyStatusChangeRecordsBothStatusesAndTheReason() {
    var task = task();
    task.transition(ProjectTaskStatus.NOT_STARTED, "owner@orisenc.com", "Ready to pick up", NOW, "corr-1");

    assertThat(task.getHistory()).hasSize(1);
    var event = task.getHistory().get(0);
    assertThat(event.getFromStatus()).isEqualTo(ProjectTaskStatus.DRAFT);
    assertThat(event.getToStatus()).isEqualTo(ProjectTaskStatus.NOT_STARTED);
    assertThat(event.getReason()).isEqualTo("Ready to pick up");
    assertThat(event.getActor()).isEqualTo("owner@orisenc.com");
    assertThat(event.getCorrelationId()).isEqualTo("corr-1");
  }

  @Test
  void aStatusChangeWithoutACommentIsRefusedAndChangesNothing() {
    var task = task();
    assertThatThrownBy(() -> task.transition(ProjectTaskStatus.NOT_STARTED, "owner@orisenc.com", "  ", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("comment is required");

    assertThat(task.getStatus()).isEqualTo(ProjectTaskStatus.DRAFT);
    assertThat(task.getHistory()).isEmpty();
  }

  @Test
  void transitionsOutsideTheAllowedSetAreRefused() {
    var task = task();
    // Draft goes to Not Started or Cancelled - never straight to Completed.
    assertThatThrownBy(() -> task.transition(ProjectTaskStatus.COMPLETED, "owner@orisenc.com", "done", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot move to");
    assertThat(task.getStatus()).isEqualTo(ProjectTaskStatus.DRAFT);
  }

  @Test
  void movingToTheStatusItIsAlreadyInIsRefused() {
    var task = task();
    assertThatThrownBy(() -> task.transition(ProjectTaskStatus.DRAFT, "owner@orisenc.com", "again", NOW, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already draft");
  }

  @Test
  void startingStampsStartedAtAndCompletingStampsCompletedAt() {
    var task = task();
    Instant started = NOW.plus(1, ChronoUnit.HOURS);
    Instant finished = NOW.plus(5, ChronoUnit.HOURS);

    task.transition(ProjectTaskStatus.NOT_STARTED, "o", "ready", NOW, "c");
    task.transition(ProjectTaskStatus.IN_PROGRESS, "o", "starting", started, "c");
    task.transition(ProjectTaskStatus.COMPLETED, "o", "delivered", finished, "c");

    assertThat(task.getStartedAt()).isEqualTo(started);
    assertThat(task.getCompletedAt()).isEqualTo(finished);
    assertThat(task.getStatus().closed()).isTrue();
  }

  @Test
  void reopeningACompletedItemClearsItsCompletionTimeButKeepsTheHistory() {
    var task = task();
    task.transition(ProjectTaskStatus.NOT_STARTED, "o", "ready", NOW, "c");
    task.transition(ProjectTaskStatus.IN_PROGRESS, "o", "starting", NOW, "c");
    task.transition(ProjectTaskStatus.COMPLETED, "o", "delivered", NOW, "c");
    task.transition(ProjectTaskStatus.IN_PROGRESS, "o", "POD was rejected by the customer", NOW, "c");

    assertThat(task.getStatus()).isEqualTo(ProjectTaskStatus.IN_PROGRESS);
    assertThat(task.getCompletedAt()).isNull();
    assertThat(task.getHistory()).hasSize(4);
    assertThat(task.getHistory().get(3).getReason()).isEqualTo("POD was rejected by the customer");
  }

  @Test
  void restoringACancelledItemClearsItsCancellationTime() {
    var task = task();
    task.transition(ProjectTaskStatus.CANCELLED, "o", "customer withdrew", NOW, "c");
    assertThat(task.getCancelledAt()).isEqualTo(NOW);

    task.transition(ProjectTaskStatus.NOT_STARTED, "o", "customer reinstated the order", NOW, "c");
    assertThat(task.getCancelledAt()).isNull();
  }

  @Test
  void requiredChecklistItemsGateCompletionWhileOptionalOnesDoNot() {
    var task = task();
    task.addChecklistItem("POD attached", true, NOW);
    task.addChecklistItem("Customer thanked", false, NOW);
    assertThat(task.requiredChecklistComplete()).isFalse();

    task.getChecklist().stream().filter(ProjectTaskChecklistItemEntity::isRequired)
        .forEach(item -> item.complete("o", NOW));
    assertThat(task.requiredChecklistComplete()).isTrue();
  }

  @Test
  void tickingAChecklistItemTwiceKeepsTheFirstActorAndTime() {
    var task = task();
    task.addChecklistItem("POD attached", true, NOW);
    var item = task.getChecklist().get(0);

    item.complete("first@orisenc.com", NOW);
    item.complete("second@orisenc.com", NOW.plus(1, ChronoUnit.HOURS));

    assertThat(item.getCompletedBy()).isEqualTo("first@orisenc.com");
    assertThat(item.getCompletedAt()).isEqualTo(NOW);
  }

  @Test
  void aClosedOrArchivedItemIsNeverOverdue() {
    var task = task();
    Instant past = task.getDueAt().plus(1, ChronoUnit.DAYS);
    assertThat(task.overdue(past)).isTrue();

    task.transition(ProjectTaskStatus.CANCELLED, "o", "no longer needed", NOW, "c");
    assertThat(task.overdue(past)).isFalse();
  }

  @Test
  void everyStatusHasSomewhereToGo() {
    // A dead-end state would strand work with no legal move and no way out except the database.
    for (ProjectTaskStatus status : ProjectTaskStatus.values()) {
      assertThat(status.allowedNext()).as("allowed transitions from %s", status).isNotEmpty();
    }
  }
}
