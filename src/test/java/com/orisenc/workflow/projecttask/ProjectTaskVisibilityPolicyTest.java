package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;

import com.orisenc.workflow.task.TaskPriority;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The audience rules, which are the part of this rework that has to be right.
 *
 * <p>The approval task family shipped a UI that showed a visibility model its API did not enforce.
 * These tests exist so the same thing cannot happen here quietly.
 */
class ProjectTaskVisibilityPolicyTest {

  private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");
  private static final String OWNER = "owner@orisenc.com";
  private static final String CREATOR = "creator@orisenc.com";
  private static final String STRANGER = "stranger@orisenc.com";

  private static final Set<String> VIEWER = Set.of(ProjectTaskPermissions.VIEW);
  private static final Set<String> WORKER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.EXECUTE);
  private static final Set<String> TEAM_VIEWER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.VIEW_TEAM);
  private static final Set<String> MANAGER =
      Set.of(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.MANAGE);

  private ProjectTaskEntity task(ProjectTaskVisibility visibility, String owner) {
    return new ProjectTaskEntity("WRK-1", "t", "d", ProjectTaskType.DELIVERABLE, TaskPriority.MEDIUM,
        visibility, "Operations", owner, CREATOR, LinkedEntityType.NONE, null, null, null,
        NOW, NOW.plus(1, ChronoUnit.DAYS));
  }

  @Test
  void withoutTheViewPermissionNothingIsVisible() {
    var task = task(ProjectTaskVisibility.ALL_TEAMS, OWNER);
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, OWNER, Set.of())).isFalse();
  }

  @Test
  void anIndividualItemIsHiddenFromEveryoneButItsOwnerCreatorAndManagers() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, OWNER, VIEWER)).isTrue();
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, CREATOR, VIEWER)).isTrue();
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, VIEWER)).isFalse();
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, TEAM_VIEWER)).isFalse();
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, MANAGER)).isTrue();
  }

  @Test
  void aRelevantTeamItemNeedsTheTeamViewPermission() {
    var task = task(ProjectTaskVisibility.RELEVANT_TEAM, OWNER);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, VIEWER)).isFalse();
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, TEAM_VIEWER)).isTrue();
  }

  @Test
  void anAllTeamsItemIsVisibleToAnyViewer() {
    var task = task(ProjectTaskVisibility.ALL_TEAMS, OWNER);
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, VIEWER)).isTrue();
  }

  @Test
  void seeingAnItemDoesNotMeanBeingAbleToMoveIt() {
    var task = task(ProjectTaskVisibility.ALL_TEAMS, OWNER);

    assertThat(ProjectTaskVisibilityPolicy.mayView(task, STRANGER, WORKER)).isTrue();
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, STRANGER, WORKER)).isFalse();
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, OWNER, WORKER)).isTrue();
  }

  @Test
  void actingRequiresTheExecutePermissionEvenForTheOwner() {
    var task = task(ProjectTaskVisibility.ALL_TEAMS, OWNER);
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, OWNER, VIEWER)).isFalse();
  }

  @Test
  void aManagerMayActOnAnythingTheyCanSee() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, OWNER);
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, STRANGER, MANAGER)).isTrue();
  }

  @Test
  void anUnownedItemMayBeClaimedByAnyoneWhoCanSeeAndExecuteIt() {
    var unowned = task(ProjectTaskVisibility.ALL_TEAMS, null);
    assertThat(ProjectTaskVisibilityPolicy.mayClaim(unowned, STRANGER, WORKER)).isTrue();
    // Without execute there is nothing to claim it for.
    assertThat(ProjectTaskVisibilityPolicy.mayClaim(unowned, STRANGER, VIEWER)).isFalse();
  }

  @Test
  void anItemSomeoneElseOwnsCannotBeTakenWithoutTheManagePermission() {
    var owned = task(ProjectTaskVisibility.ALL_TEAMS, OWNER);
    assertThat(ProjectTaskVisibilityPolicy.mayClaim(owned, STRANGER, WORKER)).isFalse();
    assertThat(ProjectTaskVisibilityPolicy.mayClaim(owned, STRANGER, MANAGER)).isTrue();
  }

  @Test
  void ownershipComparisonIgnoresCase() {
    var task = task(ProjectTaskVisibility.INDIVIDUAL, "Owner@Orisenc.com");
    assertThat(ProjectTaskVisibilityPolicy.mayView(task, "owner@orisenc.com", VIEWER)).isTrue();
    assertThat(ProjectTaskVisibilityPolicy.mayAct(task, "OWNER@ORISENC.COM", WORKER)).isTrue();
  }

  @Test
  void theListPredicateMatchesTheSingleRecordRule() {
    // A manager is unrestricted, so the query carries no audience clause at all.
    assertThat(ProjectTaskVisibilityPolicy.listPredicate(MANAGER)).isEmpty();

    // Everyone else is narrowed to their own work plus what their audience permissions allow. The
    // clause and mayView have to agree; this pins the shape that makes them agree.
    assertThat(ProjectTaskVisibilityPolicy.listPredicate(VIEWER))
        .contains("t.ownerUserId").contains("t.createdBy").contains("ALL_TEAMS")
        .doesNotContain("RELEVANT_TEAM");
    assertThat(ProjectTaskVisibilityPolicy.listPredicate(TEAM_VIEWER)).contains("RELEVANT_TEAM");
  }
}
