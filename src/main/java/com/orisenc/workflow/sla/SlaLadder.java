package com.orisenc.workflow.sla;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Turns a deadline and a clock into a rung number.
 *
 * <p>Pure and static, with no persistence and no notion of a task, because this is the one piece of
 * SLA behaviour worth testing exhaustively: everything else in the pass is loading rows and posting
 * messages.
 *
 * <h2>The ladder</h2>
 *
 * <pre>
 *   level 0  nothing crossed
 *   level 1  DUE_SOON    now >= dueAt - dueSoonLead, and dueAt has not passed
 *   level 2  BREACHED    now >= dueAt
 *   level 3  ESCALATED   now >= dueAt + escalationSteps[0]
 *   level 4  ESCALATED   now >= dueAt + escalationSteps[1]   ... and so on
 * </pre>
 *
 * <p><b>Only the highest crossed level is ever raised.</b> An item three days past a deadline gets
 * the escalation, not a warning that it is due tomorrow followed by two more messages - and the
 * first pass over an existing table sends one message per neglected item rather than emptying four
 * rungs of backlog into somebody's inbox. This is the same rule, for the same reason, as the
 * overdue-invoice rungs in Accounts.
 */
public final class SlaLadder {

  /** The level below which nothing has been crossed. */
  public static final int NONE = 0;
  static final int DUE_SOON_LEVEL = 1;
  static final int BREACHED_LEVEL = 2;

  private SlaLadder() {}

  /**
   * The highest rung this deadline has crossed, or {@link #NONE}.
   *
   * @param dueAt the commitment; a null deadline is no commitment and crosses nothing
   */
  public static int levelAt(Instant dueAt, Instant now, SlaProperties properties) {
    if (dueAt == null || now == null) return NONE;

    if (now.isBefore(dueAt)) {
      // Not yet due. The only rung available is the warning, and only inside the lead window.
      return now.isBefore(dueAt.minus(properties.dueSoonLead())) ? NONE : DUE_SOON_LEVEL;
    }

    int level = BREACHED_LEVEL;
    List<Duration> steps = properties.escalationSteps();
    for (int index = 0; index < steps.size(); index++)
      if (!now.isBefore(dueAt.plus(steps.get(index)))) level = BREACHED_LEVEL + index + 1;
    return level;
  }

  /** What the level means. Levels above {@link #BREACHED_LEVEL} are all escalations. */
  public static SlaStage stageOf(int level) {
    if (level <= DUE_SOON_LEVEL) return SlaStage.DUE_SOON;
    return level == BREACHED_LEVEL ? SlaStage.BREACHED : SlaStage.ESCALATED;
  }

  /**
   * Which escalation step a level is, counting from 1. Zero for the two rungs that are not
   * escalations - used only to label the message, never to decide anything.
   */
  public static int escalationStep(int level) {
    return level <= BREACHED_LEVEL ? 0 : level - BREACHED_LEVEL;
  }

  /**
   * The earliest deadline that can be at rung 1 right now.
   *
   * <p>The scheduled pass loads only items due before this, which keeps a healthy backlog - work due
   * next month - out of every quarter-hourly scan.
   */
  public static Instant horizon(Instant now, SlaProperties properties) {
    return now.plus(properties.dueSoonLead());
  }
}
