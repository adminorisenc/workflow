package com.orisenc.workflow.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The service-level ladder: how early a deadline is announced, and how long after it passes before
 * the item goes over somebody's head.
 *
 * <p>Configuration rather than constants because a service level is a business promise, not a
 * property of this code. Until {@code REQ-0030}'s admin panel exists there is nowhere to edit one
 * per department or per task type, so these are the platform-wide defaults and a deployment
 * override is the only way to change them - a limitation shared with every other configurable
 * threshold on the platform today.
 *
 * @param enabled whether the scheduled pass runs at all
 * @param cron when it runs; quarter-hourly by default, because a deadline missed at 09:05 that is
 *     announced at midnight is not an escalation
 * @param zone the timezone the cron is read in
 * @param dueSoonLead how long before {@code dueAt} the owner is warned
 * @param escalationSteps offsets <em>after</em> {@code dueAt} at which the item goes up a level;
 *     each entry is one further rung, and only the highest crossed is ever raised
 * @param escalationContact a mailbox that receives every escalation. The honest stand-in for "the
 *     owner's manager", which cannot be resolved: no user record on the platform carries a manager
 *     or a department - the same gap that keeps {@code RELEVANT_TEAM} coarse. Optional; blank means
 *     escalations reach the creator and the parent item's owner only.
 * @param batchSize the most items one pass will look at, so a neglected backlog cannot turn a
 *     scheduled job into a table scan that outlives its own interval
 */
@ConfigurationProperties("orisenc.workflow.sla")
public record SlaProperties(
    boolean enabled,
    String cron,
    String zone,
    Duration dueSoonLead,
    List<Duration> escalationSteps,
    String escalationContact,
    int batchSize) {

  public SlaProperties {
    cron = blankToDefault(cron, "0 */15 * * * *");
    zone = blankToDefault(zone, "Asia/Kolkata");
    dueSoonLead = dueSoonLead == null || dueSoonLead.isNegative() ? Duration.ofHours(24) : dueSoonLead;
    escalationSteps = normalise(escalationSteps);
    escalationContact = escalationContact == null || escalationContact.isBlank()
        ? null : escalationContact.trim();
    batchSize = batchSize < 1 ? 500 : Math.min(batchSize, 5000);
  }

  /**
   * Sorted, positive and distinct, so the highest-crossed rule holds whatever an operator writes.
   *
   * <p>An unsorted list would make level numbers meaningless: the stored level is an index into this
   * list, and an item escalated to level 3 must always mean "further along" than level 2.
   */
  private static List<Duration> normalise(List<Duration> configured) {
    if (configured == null || configured.isEmpty())
      return List.of(Duration.ofHours(24), Duration.ofHours(72));
    var steps = new ArrayList<Duration>();
    for (Duration step : configured)
      if (step != null && !step.isNegative() && !step.isZero() && !steps.contains(step)) steps.add(step);
    steps.sort(null);
    return List.copyOf(steps);
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
