package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.*;
import com.orisenc.workflow.task.TaskPriority;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectTaskSubtaskEntityTest {
  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
  private ProjectTaskEntity task(String id) {
    var task = new ProjectTaskEntity(id, id, "Work", ProjectTaskType.INTERNAL, TaskPriority.MEDIUM,
        ProjectTaskVisibility.INDIVIDUAL, "Operations", "owner", "owner", LinkedEntityType.NONE,
        null, null, null, NOW, NOW.plusSeconds(86400));
    task.beginSubtask();
    return task;
  }
  private void complete(ProjectTaskEntity parent, List<ProjectTaskEntity> children) {
    if (parent.getStatus() == ProjectTaskStatus.NOT_STARTED) parent.transition(ProjectTaskStatus.IN_PROGRESS, "owner", "Started", NOW, "c");
    parent.transition(ProjectTaskStatus.COMPLETED, "owner", null, "Done", NOW, "c", children);
  }
  @Test void mandatoryChildrenBlockCompletionAndNameTheOpenTasks() {
    var parent = task("P"); parent.setSubtasksMandatory(true);
    assertThatThrownBy(() -> complete(parent, List.of(task("WRK-A"), task("WRK-B"))))
        .hasMessage("2 subtasks are still open: WRK-A, WRK-B");
    assertThat(parent.getStatus()).isEqualTo(ProjectTaskStatus.IN_PROGRESS);
    assertThat(parent.getHistory()).hasSize(1);
  }
  @Test void optionalChildrenDoNotBlockCompletion() {
    var parent = task("P"); complete(parent, List.of(task("WRK-A")));
    assertThat(parent.getStatus()).isEqualTo(ProjectTaskStatus.COMPLETED);
  }
  @Test void completedCancelledAndArchivedChildrenAreIgnored() {
    var parent = task("P"); parent.setSubtasksMandatory(true);
    var done = task("A"); complete(done, List.of());
    var cancelled = task("B"); cancelled.transition(ProjectTaskStatus.CANCELLED, "owner", "No longer needed", NOW, "c");
    var archived = task("C"); archived.archive(NOW);
    complete(parent, List.of(done, cancelled, archived));
    assertThat(parent.getStatus()).isEqualTo(ProjectTaskStatus.COMPLETED);
  }
  @Test void cancellationDoesNotRequireCompletedChildren() {
    var parent = task("P"); parent.setSubtasksMandatory(true);
    parent.transition(ProjectTaskStatus.CANCELLED, "owner", "Stopped", NOW, "c");
    assertThat(parent.getStatus()).isEqualTo(ProjectTaskStatus.CANCELLED);
  }
  @Test void mandatoryCompletionCannotBypassChildEvidence() {
    var parent = task("P"); parent.setSubtasksMandatory(true);
    assertThatThrownBy(() -> parent.transition(ProjectTaskStatus.COMPLETED, "owner", "Done", NOW, "c"))
        .hasMessage("Subtask completion evidence is required.");
  }
}
