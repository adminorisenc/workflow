package com.orisenc.workflow.delegation;

/**
 * The piece of work a delegation is being tested against.
 *
 * <p>Exists so the matching rule is written once over three fields rather than twice over two
 * different entities. The workbook's acceptance criterion for REQ-0027 reads: "effective assignee is
 * resolved using active delegation rules, task type, department and validity period" - this record
 * is the first three of those, and {@code DelegationEntity.inForce} is the fourth.
 *
 * <p>Deliberately holds no reference to either task entity. The task services build one from their
 * own record and pass it in; if this package imported theirs, the two would depend on each other in
 * both directions for no gain.
 *
 * @param family which task family the work belongs to; never {@link DelegationScope#ALL}, which is a
 *     property of a delegation rather than of a task
 * @param taskType the enum name of the task's own type, from whichever family's type enum applies
 * @param department the approval's department, or the work item's relevant team. Free text on both
 *     sides today: no user record on the platform carries a department, which is the same gap that
 *     keeps {@code RELEVANT_TEAM} visibility coarse. A delegation narrowed by department therefore
 *     matches a string a person typed, not a directory value.
 */
public record DelegationTarget(DelegationScope family, String taskType, String department) {

  public static DelegationTarget approval(String taskType, String department) {
    return new DelegationTarget(DelegationScope.APPROVALS, taskType, department);
  }

  public static DelegationTarget workItem(String taskType, String relevantTeam) {
    return new DelegationTarget(DelegationScope.WORK_ITEMS, taskType, relevantTeam);
  }
}
