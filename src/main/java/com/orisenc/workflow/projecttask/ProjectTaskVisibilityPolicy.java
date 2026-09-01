package com.orisenc.workflow.projecttask;

import java.util.Set;

/**
 * Decides who may see a work item, and who may act on it.
 *
 * <p>Deliberately the only place these two questions are answered, because the audit of the approval
 * task family found the opposite arrangement: a UI that displayed an audience model the API did not
 * enforce, with the list endpoint returning every row in the table to any holder of a view
 * permission. Everything that reads work items goes through {@link #mayView} so that list, detail,
 * search and child lookups cannot drift apart.
 *
 * <h2>Known limitation</h2>
 *
 * <p>{@link ProjectTaskVisibility#RELEVANT_TEAM} is currently enforced as "holds
 * {@link ProjectTaskPermissions#VIEW_TEAM}" rather than "is a member of the task's relevant team",
 * because no user record in the platform carries a team or department: neither the Entra token
 * (see {@code com.orisenc.security.CurrentUser}) nor Common Platform's {@code AppUserEntity} has
 * one, so there is nothing to match {@code relevantTeam} against. This is coarser than TM-009
 * requires - a team viewer sees every team's items, not just their own.
 *
 * <p>It is still a real boundary: INDIVIDUAL items stop leaking, which they did not before. When a
 * department lands on the user record, narrow {@link #mayView} by comparing it to
 * {@code relevantTeam} - that single method is the whole change.
 */
public final class ProjectTaskVisibilityPolicy {

  private ProjectTaskVisibilityPolicy() {}

  /**
   * Whether the actor may see the item at all.
   *
   * <p>Ownership and authorship always win: you can see what you are accountable for and what you
   * raised, whatever the audience says. Beyond that the audience decides, and the default is no.
   */
  public static boolean mayView(ProjectTaskEntity task, String actor, Set<String> permissions) {
    if (!permissions.contains(ProjectTaskPermissions.VIEW)) return false;
    if (isOwnerOrCreator(task, actor)) return true;
    return switch (task.getVisibility()) {
      case ALL_TEAMS -> true;
      case RELEVANT_TEAM -> permissions.contains(ProjectTaskPermissions.VIEW_TEAM)
          || permissions.contains(ProjectTaskPermissions.MANAGE);
      case INDIVIDUAL -> permissions.contains(ProjectTaskPermissions.MANAGE);
    };
  }

  /**
   * Whether the actor may change the item's state.
   *
   * <p>Being able to see a task never implies being able to move it: an all-teams item is readable
   * across the organization but only its owner progresses it. A manager may act on anything they can
   * see, which is what makes reassignment and unblocking possible without handing out ownership.
   */
  public static boolean mayAct(ProjectTaskEntity task, String actor, Set<String> permissions) {
    if (!mayView(task, actor, permissions)) return false;
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return true;
    return permissions.contains(ProjectTaskPermissions.EXECUTE) && isOwner(task, actor);
  }

  /**
   * Whether the actor may take an unowned item.
   *
   * <p>Claiming is the one action deliberately open to anyone who can see the item and execute work:
   * an unclaimed team queue that only its (absent) owner could pick up would not be a queue.
   */
  public static boolean mayClaim(ProjectTaskEntity task, String actor, Set<String> permissions) {
    if (!mayView(task, actor, permissions)) return false;
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return true;
    return permissions.contains(ProjectTaskPermissions.EXECUTE)
        && (task.getOwnerUserId() == null || isOwner(task, actor));
  }

  /**
   * The same decision as {@link #mayView}, expressed as a JPQL fragment over alias {@code t}.
   *
   * <p>It exists so the audience filter runs in the database instead of over an already-loaded list:
   * filtering afterwards means loading rows the caller may not see and reporting page sizes that do
   * not match what comes back. An empty string means "no restriction".
   *
   * <p>It lives beside {@link #mayView} because the two must agree, and the only way to keep them
   * agreeing is to make them impossible to read separately. Any change to one is a change to both.
   * Binds one parameter, {@code actor}.
   */
  public static String listPredicate(Set<String> permissions) {
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return "";
    var clauses = new StringBuilder("(lower(t.ownerUserId) = lower(:actor)")
        .append(" or lower(t.createdBy) = lower(:actor)")
        .append(" or t.visibility = com.orisenc.workflow.projecttask.ProjectTaskVisibility.ALL_TEAMS");
    if (permissions.contains(ProjectTaskPermissions.VIEW_TEAM))
      clauses.append(" or t.visibility = com.orisenc.workflow.projecttask.ProjectTaskVisibility.RELEVANT_TEAM");
    return clauses.append(")").toString();
  }

  private static boolean isOwner(ProjectTaskEntity task, String actor) {
    return task.getOwnerUserId() != null && task.getOwnerUserId().equalsIgnoreCase(actor);
  }

  private static boolean isOwnerOrCreator(ProjectTaskEntity task, String actor) {
    return isOwner(task, actor) || task.getCreatedBy().equalsIgnoreCase(actor);
  }
}
