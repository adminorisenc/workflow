package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.task.TaskPriority;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link ProjectTaskEntity} enforces for editing, effort and travel (TM-A01, TM-A03,
 * TM-A05, TM-A06), tested without a database.
 *
 * <p>Here rather than in a service test because these are the entity's own refusals. The service
 * only translates them into status codes, and a rule proved through three layers of Spring is a rule
 * nobody can see is being enforced by the object that owns it.
 */
class ProjectTaskEditAndEffortTest {

  private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
  private static final Instant DUE = NOW.plus(Duration.ofDays(3));
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
  private static final String OWNER = "priya@orisenc.com";
  private static final String MATE = "ravi@orisenc.com";

  private ProjectTaskEntity workItem() {
    var task = new ProjectTaskEntity("WRK-1", "Deliver order SO-90812", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, ProjectTaskVisibility.RELEVANT_TEAM,
        "Operations", OWNER, OWNER, LinkedEntityType.SALES_ORDER, null, "SO-90812", null, NOW, DUE);
    task.addHistory("CREATED", OWNER, null, NOW, "c");
    return task;
  }

  /** The whole editable state, so a test only has to name what it is changing. */
  private ProjectTaskEntity.Edit editOf(ProjectTaskEntity task) {
    return new ProjectTaskEntity.Edit(task.getTitle(), task.getSummary(), task.getDescription(),
        task.getTaskType(), task.getPriority(), task.getVisibility(), task.getRelevantTeam(),
        task.getLinkedEntityType(), task.getLinkedEntityId(), task.getLinkedEntityRef(),
        task.getParentTaskId(), task.getDueAt(), task.getPlannedStartAt(), task.getPlannedEndAt(),
        task.getStartedAt(), task.getCompletedAt());
  }

  /* ------------------------------------------------------------------------------ editing */

  @Test
  void anEditReportsEveryFieldItChangedAndNothingItDidNot() {
    var task = workItem();
    var before = editOf(task);

    var changes = task.applyEdit(new ProjectTaskEntity.Edit("Deliver order SO-90812",
        "Van booked, POD app installed.", before.description(), before.taskType(),
        TaskPriority.CRITICAL, before.visibility(), before.relevantTeam(), before.linkedEntityType(),
        before.linkedEntityId(), before.linkedEntityRef(), before.parentTaskId(), before.dueAt(),
        NOW, DUE, null, null));

    // Three changed, and the title - identical text - is not among them. A history row for a field
    // nobody touched is the noise that makes an activity feed unreadable.
    assertThat(changes).extracting(ProjectTaskEntity.FieldChange::field)
        .containsExactlyInAnyOrder("summary", "priority", "planned start", "planned end");
    assertThat(task.getSummary()).isEqualTo("Van booked, POD app installed.");
    assertThat(task.getPriority()).isEqualTo(TaskPriority.CRITICAL);
  }

  @Test
  void savingAnUnchangedFormLeavesNoMarkOnTheTrail() {
    var task = workItem();
    assertThat(task.applyEdit(editOf(task))).isEmpty();
  }

  @Test
  void aChangeCarriesBothTheOldValueAndTheNewOne() {
    var task = workItem();
    var before = editOf(task);

    var changes = task.applyEdit(new ProjectTaskEntity.Edit("Deliver order SO-90812 urgently",
        before.summary(), before.description(), before.taskType(), before.priority(),
        before.visibility(), before.relevantTeam(), before.linkedEntityType(),
        before.linkedEntityId(), before.linkedEntityRef(), before.parentTaskId(), before.dueAt(),
        null, null, null, null));

    assertThat(changes).singleElement().satisfies(change -> {
      assertThat(change.from()).isEqualTo("Deliver order SO-90812");
      assertThat(change.to()).isEqualTo("Deliver order SO-90812 urgently");
    });
  }

  @Test
  void clearingAnOptionalFieldIsAChangeInItsOwnRight() {
    var task = workItem();
    task.describe("A summary", null, null);
    var before = editOf(task);

    var changes = task.applyEdit(new ProjectTaskEntity.Edit(before.title(), null,
        before.description(), before.taskType(), before.priority(), before.visibility(),
        before.relevantTeam(), before.linkedEntityType(), before.linkedEntityId(),
        before.linkedEntityRef(), before.parentTaskId(), before.dueAt(), null, null, null, null));

    // The reason the request carries whole state rather than a patch: an absent summary means
    // "remove it", and it could not if absent also meant "leave it".
    assertThat(changes).singleElement().satisfies(change -> {
      assertThat(change.field()).isEqualTo("summary");
      assertThat(change.to()).isNull();
    });
    assertThat(task.getSummary()).isNull();
  }

  @Test
  void aPlannedEndBeforeItsStartIsRefused() {
    var task = workItem();
    var before = editOf(task);
    assertThatThrownBy(() -> task.applyEdit(new ProjectTaskEntity.Edit(before.title(),
        before.summary(), before.description(), before.taskType(), before.priority(),
        before.visibility(), before.relevantTeam(), before.linkedEntityType(),
        before.linkedEntityId(), before.linkedEntityRef(), before.parentTaskId(), before.dueAt(),
        DUE, NOW, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planned end cannot be before");
  }

  @Test
  void aTaskCannotHaveFinishedBeforeItStarted() {
    var task = workItem();
    var before = editOf(task);
    assertThatThrownBy(() -> task.applyEdit(new ProjectTaskEntity.Edit(before.title(),
        before.summary(), before.description(), before.taskType(), before.priority(),
        before.visibility(), before.relevantTeam(), before.linkedEntityType(),
        before.linkedEntityId(), before.linkedEntityRef(), before.parentTaskId(), before.dueAt(),
        null, null, DUE, NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finished before it started");
  }

  @Test
  void aTitleCannotBeEditedAway() {
    var task = workItem();
    var before = editOf(task);
    assertThatThrownBy(() -> task.applyEdit(new ProjectTaskEntity.Edit("   ", before.summary(),
        before.description(), before.taskType(), before.priority(), before.visibility(),
        before.relevantTeam(), before.linkedEntityType(), before.linkedEntityId(),
        before.linkedEntityRef(), before.parentTaskId(), before.dueAt(), null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("A title is required");
  }

  @Test
  void aWorkItemCannotBeMadeItsOwnParent() {
    var task = workItem();
    var before = editOf(task);
    assertThatThrownBy(() -> task.applyEdit(new ProjectTaskEntity.Edit(before.title(),
        before.summary(), before.description(), before.taskType(), before.priority(),
        before.visibility(), before.relevantTeam(), before.linkedEntityType(),
        before.linkedEntityId(), before.linkedEntityRef(), "WRK-1", before.dueAt(), null, null,
        null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be its own parent");
  }

  @Test
  void aLongDescriptionIsTruncatedOnTheHistoryRowButNotOnTheTask() {
    var task = workItem();
    var before = editOf(task);
    String essay = "x".repeat(1000);

    var changes = task.applyEdit(new ProjectTaskEntity.Edit(before.title(), before.summary(), essay,
        before.taskType(), before.priority(), before.visibility(), before.relevantTeam(),
        before.linkedEntityType(), before.linkedEntityId(), before.linkedEntityRef(),
        before.parentTaskId(), before.dueAt(), null, null, null, null));

    assertThat(task.getDescription()).hasSize(1000);
    assertThat(changes).singleElement()
        .satisfies(change -> assertThat(change.to()).hasSize(200).endsWith("..."));
  }

  /* -------------------------------------------------------------------------- open, closed */

  @Test
  void aCompletedItemIsClosedToEditsAndToTimeUntilItIsReopened() {
    var task = workItem();
    task.transition(ProjectTaskStatus.NOT_STARTED, OWNER, "ready", NOW, "c");
    task.transition(ProjectTaskStatus.IN_PROGRESS, OWNER, "starting", NOW, "c");
    task.transition(ProjectTaskStatus.COMPLETED, OWNER, "delivered", NOW, "c");

    assertThat(task.open()).isFalse();
    assertThatThrownBy(() -> task.logTime(OWNER, TODAY, 60, null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reopen it");

    // The deliberate way back in: an ordinary transition, which demands its own reason.
    task.transition(ProjectTaskStatus.IN_PROGRESS, OWNER, "customer disputed the POD", NOW, "c");
    assertThat(task.open()).isTrue();
    assertThat(task.logTime(OWNER, TODAY, 60, null, OWNER, NOW)).isNotNull();
  }

  @Test
  void anArchivedItemTakesNoMoreTime() {
    var task = workItem();
    task.archive(NOW);
    assertThatThrownBy(() -> task.logTime(OWNER, TODAY, 30, null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archived");
  }

  /* ------------------------------------------------------------------------------- effort */

  @Test
  void workAndTravelAreTotalledSeparatelyAndNeverAddedTogether() {
    var task = workItem();
    task.logTime(OWNER, TODAY, 120, "Loaded and delivered.", OWNER, NOW);
    task.logTime(MATE, TODAY, 45, "Captured the POD.", MATE, NOW);
    task.logTravel(OWNER, TODAY, "Depot", "Northstar Medical", "Delivery", 300,
        new BigDecimal("450.50"), "INR", "BILL-22", OWNER, NOW);

    // The fact worth keeping visible: a two-and-three-quarter-hour job that carried five hours of
    // travel. One combined figure would hide exactly that.
    assertThat(task.totalWorkMinutes()).isEqualTo(165);
    assertThat(task.totalTravelMinutes()).isEqualTo(300);
    assertThat(task.totalExpense()).isEqualByComparingTo("450.50");
    assertThat(task.expenseCurrency()).isEqualTo("INR");
  }

  @Test
  void anExpenseTotalNeverAddsTwoCurrenciesTogether() {
    var task = workItem();
    task.logTravel(OWNER, TODAY, "Chennai", "Singapore", "Install", 480, new BigDecimal("100.00"),
        "INR", null, OWNER, NOW);
    task.logTravel(OWNER, TODAY, "Singapore", "Chennai", "Return", 480, new BigDecimal("40.00"),
        "SGD", null, OWNER, NOW);

    // A visibly partial total beats a silently wrong one: 140 would be a number meaning nothing.
    assertThat(task.expenseCurrency()).isEqualTo("INR");
    assertThat(task.totalExpense()).isEqualByComparingTo("100.00");
  }

  @Test
  void aDayIsTheMostAnyoneCanLogAtOnceAndZeroIsNotAnEntry() {
    var task = workItem();
    assertThatThrownBy(() -> task.logTime(OWNER, TODAY, 0, null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than zero");
    assertThatThrownBy(() -> task.logTime(OWNER, TODAY, 1441, null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("24 hours");
  }

  @Test
  void anExpenseCannotBeNegativeButItCanBeNothing() {
    var task = workItem();
    assertThatThrownBy(() -> task.logTravel(OWNER, TODAY, "A", "B", "Visit", 30,
        new BigDecimal("-1.00"), "INR", null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");

    // A journey somebody made at no cost is still a journey worth recording.
    var free = task.logTravel(OWNER, TODAY, "A", "B", "Visit", 30, null, null, null, OWNER, NOW);
    assertThat(free.getExpenseAmount()).isEqualByComparingTo("0.00");
    assertThat(free.getCurrencyCode()).isEqualTo("INR");
  }

  @Test
  void anEntryBelongsToTheWorkerAndToWhoeverRecordedItForThem() {
    var task = workItem();
    var entry = task.logTime(MATE, TODAY, 60, "Site visit.", OWNER, NOW);

    assertThat(entry.belongsTo(MATE)).isTrue();
    assertThat(entry.belongsTo(OWNER)).isTrue();
    assertThat(entry.belongsTo("stranger@orisenc.com")).isFalse();
    assertThat(entry.belongsTo(MATE.toUpperCase())).isTrue();
  }

  @Test
  void correctingAnEntryMarksItCorrectedWithoutLosingWhatItNowSays() {
    var task = workItem();
    var entry = task.logTime(OWNER, TODAY, 60, "Rough guess.", OWNER, NOW);
    assertThat(entry.getUpdatedAt()).isNull();

    task.correctTime(entry, TODAY, 90, "Checked the van log.", NOW.plusSeconds(600));

    assertThat(entry.getDurationMinutes()).isEqualTo(90);
    assertThat(entry.getUpdatedAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(task.totalWorkMinutes()).isEqualTo(90);
  }

  @Test
  void aCurrencyMustBeThreeLetters() {
    var task = workItem();
    assertThatThrownBy(() -> task.logTravel(OWNER, TODAY, "A", "B", "Visit", 30,
        new BigDecimal("10.00"), "RUPEES", null, OWNER, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("three letters");
  }

  @Test
  void anEntryFromAnotherItemIsNotFoundOnThisOne() {
    var task = workItem();
    assertThatThrownBy(() -> task.timeEntry(99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not on this work item");
  }
}
