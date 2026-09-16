package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.delegation.ActiveUserDirectory;
import com.orisenc.workflow.delegation.DelegationProperties;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
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
 * Putting someone on a work item through the service, and what the API refuses.
 *
 * <p>{@link ProjectTaskAssigneeAccessTest} pins the rules in isolation. This one exercises them
 * through the service the controller calls, against a real database, because that is where the parts
 * that cannot be unit-tested live: the permission gate, the status codes a screen has to handle, and
 * the history rows TM-018 requires.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ProjectTaskAssigneeServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
  private static final Instant DUE = NOW.plus(Duration.ofDays(2));

  private static final String OWNER = "priya@orisenc.com";
  private static final String MATE = "ravi@orisenc.com";
  private static final String LEAD = "lead@orisenc.com";
  private static final String STRANGER = "stranger@orisenc.com";

  private static final Set<String> WORKER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.EXECUTE);
  private static final Set<String> TEAM_LEAD = Set.of(ProjectTaskPermissions.VIEW,
      ProjectTaskPermissions.VIEW_TEAM, ProjectTaskPermissions.EXECUTE,
      ProjectTaskPermissions.ASSIGNEES_MANAGE);
  private static final Set<String> ASSIGNING_MANAGER = Set.of(ProjectTaskPermissions.VIEW,
      ProjectTaskPermissions.MANAGE, ProjectTaskPermissions.ASSIGNEES_MANAGE);
  private static final Set<String> MANAGER_NO_ASSIGN =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.MANAGE);

  @Autowired private EntityManager entityManager;
  @Autowired private ApplicationContext context;

  private ProjectTaskService workItems;
  private DelegationService delegations;

  @BeforeEach
  void setUp() {
    ApplicationEventPublisher publisher = event -> { };
    // The public constructor: the Clock-taking one is package-private to the delegation package.
    // ActiveUserDirectory is not a bean in a JPA slice, so the provider yields nothing - which only
    // matters when a delegation is created, and this test creates none.
    delegations = new DelegationService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), publisher,
        context.getBeanProvider(ActiveUserDirectory.class));
    workItems = new ProjectTaskService(entityManager, publisher, delegations);

    for (String table : List.of("ProjectTaskAssigneeEntity", "ProjectTaskHistoryEntity",
        "ProjectTaskCommentEntity", "ProjectTaskChecklistItemEntity", "ProjectTaskEntity",
        "DelegationEventEntity", "DelegationEntity"))
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

  private ProjectTaskEntity workItem() {
    return workItem(ProjectTaskVisibility.RELEVANT_TEAM);
  }

  private ProjectTaskEntity workItem(ProjectTaskVisibility visibility) {
    var task = new ProjectTaskEntity("WRK-1", "Deliver order SO-90812", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, visibility,
        "Operations", OWNER, OWNER, LinkedEntityType.SALES_ORDER, null, "SO-90812", null, NOW, DUE);
    task.addHistory("CREATED", OWNER, null, NOW, "c");
    task.transition(ProjectTaskStatus.NOT_STARTED, OWNER, "ready", NOW, "c");
    entityManager.persist(task);
    entityManager.flush();
    return task;
  }

  /* --------------------------------------------------------------------------- the gate */

  @Test
  void aWorkerCannotPutSomebodyOnTheirOwnItem() {
    workItem();
    signedInAs(OWNER, WORKER);

    // Widening who may see and progress a record is supervisory, not part of working it.
    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest(MATE), OWNER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void theWorkManagePermissionAloneDoesNotCarryTheAssigneeGrant() {
    workItem();
    signedInAs(LEAD, MANAGER_NO_ASSIGN);

    // Two separate codes on purpose; holding one must not silently confer the other.
    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void someoneWhoCannotSeeTheItemCannotAssignOnIt() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);
    signedInAs(STRANGER, Set.of(ProjectTaskPermissions.VIEW,
        ProjectTaskPermissions.ASSIGNEES_MANAGE));

    // readable() runs before the permission gate, so an invisible item is not found rather than
    // forbidden - its existence is not confirmed to someone with no business seeing it.
    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest(MATE), STRANGER, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  /* ------------------------------------------------------------------- the happy path */

  @Test
  void aManagerPutsSomeoneOnAPrivateItemAndTheyCanThenSeeAndProgressIt() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);

    signedInAs(MATE, WORKER);
    assertThatThrownBy(() -> workItems.get("WRK-1", MATE, WORKER))
        .isInstanceOf(ApiException.class);

    // ASSIGNEES_MANAGE carries no sight of its own, so reaching a private item still needs MANAGE.
    signedInAs(LEAD, ASSIGNING_MANAGER);
    var afterAdd = workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");
    assertThat(afterAdd.assignees()).singleElement()
        .satisfies(entry -> {
          assertThat(entry.username()).isEqualTo(MATE);
          assertThat(entry.addedBy()).isEqualTo(LEAD);
        });

    signedInAs(MATE, WORKER);
    var seen = workItems.get("WRK-1", MATE, WORKER);
    assertThat(seen.id()).isEqualTo("WRK-1");
    assertThat(seen.mayAct()).isTrue();
    // Seeing the list is a view-level question, so the person on it can read it.
    assertThat(workItems.assignees("WRK-1", MATE)).hasSize(1);
    // But they cannot change it - that needs the manage code they do not hold.
    assertThat(seen.mayManageAssignees()).isFalse();
  }

  @Test
  void removingThemTakesTheItemAwayAgain() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);
    signedInAs(LEAD, ASSIGNING_MANAGER);
    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");

    signedInAs(MATE, WORKER);
    assertThat(workItems.get("WRK-1", MATE, WORKER).id()).isEqualTo("WRK-1");

    signedInAs(LEAD, ASSIGNING_MANAGER);
    var afterRemove = workItems.removeAssignee("WRK-1", MATE, LEAD, "c");
    assertThat(afterRemove.assignees()).isEmpty();

    signedInAs(MATE, WORKER);
    assertThatThrownBy(() -> workItems.get("WRK-1", MATE, WORKER)).isInstanceOf(ApiException.class);
  }

  @Test
  void theWorklistAndADirectLoadAgreeOnWhatTheAssigneeCanSee() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);
    signedInAs(LEAD, ASSIGNING_MANAGER);
    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");

    // The audience subquery and mayView must return the same answer, or an item is reachable by id
    // but never appears in a list.
    signedInAs(MATE, WORKER);
    assertThat(workItems.list(new ProjectTaskService.ListQuery(null, null, null, null, null, null, null, null, null, null, null, 0, 20), MATE, WORKER))
        .extracting(ProjectTaskDtos.ProjectTaskSummary::id).contains("WRK-1");
  }

  @Test
  void myWorkIncludesWhatIWasAssignedToAndNotSomebodyElsesOwnWork() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);
    signedInAs(LEAD, ASSIGNING_MANAGER);
    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");

    // "My work" is the work that is mine to do. Before named assignees that was the same as the work
    // I own; it is not any more, and a list that still meant "owned by me" would make being assigned
    // to something invisible to the person assigned.
    signedInAs(MATE, WORKER);
    assertThat(mine(MATE)).extracting(ProjectTaskDtos.ProjectTaskSummary::id).contains("WRK-1");

    // The owner filter still means ownership, so it does not pick the item up for them.
    assertThat(workItems.list(new ProjectTaskService.ListQuery(null, "me", null, null, null, null,
        null, null, null, null, null, 0, 20), MATE, WORKER)).isEmpty();

    // And it stays somebody else's work for everyone else.
    signedInAs(STRANGER, WORKER);
    assertThat(mine(STRANGER)).isEmpty();
  }

  private List<ProjectTaskDtos.ProjectTaskSummary> mine(String actor) {
    return workItems.list(new ProjectTaskService.ListQuery(null, null, null, null, null, null,
        null, null, null, null, true, 0, 20), actor, WORKER);
  }

  /* --------------------------------------------------------------------- the refusals */

  @Test
  void theOwnerAndARepeatAreBothAConflict() {
    workItem();
    signedInAs(LEAD, TEAM_LEAD);

    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest(OWNER), LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("already owns");

    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");
    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT))
        .hasMessageContaining("already assigned");
  }

  @Test
  void removingSomebodyWhoIsNotOnItIsNotFound() {
    workItem();
    signedInAs(LEAD, TEAM_LEAD);

    assertThatThrownBy(() -> workItems.removeAssignee("WRK-1", STRANGER, LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void aBlankUsernameIsABadRequestRatherThanAnEmptyRow() {
    workItem();
    signedInAs(LEAD, TEAM_LEAD);

    assertThatThrownBy(() -> workItems.addAssignee("WRK-1",
        new ProjectTaskDtos.AssigneeRequest("   "), LEAD, "c"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  /* -------------------------------------------- what a broad audience does and does not get */

  @Test
  void anAudienceOnlyViewerGetsMetadataAndCommentsButNoHistoryOrCustomer() {
    var task = workItem(ProjectTaskVisibility.ALL_TEAMS);
    task.linkToMaster(7L, 42L, null);
    entityManager.flush();

    // STRANGER reaches this only because somebody published it to everyone. The enum has always said
    // "metadata and status only"; until now the service handed over the audit trail as well.
    signedInAs(STRANGER, WORKER);
    var seen = workItems.get("WRK-1", STRANGER, WORKER);

    assertThat(seen.title()).isEqualTo("Deliver order SO-90812");
    assertThat(seen.status()).isNotNull();
    assertThat(seen.history()).as("an audit trail is not metadata, status or a comment").isEmpty();
    assertThat(seen.masterData()).as("TM-012 keeps the customer with the people doing the work").isNull();
  }

  @Test
  void theOwnerAndAnAssigneeBothGetTheHistoryAndTheCustomer() {
    var task = workItem(ProjectTaskVisibility.ALL_TEAMS);
    task.linkToMaster(7L, 42L, null);
    entityManager.flush();

    signedInAs(OWNER, WORKER);
    var asOwner = workItems.get("WRK-1", OWNER, WORKER);
    assertThat(asOwner.history()).isNotEmpty();
    assertThat(asOwner.masterData().customerId()).isEqualTo(42L);

    signedInAs(LEAD, ASSIGNING_MANAGER);
    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");

    // Being put on a task is what makes it yours, so it carries the same entitlement as owning it.
    signedInAs(MATE, WORKER);
    var asAssignee = workItems.get("WRK-1", MATE, WORKER);
    assertThat(asAssignee.history()).isNotEmpty();
    assertThat(asAssignee.masterData().customerId()).isEqualTo(42L);
  }

  @Test
  void aTaskCanCarryTheCustomerItIsAboutAndRefusesToCarryBoth() {
    signedInAs(OWNER, Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.CREATE,
        ProjectTaskPermissions.EXECUTE));
    var created = workItems.create(new ProjectTaskDtos.CreateProjectTaskRequest(
        "Chase the PO", "Customer has not sent it.", ProjectTaskType.FOLLOW_UP, TaskPriority.MEDIUM,
        ProjectTaskVisibility.INDIVIDUAL, "Operations", OWNER, LinkedEntityType.CUSTOMER, "42",
        "Northstar Medical", null, DUE, null, 7L, 42L, null, null, null, null), OWNER, "c");

    // The column existed on the entity and no create path had ever populated it, so TM-002's
    // customer link was modelled and never wired.
    assertThat(created.masterData().customerId()).isEqualTo(42L);
    assertThat(created.masterData().organizationId()).isEqualTo(7L);

    assertThatThrownBy(() -> workItems.create(new ProjectTaskDtos.CreateProjectTaskRequest(
        "Both", "Not allowed.", ProjectTaskType.FOLLOW_UP, TaskPriority.MEDIUM,
        ProjectTaskVisibility.INDIVIDUAL, "Operations", OWNER, LinkedEntityType.NONE, null, null,
        null, DUE, null, 7L, 42L, 9L, null, null, null), OWNER, "c"))
        .isInstanceOf(Exception.class);
  }

  /* ------------------------------------------------------------------------- the trail */

  @Test
  void addingAndRemovingBothLandInTheImmutableHistory() {
    workItem(ProjectTaskVisibility.INDIVIDUAL);
    signedInAs(LEAD, ASSIGNING_MANAGER);
    workItems.addAssignee("WRK-1", new ProjectTaskDtos.AssigneeRequest(MATE), LEAD, "c");
    var afterRemove = workItems.removeAssignee("WRK-1", MATE, LEAD, "c");

    // TM-018 asks for assignment changes in the trail, not only in the current row: who was on this
    // last week is a question an auditor asks, and the live table cannot answer it.
    assertThat(afterRemove.history()).extracting(ProjectTaskDtos.HistoryResponse::action)
        .contains("ASSIGNEE_ADDED", "ASSIGNEE_REMOVED");
    assertThat(afterRemove.history())
        .filteredOn(entry -> entry.action().startsWith("ASSIGNEE_"))
        .allSatisfy(entry -> {
          assertThat(entry.actor()).isEqualTo(LEAD);
          assertThat(entry.reason()).isEqualTo(MATE);
        });
  }

}
