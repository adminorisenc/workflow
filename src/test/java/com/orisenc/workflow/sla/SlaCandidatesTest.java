package com.orisenc.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.orisenc.workflow.projecttask.LinkedEntityType;
import com.orisenc.workflow.projecttask.ProjectTaskEntity;
import com.orisenc.workflow.projecttask.ProjectTaskStatus;
import com.orisenc.workflow.projecttask.ProjectTaskType;
import com.orisenc.workflow.projecttask.ProjectTaskVisibility;
import com.orisenc.workflow.task.TaskEntity;
import com.orisenc.workflow.task.TaskPriority;
import com.orisenc.workflow.task.TaskType;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The two scan queries, against a real database engine rather than a mock.
 *
 * <p>They are the part of the SLA feature the compiler cannot check. Both are hand-written JPQL, one
 * of them a self-join onto a column that is deliberately not an association, and a malformed or
 * silently-empty one would first show itself as a scheduled pass that quietly escalates nothing -
 * which looks exactly like a platform where every deadline is being met.
 */
@DataJpaTest
@Import(SlaCandidates.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SlaCandidatesTest {

  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
  private static final Instant HORIZON = NOW.plus(Duration.ofHours(24));

  @Autowired private SlaCandidates candidates;
  @Autowired private EntityManager entityManager;

  @BeforeEach
  void clean() {
    entityManager.createQuery("delete from ProjectTaskHistoryEntity").executeUpdate();
    entityManager.createQuery("delete from ProjectTaskEntity").executeUpdate();
    entityManager.createQuery("delete from TaskHistoryEntity").executeUpdate();
    entityManager.createQuery("delete from TaskEntity").executeUpdate();
    entityManager.flush();
  }

  @Test
  void aWorkItemInsideTheHorizonIsReturnedWithEverythingTheLadderNeeds() {
    persist(workItem("WRK-1", NOW.plus(Duration.ofHours(6)), "priya@orisenc.com", null));

    var found = candidates.openWorkItems(HORIZON, 500);

    assertThat(found).singleElement().satisfies(candidate -> {
      assertThat(candidate.family()).isEqualTo(SlaFamily.WORK_ITEM);
      assertThat(candidate.id()).isEqualTo("WRK-1");
      assertThat(candidate.owner()).isEqualTo("priya@orisenc.com");
      assertThat(candidate.creator()).isEqualTo("creator@orisenc.com");
      assertThat(candidate.team()).isEqualTo("Operations");
      assertThat(candidate.level()).isZero();
    });
  }

  @Test
  void aChildCarriesItsParentsOwnerAsTheEscalationContact() {
    // The self-join. A stage of a fulfilment chain escalates to whoever holds the order, and reading
    // that with a second query per candidate would turn one scan into several hundred.
    var parent = workItem("WRK-PARENT", NOW.plus(Duration.ofHours(20)), "chain@orisenc.com", null);
    persist(parent);
    persist(workItem("WRK-CHILD", NOW.plus(Duration.ofHours(6)), "priya@orisenc.com", "WRK-PARENT"));

    var found = candidates.openWorkItems(HORIZON, 500);

    assertThat(found).filteredOn(candidate -> candidate.id().equals("WRK-CHILD"))
        .singleElement()
        .satisfies(child -> assertThat(child.escalationContact()).isEqualTo("chain@orisenc.com"));
  }

  @Test
  void aParentlessItemIsStillReturnedRatherThanDroppedByTheJoin() {
    // The reason the join is a left join. An inner one would silently exclude every ad-hoc task on
    // the platform, and the scan would look like it was working.
    persist(workItem("WRK-ADHOC", NOW.plus(Duration.ofHours(6)), "priya@orisenc.com", null));

    assertThat(candidates.openWorkItems(HORIZON, 500))
        .singleElement()
        .satisfies(found -> assertThat(found.escalationContact()).isNull());
  }

  @Test
  void workDueBeyondTheHorizonIsNotLoadedAtAll() {
    persist(workItem("WRK-LATER", NOW.plus(Duration.ofDays(30)), "priya@orisenc.com", null));

    assertThat(candidates.openWorkItems(HORIZON, 500)).isEmpty();
  }

  @Test
  void closedAndArchivedWorkIsLeftAlone() {
    // Chasing somebody about work that is finished or out of the business is the noise that teaches
    // people to stop reading these messages.
    var completed = workItem("WRK-DONE", NOW.minus(Duration.ofDays(2)), "priya@orisenc.com", null);
    completed.transition(ProjectTaskStatus.NOT_STARTED, "a", "ready", NOW, "c");
    completed.transition(ProjectTaskStatus.IN_PROGRESS, "a", "started", NOW, "c");
    completed.transition(ProjectTaskStatus.COMPLETED, "a", "delivered", NOW, "c");
    persist(completed);

    var archived = workItem("WRK-ARCHIVED", NOW.minus(Duration.ofDays(2)), "priya@orisenc.com", null);
    archived.archive(NOW);
    persist(archived);

    assertThat(candidates.openWorkItems(HORIZON, 500)).isEmpty();
  }

  @Test
  void openApprovalsAreScannedWithTheAssigneeAsOwnerAndTheRequesterAsCreator() {
    persist(approval("TSK-1", NOW.minus(Duration.ofHours(2))));

    assertThat(candidates.openApprovals(HORIZON, 500)).singleElement().satisfies(found -> {
      assertThat(found.family()).isEqualTo(SlaFamily.APPROVAL);
      assertThat(found.owner()).isEqualTo("priya@orisenc.com");
      assertThat(found.creator()).isEqualTo("ravi@orisenc.com");
      assertThat(found.team()).isEqualTo("Finance");
      // No parent to escalate to; only the configured mailbox stands above an approval.
      assertThat(found.escalationContact()).isNull();
    });
  }

  @Test
  void aDecidedApprovalIsNoLongerScanned() {
    var decided = approval("TSK-DONE", NOW.minus(Duration.ofHours(2)));
    decided.transition(com.orisenc.workflow.task.TaskStatus.COMPLETED, NOW);
    persist(decided);

    assertThat(candidates.openApprovals(HORIZON, 500)).isEmpty();
  }

  @Test
  void theBatchSizeIsAHardCeilingOnOnePass() {
    for (int index = 0; index < 5; index++)
      persist(workItem("WRK-" + index, NOW.plus(Duration.ofHours(index + 1)), "priya@orisenc.com", null));

    assertThat(candidates.openWorkItems(HORIZON, 3)).hasSize(3);
  }

  @Test
  void theSoonestDeadlineIsScannedFirst() {
    // So a truncated pass chases the most urgent work rather than an arbitrary three rows.
    persist(workItem("WRK-LATE", NOW.plus(Duration.ofHours(20)), "priya@orisenc.com", null));
    persist(workItem("WRK-SOON", NOW.minus(Duration.ofHours(3)), "priya@orisenc.com", null));

    assertThat(candidates.openWorkItems(HORIZON, 500)).extracting(SlaCandidate::id)
        .containsExactly("WRK-SOON", "WRK-LATE");
  }

  @Test
  void recordingARungWritesBothTheLevelAndItsHistoryRow() {
    persist(workItem("WRK-1", NOW.minus(Duration.ofHours(2)), "priya@orisenc.com", null));

    boolean recorded = candidates.recordEscalation(SlaFamily.WORK_ITEM, "WRK-1", 2,
        "Past its due date; told priya@orisenc.com.", NOW, "sla-wrk-1-L2");
    entityManager.clear();

    assertThat(recorded).isTrue();
    var reloaded = entityManager.find(ProjectTaskEntity.class, "WRK-1");
    assertThat(reloaded.getEscalationLevel()).isEqualTo(2);
    assertThat(reloaded.getHistory()).extracting(history -> history.getAction())
        .contains("SLA_ESCALATED");
  }

  @Test
  void recordingARungAlreadyReachedChangesNothingAndSaysSo() {
    // Two passes overlapping. The entity refuses; this reports the refusal rather than raising it,
    // because a slow run catching up is ordinary rather than exceptional.
    persist(workItem("WRK-1", NOW.minus(Duration.ofHours(2)), "priya@orisenc.com", null));
    candidates.recordEscalation(SlaFamily.WORK_ITEM, "WRK-1", 2, "breached", NOW, "c");

    boolean second = candidates.recordEscalation(SlaFamily.WORK_ITEM, "WRK-1", 2, "breached", NOW, "c");

    assertThat(second).isFalse();
  }

  @Test
  void recordingARungOnAnApprovalUsesTheApprovalsOwnHistory() {
    persist(approval("TSK-1", NOW.minus(Duration.ofHours(2))));

    boolean recorded = candidates.recordEscalation(SlaFamily.APPROVAL, "TSK-1", 2, "breached", NOW, "c");
    entityManager.clear();

    assertThat(recorded).isTrue();
    var reloaded = entityManager.find(TaskEntity.class, "TSK-1");
    assertThat(reloaded.getEscalationLevel()).isEqualTo(2);
    assertThat(reloaded.getHistory()).extracting(history -> history.getAction())
        .contains("SLA_ESCALATED");
  }

  @Test
  void anItemThatVanishedBetweenTheScanAndTheStampIsNotAnError() {
    assertThat(candidates.recordEscalation(SlaFamily.WORK_ITEM, "WRK-GONE", 2, "breached", NOW, "c"))
        .isFalse();
  }

  private void persist(Object entity) {
    entityManager.persist(entity);
    entityManager.flush();
  }

  private static ProjectTaskEntity workItem(String id, Instant dueAt, String owner, String parentId) {
    var task = new ProjectTaskEntity(id, "Deliver order SO-90812", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, ProjectTaskVisibility.RELEVANT_TEAM,
        "Operations", owner, "creator@orisenc.com", LinkedEntityType.SALES_ORDER, null, "SO-90812",
        parentId, NOW.minus(Duration.ofDays(1)), dueAt);
    task.addHistory("CREATED", "creator@orisenc.com", null, NOW.minus(Duration.ofDays(1)), "c");
    return task;
  }

  private static TaskEntity approval(String id, Instant dueAt) {
    var task = new TaskEntity(id, "Approve credit terms", "SO-90812", "Finance", TaskType.APPROVAL,
        TaskPriority.HIGH, "ravi@orisenc.com", "priya@orisenc.com", "Terms need a decision.", null,
        NOW.minus(Duration.ofDays(1)), dueAt);
    task.addHistory("CREATED", "ravi@orisenc.com", null, NOW.minus(Duration.ofDays(1)), "c");
    return task;
  }
}
