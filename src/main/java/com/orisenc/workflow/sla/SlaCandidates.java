package com.orisenc.workflow.sla;

import com.orisenc.workflow.projecttask.ProjectTaskEntity;
import com.orisenc.workflow.projecttask.ProjectTaskStatus;
import com.orisenc.workflow.task.TaskEntity;
import com.orisenc.workflow.task.TaskStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The database half of the SLA pass: which items are worth looking at, and stamping the ones that
 * crossed a rung.
 *
 * <p>Separate from {@link SlaEscalationService} for two reasons. The first is transactional: raising
 * a notification is an HTTP call to another service, and it must not happen inside an open write
 * transaction - so the pass reads here, posts outside, then comes back to write. The second is that
 * it makes the escalation logic testable without a database, which is the only way the ladder gets
 * the coverage it deserves.
 */
@Component
public class SlaCandidates {

  private static final List<ProjectTaskStatus> CLOSED_WORK =
      List.of(ProjectTaskStatus.COMPLETED, ProjectTaskStatus.CANCELLED);
  private static final List<TaskStatus> OPEN_APPROVALS =
      List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);

  private final EntityManager entityManager;

  public SlaCandidates(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * Open work items whose deadline is near enough to matter, soonest first.
   *
   * <p>The self-join reads the parent item's owner in the same query: a stage of a fulfilment chain
   * escalates to whoever holds the order, and fetching that one row per candidate afterwards would
   * turn one query into several hundred.
   *
   * <p>Archived items are excluded here rather than filtered later - an archived item is out of the
   * business, and continuing to chase somebody about it is exactly the noise that makes people stop
   * reading these messages.
   */
  @Transactional(readOnly = true)
  public List<SlaCandidate> openWorkItems(Instant horizon, int limit) {
    return entityManager.createQuery(
            "select t.id, t.title, t.taskType, t.dueAt, t.ownerUserId, t.createdBy, t.relevantTeam,"
                + " p.ownerUserId, t.escalationLevel"
                + " from ProjectTaskEntity t"
                + " left join ProjectTaskEntity p on p.id = t.parentTaskId"
                + " where t.archivedAt is null and t.status not in :closed and t.dueAt < :horizon"
                + " order by t.dueAt asc, t.id asc", Object[].class)
        .setParameter("closed", CLOSED_WORK)
        .setParameter("horizon", horizon)
        .setMaxResults(limit)
        .getResultList().stream()
        .map(row -> new SlaCandidate(SlaFamily.WORK_ITEM, (String) row[0], (String) row[1],
            String.valueOf(row[2]), (Instant) row[3], (String) row[4], (String) row[5],
            (String) row[6], (String) row[7], ((Number) row[8]).intValue()))
        .toList();
  }

  /**
   * Open approvals whose deadline is near enough to matter, soonest first.
   *
   * <p>An approval has no parent, so its only escalation contact is the configured mailbox. That is
   * the same missing reporting line that keeps {@code RELEVANT_TEAM} coarse, showing up again.
   */
  @Transactional(readOnly = true)
  public List<SlaCandidate> openApprovals(Instant horizon, int limit) {
    return entityManager.createQuery(
            "select t.id, t.title, t.type, t.dueAt, t.assignee, t.requester, t.department,"
                + " t.escalationLevel"
                + " from TaskEntity t"
                + " where t.status in :open and t.dueAt < :horizon"
                + " order by t.dueAt asc, t.id asc", Object[].class)
        .setParameter("open", OPEN_APPROVALS)
        .setParameter("horizon", horizon)
        .setMaxResults(limit)
        .getResultList().stream()
        .map(row -> new SlaCandidate(SlaFamily.APPROVAL, (String) row[0], (String) row[1],
            String.valueOf(row[2]), (Instant) row[3], (String) row[4], (String) row[5],
            (String) row[6], null, ((Number) row[7]).intValue()))
        .toList();
  }

  /**
   * Writes the rung onto the item and appends the history row that explains it.
   *
   * <p>Re-reads the row inside its own transaction and refuses a level that is not above the stored
   * one, so two passes overlapping - a slow run still going when the next one starts - cannot record
   * the same escalation twice. The entity refuses it; this only reports the refusal.
   *
   * <p>Bumping the row's {@code @Version} is deliberate. A client holding a pre-escalation snapshot
   * genuinely is stale - the escalation level is part of the item's state and is served in its
   * detail - so the optimistic-lock conflict a mid-edit user then gets is correct rather than
   * spurious: they reload and see that the item was escalated while they were typing.
   *
   * @return true when the rung was recorded, false when the item vanished or had already reached it
   */
  @Transactional
  public boolean recordEscalation(SlaFamily family, String id, int level, String note, Instant time,
      String correlationId) {
    try {
      if (family == SlaFamily.WORK_ITEM) {
        var task = entityManager.find(ProjectTaskEntity.class, id);
        if (task == null) return false;
        task.recordEscalation(level, note, time, correlationId);
      } else {
        var task = entityManager.find(TaskEntity.class, id);
        if (task == null) return false;
        task.recordEscalation(level, note, time, correlationId);
      }
      entityManager.flush();
      return true;
    } catch (IllegalArgumentException alreadyThere) {
      return false;
    }
  }
}
