package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.delegation.DelegationCover;
import com.orisenc.workflow.task.TaskPriority;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Naming a second person on a work item, and what that does and does not grant.
 *
 * <p>An item keeps one accountable owner (TM-003) because the SLA ladder, delegation and claiming
 * each need a single answer to "who". These tests pin the two halves of the addition: a named
 * assignee can see and progress the item, and being named grants nothing beyond it - which is the
 * boundary policy note P-04 draws and TM-012/TM-014 mark critical.
 */
class ProjectTaskAssigneeAccessTest {

  private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
  private static final String OWNER = "owner@orisenc.com";
  private static final String CREATOR = "creator@orisenc.com";
  private static final String MATE = "mate@orisenc.com";
  private static final String STRANGER = "stranger@orisenc.com";
  private static final String LEAD = "lead@orisenc.com";

  private static final Set<String> VIEWER = Set.of(ProjectTaskPermissions.VIEW);
  private static final Set<String> WORKER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.EXECUTE);
  private static final Set<String> MANAGER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.MANAGE);

  private ProjectTaskEntity task(ProjectTaskVisibility visibility, String owner) {
    return new ProjectTaskEntity("WRK-1", "t", "d", ProjectTaskType.DELIVERABLE, TaskPriority.MEDIUM,
        visibility, "Operations", owner, CREATOR, LinkedEntityType.NONE, null, null, null,
        NOW, NOW.plus(1, ChronoUnit.DAYS));
  }

  /* ----------------------------------------------------------------- the entity's own rules */

  @Test
  void theOwnerCannotAlsoBeNamedAsAnAssignee() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);

    // Two answers to "who is on this task" depending on which field you read is the defect this
    // refuses. The owner is already on it.
    assertThatThrownBy(() -> task.addAssignee(OWNER, LEAD, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already owns");
  }

  @Test
  void theSamePersonCannotBeAddedTwice() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    assertThatThrownBy(() -> task.addAssignee(MATE, LEAD, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already assigned");
  }

  @Test
  void aBlankUsernameIsRefused() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    assertThatThrownBy(() -> task.addAssignee("  ", LEAD, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void membershipIsCaseInsensitiveAndSurvivesSurroundingSpace() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee("  Mate@Orisenc.com  ", LEAD, NOW);

    // The same comparison ownerUserId and createdBy already get, so "is this the owner" and "is this
    // an assignee" stay the same sort of question.
    assertThat(task.isAssignee(MATE)).isTrue();
    assertThat(task.isAssignee("MATE@ORISENC.COM")).isTrue();
    assertThat(task.getAssignees()).singleElement()
        .satisfies(entry -> assertThat(entry.getUsername()).isEqualTo("Mate@Orisenc.com"));
  }

  @Test
  void removingIsCaseInsensitiveAndReportsWhetherItDidAnything() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    assertThat(task.removeAssignee(STRANGER)).isFalse();
    assertThat(task.removeAssignee("MATE@ORISENC.COM")).isTrue();
    assertThat(task.isAssignee(MATE)).isFalse();
  }

  /* ----------------------------------------------------------------------------- seeing it */

  @Test
  void aNamedAssigneeSeesAnIndividualItemTheyWouldOtherwiseNeverSee() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isFalse();
    task.addAssignee(MATE, LEAD, NOW);
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isTrue();
  }

  @Test
  void removingThemHidesItAgain() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isTrue();

    task.removeAssignee(MATE);
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isFalse();
  }

  @Test
  void beingNamedStillRequiresTheViewPermission() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    // A row in Workflow's database must not mint an authority Common Platform never granted.
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, Set.of())).isFalse();
  }

  @Test
  void namingOnePersonDoesNotRevealTheItemToAnother() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, VIEWER)).isFalse();
  }

  /* ------------------------------------------------------------------------ acting on it */

  @Test
  void aNamedAssigneeMayProgressTheItem() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, MATE, WORKER)).isTrue();
  }

  @Test
  void aNamedAssigneeWithoutExecuteMaySeeButNotAct() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    // The same separation the policy already makes for an all-teams item: seeing is not moving.
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isTrue();
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, MATE, VIEWER)).isFalse();
  }

  @Test
  void anAssigneeActsInTheirOwnRightAndNotOnAnyonesBehalf() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);

    assertThat(ProjectTaskVisibilityPolicy.actingFor(task, MATE, List.of())).isNull();
  }

  @Test
  void someoneWhoIsBothAssigneeAndDelegateIsRecordedAsActingForNobody() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.addAssignee(MATE, LEAD, NOW);
    var cover = List.of(new DelegationCover(OWNER, null, null));

    // They hold the item in their own right, so attributing the action to the delegator would put a
    // name on the history row that did not act. Assignee is checked before cover for this reason.
    assertThat(ProjectTaskVisibilityPolicy.actingFor(task, MATE, cover)).isNull();
  }

  /* ------------------------------------------------------- the list agrees with the record */

  @Test
  void theAudienceQueryCarriesTheSameGrantAsMayView() {
    // mayView and audience() are the same decision in two languages. If they drift, a task is
    // reachable by id but never appears in a list, or the other way round.
    var audience = ProjectTaskVisibilityPolicy.audience(VIEWER, MATE, List.of());

    assertThat(audience.jpql())
        .contains("ProjectTaskAssigneeEntity")
        .contains("a.username")
        .contains("exists");
    assertThat(audience.parameters()).containsKey("actor");
  }

  @Test
  void aManagerNeedsNoAssigneeClauseBecauseTheyAreUnrestricted() {
    assertThat(ProjectTaskVisibilityPolicy.audience(MANAGER, MATE, List.of()).unrestricted()).isTrue();
  }

  /* ------------------------------------------------------------- the boundary that matters */

  @Test
  void beingNamedOnATaskGrantsNothingAboutItsCustomer() {
    // TM-012 and TM-014, and policy note P-04: a broader task audience carries task metadata, status
    // and comments - never customer identity or customer fields. Customer access is a separate
    // entitlement, and this asserts the assignee grant did not quietly become one.
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    task.linkToMaster(7L, 42L, null);
    task.addAssignee(MATE, LEAD, NOW);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, MATE, VIEWER)).isTrue();

    // The policy answers task access only. It exposes no customer decision at all, which is what
    // keeps the two entitlements separate - there is no method here that could return "and they may
    // also see customer 42".
    assertThat(ProjectTaskPermissions.ASSIGNEES_MANAGE).isEqualTo("platform.work.assignees.manage");
    assertThat(Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.VIEW_TEAM,
        ProjectTaskPermissions.EXECUTE, ProjectTaskPermissions.MANAGE,
        ProjectTaskPermissions.ASSIGNEES_MANAGE))
        .noneMatch(code -> code.contains("customer"));
  }

  @Test
  void theManagePermissionIsNotWidenedByTheAssigneeGrant() {
    // Unlike sales.lead.collaborators.manage, holding the assignee-manage code grants no visibility
    // bypass of its own: platform.work.manage already fills that role, and two bypasses would be two
    // answers to one question.
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    var assigneeManagerOnly =
        Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.ASSIGNEES_MANAGE);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, assigneeManagerOnly)).isFalse();
  }
}
