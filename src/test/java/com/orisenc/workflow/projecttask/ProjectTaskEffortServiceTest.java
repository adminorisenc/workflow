package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.delegation.ActiveUserDirectory;
import com.orisenc.workflow.delegation.DelegationProperties;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.TimeEntryRequest;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.TravelEntryRequest;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.UpdateProjectTaskRequest;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * Editing, timesheets and travel through the service, against a real database (TM-A03, TM-A05,
 * TM-A06).
 *
 * <p>{@link ProjectTaskEditAndEffortTest} pins the entity's own rules. This one covers what only
 * exists once the service and a schema are involved: who the permission gate lets through, the
 * status codes a screen has to handle, the history rows TM-018 requires, and - the part worth most -
 * that individual effort is withheld from somebody who can see the task but is not on it.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ProjectTaskEffortServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
  private static final Instant DUE = NOW.plus(Duration.ofDays(2));
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

  private static final String OWNER = "priya@orisenc.com";
  private static final String MATE = "ravi@orisenc.com";
  private static final String LEAD = "lead@orisenc.com";
  private static final String STRANGER = "stranger@orisenc.com";

  private static final Set<String> WORKER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.EXECUTE);
  private static final Set<String> VIEWER = Set.of(ProjectTaskPermissions.VIEW);
  private static final Set<String> MANAGER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.MANAGE);

  @Autowired private EntityManager entityManager;
  @Autowired private ApplicationContext context;

  private ProjectTaskService workItems;

  @BeforeEach
  void setUp() {
    ApplicationEventPublisher publisher = event -> { };
    var delegations = new DelegationService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), publisher,
        context.getBeanProvider(ActiveUserDirectory.class));
    workItems = new ProjectTaskService(entityManager, publisher, delegations);

    // Children before parents: the two new tables hold a task_id foreign key like the rest.
    for (String table : List.of("ProjectTaskTimeEntryEntity", "ProjectTaskTravelEntryEntity",
        "ProjectTaskAssigneeEntity", "ProjectTaskHistoryEntity", "ProjectTaskCommentEntity",
        "ProjectTaskChecklistItemEntity", "ProjectTaskEntity", "DelegationEventEntity",
        "DelegationEntity"))
      entityManager.createQuery("delete from " + table).executeUpdate();
    entityManager.flush();
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  private void signedInAs(String user, Set<String> permissions) {
    var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
  }

  private ProjectTaskEntity workItem(ProjectTaskVisibility visibility) {
    var task = new ProjectTaskEntity("WRK-1", "Deliver order SO-90812", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, visibility, "Operations", OWNER, OWNER,
        LinkedEntityType.SALES_ORDER, null, "SO-90812", null, NOW, DUE);
    task.addHistory("CREATED", OWNER, null, NOW, "c");
    task.transition(ProjectTaskStatus.NOT_STARTED, OWNER, "ready", NOW, "c");
    entityManager.persist(task);
    entityManager.flush();
    return task;
  }

  private ProjectTaskEntity workItem() {
    return workItem(ProjectTaskVisibility.RELEVANT_TEAM);
  }

  /** The item's current state as an update request, so a test names only what it is changing. */
  private UpdateProjectTaskRequest formFor(ProjectTaskEntity task) {
    return new UpdateProjectTaskRequest(task.getTitle(), task.getSummary(), task.getDescription(),
        task.getTaskType(), task.getPriority(), task.getVisibility(), task.getRelevantTeam(),
        task.getLinkedEntityType(), task.getLinkedEntityId(), task.getLinkedEntityRef(),
        task.getParentTaskId(), task.getDueAt(), task.getPlannedStartAt(), task.getPlannedEndAt(),
        task.getStartedAt(), task.getCompletedAt(), task.getVersion());
  }

  /* ------------------------------------------------------------------------------ editing */

  @Test
  void theOwnerCanEditAndEachChangedFieldGetsItsOwnHistoryRow() {
    var task = workItem();
    signedInAs(OWNER, WORKER);
    var form = formFor(task);

    var updated = workItems.update("WRK-1", new UpdateProjectTaskRequest(form.title(),
        "Van booked.", form.description(), form.taskType(), TaskPriority.CRITICAL,
        form.visibility(), form.relevantTeam(), form.linkedEntityType(), form.linkedEntityId(),
        form.linkedEntityRef(), form.parentTaskId(), form.dueAt(), NOW, DUE, null, null,
        form.expectedVersion()), OWNER, "c");

    assertThat(updated.summary()).isEqualTo("Van booked.");
    assertThat(updated.plannedStartAt()).isEqualTo(NOW);
    assertThat(updated.plannedEndAt()).isEqualTo(DUE);

    // Four fields moved, so four rows. "The task was edited" would answer none of TM-018's question.
    var edits = updated.history().stream().filter(row -> "FIELD_CHANGED".equals(row.action())).toList();
    assertThat(edits).hasSize(4);
    assertThat(edits).extracting(ProjectTaskDtos.HistoryResponse::reason)
        .anySatisfy(reason -> assertThat(reason).contains("priority changed from HIGH to CRITICAL"))
        .anySatisfy(reason -> assertThat(reason).contains("summary set to Van booked."));
    assertThat(edits).allSatisfy(row -> assertThat(row.actor()).isEqualTo(OWNER));
  }

  @Test
  void savingTheFormUnchangedWritesNothing() {
    var task = workItem();
    signedInAs(OWNER, WORKER);
    int before = workItems.get("WRK-1", OWNER, ProjectTaskPermissions.granted()).history().size();

    var updated = workItems.update("WRK-1", formFor(task), OWNER, "c");

    assertThat(updated.history()).hasSize(before);
  }

  @Test
  void somebodyWhoOnlyHappensToSeeTheItemCannotEditIt() {
    var task = workItem(ProjectTaskVisibility.ALL_TEAMS);
    signedInAs(STRANGER, WORKER);

    // Published to everyone is not the same as theirs to change - the distinction TM-014 draws.
    assertThatThrownBy(() -> workItems.update("WRK-1", formFor(task), STRANGER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void aManagerCanCorrectAnItemTheyDoNotOwn() {
    var task = workItem();
    signedInAs(LEAD, MANAGER);
    var form = formFor(task);

    var updated = workItems.update("WRK-1", new UpdateProjectTaskRequest(form.title(),
        form.summary(), form.description(), form.taskType(), form.priority(), form.visibility(),
        "Logistics", form.linkedEntityType(), form.linkedEntityId(), form.linkedEntityRef(),
        form.parentTaskId(), form.dueAt(), null, null, null, null, form.expectedVersion()),
        LEAD, "c");

    // Fixing a wrong team should not require first taking the work off the person doing it.
    assertThat(updated.relevantTeam()).isEqualTo("Logistics");
  }

  @Test
  void aClosedItemMustBeReopenedBeforeItsDetailsChange() {
    var task = workItem();
    signedInAs(OWNER, WORKER);
    workItems.transition("WRK-1", new ProjectTaskDtos.TransitionRequest(
        ProjectTaskStatus.IN_PROGRESS, "starting", task.getVersion()), OWNER, "c");
    var current = workItems.get("WRK-1", OWNER, ProjectTaskPermissions.granted());
    workItems.transition("WRK-1", new ProjectTaskDtos.TransitionRequest(
        ProjectTaskStatus.COMPLETED, "delivered", current.version()), OWNER, "c");

    var closed = workItems.get("WRK-1", OWNER, ProjectTaskPermissions.granted());
    assertThat(closed.mayEdit()).isFalse();

    assertThatThrownBy(() -> workItems.update("WRK-1", new UpdateProjectTaskRequest(closed.title(),
        "too late", closed.description(), closed.taskType(), closed.priority(), closed.visibility(),
        closed.relevantTeam(), LinkedEntityType.SALES_ORDER, null, "SO-90812", null, closed.dueAt(),
        null, null, null, null, closed.version()), OWNER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> {
          assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e).hasMessageContaining("Reopen it");
        });
  }

  @Test
  void anEditBuiltOnAStaleReadIsRefusedRatherThanOverwriting() {
    var task = workItem();
    signedInAs(OWNER, WORKER);
    var form = formFor(task);

    assertThatThrownBy(() -> workItems.update("WRK-1", new UpdateProjectTaskRequest(form.title(),
        "mine", form.description(), form.taskType(), form.priority(), form.visibility(),
        form.relevantTeam(), form.linkedEntityType(), form.linkedEntityId(), form.linkedEntityRef(),
        form.parentTaskId(), form.dueAt(), null, null, null, null, 99L), OWNER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  /* ---------------------------------------------------------------------- time and travel */

  @Test
  void loggingTimeUpdatesTheTotalsAndLandsOnTheTrail() {
    workItem();
    signedInAs(OWNER, WORKER);

    var updated = workItems.logTime("WRK-1",
        new TimeEntryRequest(null, TODAY, 150, "Loaded and delivered."), OWNER, "c");

    assertThat(updated.effort().workMinutes()).isEqualTo(150);
    assertThat(updated.effort().travelMinutes()).isZero();
    assertThat(updated.timeEntries()).singleElement().satisfies(entry -> {
      assertThat(entry.username()).isEqualTo(OWNER);
      assertThat(entry.note()).isEqualTo("Loaded and delivered.");
      assertThat(entry.mayEdit()).isTrue();
    });
    assertThat(updated.history()).anySatisfy(row -> {
      assertThat(row.action()).isEqualTo("TIME_LOGGED");
      assertThat(row.reason()).contains("2h 30m");
    });
  }

  @Test
  void travelCarriesItsOwnTimeAndItsExpenseWithoutTouchingTheWorkTotal() {
    workItem();
    signedInAs(OWNER, WORKER);
    workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 120, "On site."), OWNER, "c");

    var updated = workItems.logTravel("WRK-1", new TravelEntryRequest(null, TODAY, "Depot",
        "Northstar Medical", "Delivery run", 300, new BigDecimal("450.50"), "INR", "BILL-22"),
        OWNER, "c");

    assertThat(updated.effort().workMinutes()).isEqualTo(120);
    assertThat(updated.effort().travelMinutes()).isEqualTo(300);
    assertThat(updated.effort().expenseTotal()).isEqualByComparingTo("450.50");
    assertThat(updated.effort().expenseCurrency()).isEqualTo("INR");
    assertThat(updated.travelEntries()).singleElement()
        .satisfies(entry -> assertThat(entry.voucherRef()).isEqualTo("BILL-22"));
  }

  @Test
  void youCannotPutHoursAgainstSomebodyElsesNameUnlessYouManageTheQueue() {
    workItem();
    signedInAs(OWNER, WORKER);

    // Hours attributed to a person are a statement about that person.
    assertThatThrownBy(() -> workItems.logTime("WRK-1",
        new TimeEntryRequest(MATE, TODAY, 60, "on their behalf"), OWNER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));

    signedInAs(LEAD, MANAGER);
    var updated = workItems.logTime("WRK-1",
        new TimeEntryRequest(MATE, TODAY, 60, "Worked offline."), LEAD, "c");
    assertThat(updated.timeEntries()).singleElement()
        .satisfies(entry -> assertThat(entry.username()).isEqualTo(MATE));
  }

  @Test
  void youCanOnlyCorrectAndRemoveEntriesThatAreYours() {
    workItem();
    signedInAs(OWNER, WORKER);
    var logged = workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 60, "Rough guess."),
        OWNER, "c");
    Long entryId = logged.timeEntries().getFirst().id();

    signedInAs(LEAD, MANAGER);
    // Deliberately not even a manager: a correction is a restatement of what somebody did.
    assertThatThrownBy(() -> workItems.correctTime("WRK-1", entryId,
        new TimeEntryRequest(null, TODAY, 30, "trimmed"), LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));

    signedInAs(OWNER, WORKER);
    var corrected = workItems.correctTime("WRK-1", entryId,
        new TimeEntryRequest(null, TODAY, 90, "Checked the van log."), OWNER, "c");
    assertThat(corrected.effort().workMinutes()).isEqualTo(90);
    assertThat(corrected.history()).anySatisfy(row -> {
      assertThat(row.action()).isEqualTo("TIME_CORRECTED");
      assertThat(row.reason()).contains("1h").contains("1h 30m");
    });
  }

  @Test
  void removingAnEntryTakesItOutOfTheTotalAndLeavesTheFactOnTheTrail() {
    workItem();
    signedInAs(OWNER, WORKER);
    var logged = workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 60, "Mistake."),
        OWNER, "c");

    var after = workItems.removeTime("WRK-1", logged.timeEntries().getFirst().id(), OWNER, "c");

    assertThat(after.timeEntries()).isEmpty();
    assertThat(after.effort().workMinutes()).isZero();
    // A deletion nobody can see is a hole in the total the audit trail cannot explain.
    assertThat(after.history()).anySatisfy(row -> {
      assertThat(row.action()).isEqualTo("TIME_REMOVED");
      assertThat(row.reason()).contains("1h");
    });
  }

  @Test
  void anAssigneeMayLogTimeButMayNotRedefineTheWork() {
    var task = workItem();
    task.addAssignee(MATE, OWNER, NOW);
    entityManager.flush();
    signedInAs(MATE, WORKER);

    var logged = workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 45, "Captured POD."),
        MATE, "c");
    assertThat(logged.effort().workMinutes()).isEqualTo(45);
    assertThat(logged.mayEdit()).isFalse();

    // Being put on the work as defined is not authority to change what it is.
    assertThatThrownBy(() -> workItems.update("WRK-1", formFor(task), MATE, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  /* ------------------------------------------------------------- the part that matters most */

  @Test
  void anAllTeamsViewerSeesTheItemAndNotWhoSpentHowLongOnIt() {
    workItem(ProjectTaskVisibility.ALL_TEAMS);
    signedInAs(OWNER, WORKER);
    workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 840, "A long day."), OWNER, "c");
    workItems.logTravel("WRK-1", new TravelEntryRequest(null, TODAY, "Depot", "Site", "Delivery",
        120, new BigDecimal("300.00"), "INR", null), OWNER, "c");

    signedInAs(STRANGER, VIEWER);
    var seen = workItems.get("WRK-1", STRANGER, ProjectTaskPermissions.granted());

    // The task is org-wide readable, which is what ALL_TEAMS means. That "priya spent fourteen
    // hours on it" is not - individual effort sits on the far side of the line TM-014 draws.
    assertThat(seen.title()).isEqualTo("Deliver order SO-90812");
    assertThat(seen.mayViewEffort()).isFalse();
    assertThat(seen.effort()).isNull();
    assertThat(seen.timeEntries()).isEmpty();
    assertThat(seen.travelEntries()).isEmpty();
  }

  @Test
  void theOwnerAndAManagerBothSeeTheTimesheetInFull() {
    workItem(ProjectTaskVisibility.ALL_TEAMS);
    signedInAs(OWNER, WORKER);
    workItems.logTime("WRK-1", new TimeEntryRequest(null, TODAY, 120, "Delivered."), OWNER, "c");

    assertThat(workItems.get("WRK-1", OWNER, ProjectTaskPermissions.granted()).mayViewEffort())
        .isTrue();

    signedInAs(LEAD, MANAGER);
    var asLead = workItems.get("WRK-1", LEAD, ProjectTaskPermissions.granted());
    assertThat(asLead.mayViewEffort()).isTrue();
    assertThat(asLead.effort().workMinutes()).isEqualTo(120);
  }

  @Test
  void aViewerWhoCannotSeeTheEffortCannotLogAgainstItEither() {
    workItem(ProjectTaskVisibility.ALL_TEAMS);
    signedInAs(STRANGER, WORKER);

    // Deny-by-default in both directions: the audience lets a stranger read the item, and neither
    // reading nor holding EXECUTE makes them one of the people working it.
    assertThatThrownBy(() -> workItems.logTime("WRK-1",
        new TimeEntryRequest(null, TODAY, 60, "not mine to log"), STRANGER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }
}
