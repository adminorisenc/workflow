package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.delegation.DelegationCover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>Named assignees</h2>
 *
 * <p>An item's owner is one person (TM-003), because the SLA ladder, delegation and claiming each
 * need a single answer to "who". People working it alongside the owner are named on the item
 * instead, and a named assignee both sees it - whatever its audience says - and may progress it,
 * still needing {@link ProjectTaskPermissions#EXECUTE} of their own.
 *
 * <p>Being named grants task metadata, status and comments and nothing else. Policy note P-04 draws
 * the same boundary for the audience enum, and TM-012/TM-014 make it critical: customer identity and
 * customer fields are a separate entitlement, never inferred from being on a task.
 *
 * <h2>Delegation</h2>
 *
 * <p>Every method takes the delegation cover currently handing work to the actor (REQ-0027). A
 * delegate sees and may act on what their delegator <em>owns</em>, narrowed by whatever the
 * delegation narrows to. Authorship is not covered: a delegation is cover for somebody's workload,
 * not access to everything they ever raised.
 *
 * <p>Claiming is deliberately untouched by it. Taking an unowned item off a team queue is your own
 * act, not one you perform as somebody else, and a delegate who wants it can claim it as themselves.
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
   * A {@code where} fragment over alias {@code t}, with every parameter it binds.
   *
   * <p>The two travel together because they must agree, and the only way to keep them agreeing is to
   * make them impossible to use separately. An earlier shape returned the fragment alone and left
   * the caller to bind {@code :actor} by hand, which worked only while there was exactly one
   * parameter to forget.
   *
   * @param jpql an empty string when the caller may see everything
   */
  public record Audience(String jpql, Map<String, Object> parameters) {

    static final Audience UNRESTRICTED = new Audience("", Map.of());

    public boolean unrestricted() {
      return jpql.isEmpty();
    }
  }

  /**
   * Whether the actor may see the item at all.
   *
   * <p>Ownership and authorship always win: you can see what you are accountable for and what you
   * raised, whatever the audience says. Beyond that the audience decides, and the default is no.
   */
  public static boolean mayView(ProjectTaskEntity task, String actor, Set<String> permissions) {
    return mayView(task, actor, permissions, List.of());
  }

  public static boolean mayView(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    if (!permissions.contains(ProjectTaskPermissions.VIEW)) return false;
    if (isOwnerOrCreator(task, actor)) return true;
    if (task.isAssignee(actor)) return true;
    if (coveredBy(task, cover)) return true;
    return switch (task.getVisibility()) {
      case ALL_TEAMS -> true;
      case RELEVANT_TEAM -> permissions.contains(ProjectTaskPermissions.VIEW_TEAM)
          || permissions.contains(ProjectTaskPermissions.MANAGE);
      case INDIVIDUAL -> permissions.contains(ProjectTaskPermissions.MANAGE);
    };
  }

  /**
   * Whether the actor has a connection to this task, rather than merely being allowed to look at it.
   *
   * <p>The distinction {@link ProjectTaskVisibility#ALL_TEAMS} needs and did not have. Seeing a task
   * because somebody published it to everyone is not the same as owning it, being assigned to it,
   * raising it, covering for its owner or managing the queue - and TM-014 draws the line in exactly
   * that place: a broad audience carries task metadata, status and safe comments, and nothing else.
   *
   * <p>Two things hang off it: the audit history, which is not any of those three, and the linked
   * customer, which TM-012 reserves to the people actually doing the work.
   */
  public static boolean isEntitled(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return true;
    return isOwnerOrCreator(task, actor) || task.isAssignee(actor) || coveredBy(task, cover);
  }

  /**
   * Whether the actor may change the item's state.
   *
   * <p>Being able to see a task never implies being able to move it: an all-teams item is readable
   * across the organization but only its owner progresses it. A manager may act on anything they can
   * see, which is what makes reassignment and unblocking possible without handing out ownership.
   *
   * <p>A delegate may act on what their delegator owns - and still needs
   * {@link ProjectTaskPermissions#EXECUTE} of their own. A delegation widens whose records you may
   * act on; it does not grant the permission to act. Common Platform is the RBAC store, and a row in
   * Workflow's own database must not be able to mint an authority the access service never gave.
   */
  public static boolean mayAct(ProjectTaskEntity task, String actor, Set<String> permissions) {
    return mayAct(task, actor, permissions, List.of());
  }

  public static boolean mayAct(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    if (!mayView(task, actor, permissions, cover)) return false;
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return true;
    if (!permissions.contains(ProjectTaskPermissions.EXECUTE)) return false;
    return isOwner(task, actor) || task.isAssignee(actor) || coveredBy(task, cover);
  }

  /**
   * Whether the actor may take an unowned item.
   *
   * <p>Claiming is the one action deliberately open to anyone who can see the item and execute work:
   * an unclaimed team queue that only its (absent) owner could pick up would not be a queue.
   *
   * <p>Delegation plays no part. Claiming is not something you do on somebody's behalf - the item
   * has no owner to stand in for - so a delegate claims as themselves and becomes the owner.
   */
  public static boolean mayClaim(ProjectTaskEntity task, String actor, Set<String> permissions) {
    return mayClaim(task, actor, permissions, List.of());
  }

  public static boolean mayClaim(ProjectTaskEntity task, String actor, Set<String> permissions,
      List<DelegationCover> cover) {
    if (!mayView(task, actor, permissions, cover)) return false;
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return true;
    return permissions.contains(ProjectTaskPermissions.EXECUTE)
        && (task.getOwnerUserId() == null || isOwner(task, actor));
  }

  /**
   * The person whose authority the actor would be using on this item, or null when it is their own.
   *
   * <p>What the history row records. Resolved here rather than in the service so that the answer to
   * "may they" and the answer to "as whom" come from the same rule - two places would eventually
   * produce a permitted action attributed to nobody.
   */
  public static String actingFor(ProjectTaskEntity task, String actor, List<DelegationCover> cover) {
    if (isOwner(task, actor)) return null;
    // A named assignee holds the item in their own right, so there is nobody to attribute to. Checked
    // before the cover lookup: someone who is both an assignee and a delegate is acting as an
    // assignee, and recording a delegator would put a name on the history row that did not act.
    if (task.isAssignee(actor)) return null;
    return cover.stream()
        .filter(entry -> entry.isFor(task.getOwnerUserId()))
        .filter(entry -> entry.covers(task.getTaskType().name(), task.getRelevantTeam()))
        .map(DelegationCover::delegator)
        .findFirst().orElse(null);
  }

  /**
   * The same decision as {@link #mayView}, expressed for the database.
   *
   * <p>It exists so the audience filter runs in the database instead of over an already-loaded list:
   * filtering afterwards means loading rows the caller may not see and reporting page sizes that do
   * not match what comes back.
   *
   * <p>It lives beside {@link #mayView} because the two must agree, and the only way to keep them
   * agreeing is to make them impossible to read separately. Any change to one is a change to both.
   */
  public static Audience audience(Set<String> permissions, String actor, List<DelegationCover> cover) {
    if (permissions.contains(ProjectTaskPermissions.MANAGE)) return Audience.UNRESTRICTED;

    var parameters = new LinkedHashMap<String, Object>();
    var clauses = new ArrayList<String>();
    clauses.add("lower(t.ownerUserId) = :actor");
    clauses.add("lower(t.createdBy) = :actor");
    // The database half of `task.isAssignee(actor)` in mayView. An exists subquery rather than a join
    // so an item with several assignees is still one row in the result.
    clauses.add("exists (select 1 from ProjectTaskAssigneeEntity a"
        + " where a.task = t and lower(a.username) = :actor)");
    clauses.add("t.visibility = com.orisenc.workflow.projecttask.ProjectTaskVisibility.ALL_TEAMS");
    parameters.put("actor", actor == null ? "" : actor.toLowerCase(java.util.Locale.ROOT));
    if (permissions.contains(ProjectTaskPermissions.VIEW_TEAM))
      clauses.add("t.visibility = com.orisenc.workflow.projecttask.ProjectTaskVisibility.RELEVANT_TEAM");

    // One clause per delegation rather than a single "owner in (...)": each carries its own type and
    // department narrowing, so collapsing them would hand a delegate covering one team every item
    // their delegator owns anywhere. A person holds one or two of these, so the clause stays small.
    for (int index = 0; index < cover.size(); index++) {
      var entry = cover.get(index);
      var clause = new StringBuilder("(lower(t.ownerUserId) = :dlg").append(index);
      parameters.put("dlg" + index, entry.delegatorKey());
      if (entry.taskTypeKey() != null) {
        clause.append(" and t.taskType = :dlgType").append(index);
        parameters.put("dlgType" + index, ProjectTaskType.valueOf(entry.taskTypeKey()));
      }
      if (entry.departmentKey() != null) {
        clause.append(" and lower(t.relevantTeam) = :dlgTeam").append(index);
        parameters.put("dlgTeam" + index, entry.departmentKey());
      }
      clauses.add(clause.append(")").toString());
    }
    return new Audience("(" + String.join(" or ", clauses) + ")", Map.copyOf(parameters));
  }

  /** True when one of the actor's delegations reaches this item. */
  private static boolean coveredBy(ProjectTaskEntity task, List<DelegationCover> cover) {
    return cover.stream()
        .anyMatch(entry -> entry.isFor(task.getOwnerUserId())
            && entry.covers(task.getTaskType().name(), task.getRelevantTeam()));
  }

  private static boolean isOwner(ProjectTaskEntity task, String actor) {
    return task.getOwnerUserId() != null && task.getOwnerUserId().equalsIgnoreCase(actor);
  }

  private static boolean isOwnerOrCreator(ProjectTaskEntity task, String actor) {
    return isOwner(task, actor) || task.getCreatedBy().equalsIgnoreCase(actor);
  }
}
