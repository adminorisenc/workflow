package com.orisenc.workflow.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.projecttask.LinkedEntityType;
import com.orisenc.workflow.projecttask.ProjectTaskDtos;
import com.orisenc.workflow.projecttask.ProjectTaskEntity;
import com.orisenc.workflow.projecttask.ProjectTaskPermissions;
import com.orisenc.workflow.projecttask.ProjectTaskService;
import com.orisenc.workflow.projecttask.ProjectTaskStatus;
import com.orisenc.workflow.projecttask.ProjectTaskType;
import com.orisenc.workflow.projecttask.ProjectTaskVisibility;
import com.orisenc.workflow.task.TaskAction;
import com.orisenc.workflow.task.TaskDtos;
import com.orisenc.workflow.task.TaskEntity;
import com.orisenc.workflow.task.TaskPriority;
import com.orisenc.workflow.task.TaskService;
import com.orisenc.workflow.task.TaskType;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * The acceptance criterion itself: "a task may be completed only by its assignee or an authorized
 * delegate", and the same for a work item's owner.
 *
 * <p>Everything else about delegation is machinery for this. A test of the records alone would prove
 * that rows can be written and read; what needs proving is that writing one changes what the task
 * services will let somebody do, and that revoking it changes it back.
 *
 * <p>It also pins the boundary that keeps delegation from being a privilege escalation: a delegate
 * without the permission for the action is still refused. Common Platform is the RBAC store, and a
 * row in Workflow's own database must not be able to mint an authority the access service never gave.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DelegatedActionTest {

  private static final Instant NOW = Instant.parse("2026-09-10T09:00:00Z");
  private static final Instant DUE = NOW.plus(Duration.ofDays(2));
  private static final Instant UNTIL = NOW.plus(Duration.ofDays(7));

  private static final String OWNER = "priya@orisenc.com";
  private static final String DELEGATE = "ravi@orisenc.com";
  private static final String STRANGER = "stranger@orisenc.com";

  private static final Set<String> WORKER = Set.of(ProjectTaskPermissions.VIEW,
      ProjectTaskPermissions.EXECUTE, DelegationPermissions.VIEW, DelegationPermissions.CREATE);
  private static final Set<String> VIEWER_ONLY =
      Set.of(ProjectTaskPermissions.VIEW, DelegationPermissions.VIEW, DelegationPermissions.CREATE);

  @Autowired private EntityManager entityManager;

  private DelegationService delegations;
  private ProjectTaskService workItems;
  private TaskService approvals;

  @BeforeEach
  void setUp() {
    ApplicationEventPublisher publisher = event -> { };
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    delegations = new DelegationService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), publisher, clock);
    // The public constructor, so this exercises the wiring Spring actually builds. Its own clock is
    // the system one, which is harmless here: every question this test asks about a delegation
    // window is answered by DelegationService, and that one is pinned.
    workItems = new ProjectTaskService(entityManager, publisher, delegations);
    approvals = new TaskService(entityManager, publisher, delegations);

    for (String table : List.of("ProjectTaskHistoryEntity", "ProjectTaskCommentEntity",
        "ProjectTaskChecklistItemEntity", "ProjectTaskEntity", "TaskHistoryEntity", "TaskEntity",
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

  private void delegateWorkItems(String department) {
    signedInAs(OWNER, WORKER);
    delegations.create(new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.WORK_ITEMS, null, department, NOW, UNTIL, "Annual leave"), OWNER, "c");
  }

  private ProjectTaskEntity workItem(String team) {
    var task = new ProjectTaskEntity("WRK-1", "Deliver order SO-90812", "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, ProjectTaskVisibility.INDIVIDUAL, team,
        OWNER, OWNER, LinkedEntityType.SALES_ORDER, null, "SO-90812", null, NOW, DUE);
    task.addHistory("CREATED", OWNER, null, NOW, "c");
    task.transition(ProjectTaskStatus.NOT_STARTED, OWNER, "ready", NOW, "c");
    entityManager.persist(task);
    entityManager.flush();
    return task;
  }

  private TaskEntity approval() {
    var task = new TaskEntity("TSK-1", "Approve credit terms", "SO-90812", "Finance",
        TaskType.APPROVAL, TaskPriority.HIGH, "requester@orisenc.com", OWNER,
        "Terms need a decision.", null, NOW, DUE);
    task.addHistory("CREATED", "requester@orisenc.com", null, NOW, "c");
    entityManager.persist(task);
    entityManager.flush();
    return task;
  }

  // ---------------------------------------------------------------- work items

  @Test
  void withoutADelegationSomebodyElsesWorkItemIsNotEvenVisible() {
    workItem("Operations");
    signedInAs(DELEGATE, WORKER);

    assertThatThrownBy(() -> workItems.get("WRK-1", DELEGATE, WORKER))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void aDelegateSeesAndMayProgressTheWorkTheyWereGiven() {
    workItem("Operations");
    delegateWorkItems(null);
    signedInAs(DELEGATE, WORKER);

    var detail = workItems.get("WRK-1", DELEGATE, WORKER);

    assertThat(detail.mayAct()).isTrue();
    assertThat(workItems.list(new ProjectTaskService.ListQuery(null, null, null, null, null, null, null, null, null, null, null, null, null), DELEGATE, WORKER))
        .extracting(ProjectTaskDtos.ProjectTaskSummary::id).containsExactly("WRK-1");
  }

  @Test
  void theHistoryNamesBothTheDelegateAndTheOwner() {
    // REQ-0027's "original/delegated actor history" in one assertion. Either name alone is
    // misleading: the delegate did it, and the owner was accountable for it.
    workItem("Operations");
    delegateWorkItems(null);
    signedInAs(DELEGATE, WORKER);
    var detail = workItems.get("WRK-1", DELEGATE, WORKER);

    var moved = workItems.transition("WRK-1",
        new ProjectTaskDtos.TransitionRequest(ProjectTaskStatus.IN_PROGRESS, "Picked up for Priya.",
            detail.version()), DELEGATE, "c");

    var recorded = movedRow(moved);
    assertThat(recorded.actor()).isEqualTo(DELEGATE);
    assertThat(recorded.onBehalfOf()).isEqualTo(OWNER);
  }

  @Test
  void actingOnYourOwnWorkRecordsNobodyElse() {
    workItem("Operations");
    signedInAs(OWNER, WORKER);
    var detail = workItems.get("WRK-1", OWNER, WORKER);

    var moved = workItems.transition("WRK-1",
        new ProjectTaskDtos.TransitionRequest(ProjectTaskStatus.IN_PROGRESS, "Starting.",
            detail.version()), OWNER, "c");

    assertThat(movedRow(moved).onBehalfOf()).isNull();
  }

  @Test
  void aDelegationNarrowedToOneTeamDoesNotReachAnother() {
    // The workbook's criterion names department, and this is what narrowing has to actually do.
    workItem("Tech");
    delegateWorkItems("Operations");
    signedInAs(DELEGATE, WORKER);

    assertThatThrownBy(() -> workItems.get("WRK-1", DELEGATE, WORKER))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void aDelegationGrantsNoPermissionOfItsOwn() {
    // The boundary that stops a delegation being a back door into RBAC. A delegate who cannot
    // execute work still cannot execute it; the fix is a role, granted where roles live.
    workItem("Operations");
    delegateWorkItems(null);
    signedInAs(DELEGATE, VIEWER_ONLY);

    var detail = workItems.get("WRK-1", DELEGATE, VIEWER_ONLY);

    assertThat(detail.mayAct()).isFalse();
    assertThatThrownBy(() -> workItems.transition("WRK-1",
        new ProjectTaskDtos.TransitionRequest(ProjectTaskStatus.IN_PROGRESS, "Trying.",
            detail.version()), DELEGATE, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("delegated");
  }

  @Test
  void revokingTheDelegationTakesTheWorkBackImmediately() {
    workItem("Operations");
    delegateWorkItems(null);
    signedInAs(DELEGATE, WORKER);
    assertThat(workItems.get("WRK-1", DELEGATE, WORKER).mayAct()).isTrue();

    signedInAs(OWNER, WORKER);
    var live = delegations.list(new DelegationService.ListQuery(null, null, null, null, null, null),
        OWNER).getFirst();
    delegations.revoke(live.id(), new DelegationDtos.DecisionRequest("Back early.", live.version()),
        OWNER, "c");

    signedInAs(DELEGATE, WORKER);
    assertThatThrownBy(() -> workItems.get("WRK-1", DELEGATE, WORKER))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void aStrangerIsUnaffectedByEverybodyElsesDelegations() {
    workItem("Operations");
    delegateWorkItems(null);
    signedInAs(STRANGER, WORKER);

    assertThat(workItems.list(new ProjectTaskService.ListQuery(null, null, null, null, null, null, null, null, null, null, null, null, null), STRANGER, WORKER)).isEmpty();
  }

  /**
   * The history row that recorded the move to IN_PROGRESS.
   *
   * <p>Selected by what it records rather than by being last. The fixture writes its rows on a fixed
   * clock and the service writes this one on the system clock, so "last by timestamp" is not the
   * same as "most recent" here - and the row this test is about is the one it names.
   */
  private static ProjectTaskDtos.HistoryResponse movedRow(ProjectTaskDtos.ProjectTaskDetail detail) {
    return detail.history().stream()
        .filter(event -> event.toStatus() == ProjectTaskStatus.IN_PROGRESS)
        .findFirst().orElseThrow();
  }

  // ---------------------------------------------------------------- approvals

  @Test
  void withoutADelegationOnlyTheAssigneeDecides() {
    approval();

    assertThatThrownBy(() -> approvals.act("TSK-1",
        new TaskDtos.ActionRequest(TaskAction.APPROVE, "Looks fine.", 0L), DELEGATE, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("assignee or an authorized delegate");
  }

  @Test
  void anApprovalDelegationHasToBeApprovedBeforeItAuthorisesAnything() {
    // The privileged gate, seen from the task side: the delegation exists, and the delegate is still
    // refused until somebody else signs it off.
    approval();
    signedInAs(OWNER, WORKER);
    delegations.create(new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.APPROVALS, null, null, NOW, UNTIL, "Annual leave"), OWNER, "c");

    assertThatThrownBy(() -> approvals.act("TSK-1",
        new TaskDtos.ActionRequest(TaskAction.APPROVE, "Looks fine.", 0L), DELEGATE, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("assignee or an authorized delegate");
  }

  @Test
  void onceApprovedTheDelegateCanDecideAndBothNamesAreOnTheDecision() {
    approval();
    signedInAs(OWNER, WORKER);
    var created = delegations.create(new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.APPROVALS, null, null, NOW, UNTIL, "Annual leave"), OWNER, "c");
    signedInAs("lead@orisenc.com", Set.of(DelegationPermissions.VIEW, DelegationPermissions.APPROVE,
        DelegationPermissions.MANAGE));
    delegations.approve(created.id(),
        new DelegationDtos.DecisionRequest("Cover agreed.", created.version()), "lead@orisenc.com", "c");

    var decided = approvals.act("TSK-1",
        new TaskDtos.ActionRequest(TaskAction.APPROVE, "Terms match the contract.", 0L), DELEGATE, "c");

    assertThat(decided.status().name()).isEqualTo("COMPLETED");
    var latest = decided.history().getLast();
    assertThat(latest.action()).isEqualTo("APPROVED");
    assertThat(latest.actor()).isEqualTo(DELEGATE);
    assertThat(latest.onBehalfOf()).isEqualTo(OWNER);
  }

  @Test
  void aWorkItemDelegationDoesNotLetSomebodyApprove() {
    // The scope split, proven where it matters: covering deliveries is not authority to decide.
    approval();
    delegateWorkItems(null);

    assertThatThrownBy(() -> approvals.act("TSK-1",
        new TaskDtos.ActionRequest(TaskAction.APPROVE, "Looks fine.", 0L), DELEGATE, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("assignee or an authorized delegate");
  }
}
