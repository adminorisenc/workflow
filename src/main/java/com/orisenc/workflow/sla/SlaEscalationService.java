package com.orisenc.workflow.sla;

import com.orisenc.workflow.delegation.DelegationScope;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.delegation.DelegationTarget;
import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.Channel;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Watches deadlines and escalates the ones that pass (REQ-0022, the SLA half).
 *
 * <p>Both task families go through one pass: an approval waiting on a decision and a delivery
 * waiting on a driver are the same problem seen twice, and two ladders would drift apart.
 *
 * <h2>What it will not do</h2>
 *
 * <p><b>It sends nothing.</b> Workflow does not talk to a mail server; it raises an intention with
 * the Integration Service and returns. Delivery, retries and the record of who was told live in the
 * one service that makes third-party calls.
 *
 * <p><b>It escalates nothing when there is no route.</b> With no Integration client configured the
 * pass records that it could not run rather than stamping escalation levels nobody was told about -
 * an escalation is somebody being told, and a level written to a row in silence would make the item
 * look chased when it had not been.
 *
 * <p><b>It raises before it stamps.</b> If the raise succeeds and the stamp fails, the next pass
 * raises again and the Integration Service's idempotency drops the duplicate. Stamping first would
 * mean a failed raise silently consumed the rung and nobody would ever hear about it. The two
 * guards are complementary: the stored level keeps the quarter-hourly scan quiet, and the delivery
 * log covers the window between the post and the write.
 */
@Service
public class SlaEscalationService {

  private static final Logger log = LoggerFactory.getLogger(SlaEscalationService.class);

  /** Templates the Integration Service renders. Their wording and version live there, not here. */
  static final String TEMPLATE_DUE_SOON = "TASK_SLA_DUE_SOON";
  static final String TEMPLATE_BREACHED = "TASK_SLA_BREACHED";
  static final String TEMPLATE_ESCALATED = "TASK_SLA_ESCALATED";

  private final SlaCandidates candidates;
  private final SlaProperties properties;
  private final DelegationService delegations;
  private final Supplier<IntegrationServiceClient> integration;
  private final Clock clock;

  // Two constructors: this one for Spring, the package-private one below for tests that need a
  // fixed Clock and a stub client. Without @Autowired Spring cannot choose between them - see
  // SpringBeanConstructorTest, which exists because exactly that shipped once.
  @Autowired
  public SlaEscalationService(SlaCandidates candidates, SlaProperties properties,
      DelegationService delegations, ObjectProvider<IntegrationServiceClient> integration) {
    // Resolved per run rather than at construction, so the client's own configuration condition
    // decides whether a route exists without this bean depending on startup ordering.
    this(candidates, properties, delegations, integration::getIfAvailable, Clock.systemUTC());
  }

  SlaEscalationService(SlaCandidates candidates, SlaProperties properties,
      DelegationService delegations, Supplier<IntegrationServiceClient> integration, Clock clock) {
    this.candidates = candidates;
    this.properties = properties;
    this.delegations = delegations;
    this.integration = integration;
    this.clock = clock;
  }

  /**
   * What one pass did.
   *
   * @param examined items whose deadline was near enough to look at
   * @param escalated items that crossed a rung they had not crossed before
   * @param notified individual messages queued - one per recipient, so higher than {@code escalated}
   * @param failed items whose messages could not be raised, and which keep their old rung
   * @param routeAvailable false when no Integration client is configured, in which case nothing ran
   */
  public record SlaRun(int examined, int escalated, int notified, int failed, boolean routeAvailable) {}

  /**
   * Runs on an interval, not at an hour.
   *
   * <p>Unlike an overdue-invoice reminder, which is a message to a customer and belongs in their
   * morning, this one tells a colleague that something they are accountable for is about to slip. A
   * deadline missed at 09:05 and announced at midnight is a report, not an escalation.
   */
  @Scheduled(cron = "${orisenc.workflow.sla.cron:0 */15 * * * *}",
      zone = "${orisenc.workflow.sla.zone:Asia/Kolkata}")
  public void scheduledRun() {
    if (!properties.enabled()) return;
    var result = runOnce();
    if (!result.routeAvailable()) {
      log.debug("SLA pass skipped: no Integration client is configured, so nobody could be told.");
      return;
    }
    if (result.escalated() > 0 || result.failed() > 0)
      log.info("SLA pass: {} examined, {} escalated, {} message(s) queued, {} failed.",
          result.examined(), result.escalated(), result.notified(), result.failed());
  }

  /**
   * One pass over everything with a deadline in reach.
   *
   * <p>Not transactional. Each item is read, posted about and stamped independently, so one
   * unreachable moment does not roll back the escalations that did get through, and no HTTP call
   * ever happens with a write transaction open.
   */
  public SlaRun runOnce() {
    var client = integration.get();
    if (client == null) return new SlaRun(0, 0, 0, 0, false);

    Instant now = clock.instant();
    Instant horizon = SlaLadder.horizon(now, properties);
    int limit = properties.batchSize();

    var open = new ArrayList<SlaCandidate>(candidates.openWorkItems(horizon, limit));
    open.addAll(candidates.openApprovals(horizon, limit));

    int escalated = 0;
    int notified = 0;
    int failed = 0;

    for (SlaCandidate candidate : open) {
      int level = SlaLadder.levelAt(candidate.dueAt(), now, properties);
      // Nothing new. The overwhelming majority of every pass lands here, which is the point of
      // storing the rung rather than recomputing whether the item is late.
      if (level <= candidate.level()) continue;

      SlaStage stage = SlaLadder.stageOf(level);
      // Whoever is standing in for the owner hears whatever the owner hears. Resolved per candidate
      // rather than once per pass because the answer depends on this item's type and team, and a
      // delegation narrowed to one of those must not widen to all of them here.
      var delegates = delegations.delegatesOf(candidate.owner(), new DelegationTarget(
          candidate.family() == SlaFamily.APPROVAL
              ? DelegationScope.APPROVALS : DelegationScope.WORK_ITEMS,
          candidate.taskType(), candidate.team()));
      var recipients = SlaRecipients.forStage(candidate, stage, properties.escalationContact(),
          delegates);
      if (recipients.isEmpty()) {
        // An item with neither owner nor creator should not exist - the creator column is not
        // nullable - but stamping a rung nobody heard about is the one outcome worth refusing.
        log.warn("SLA rung {} on {} has no recipient; leaving it at level {}.", level, candidate.id(),
            candidate.level());
        continue;
      }

      int queued = raiseAll(client, candidate, stage, level, now, recipients);
      if (queued == 0) {
        failed++;
        continue;
      }

      notified += queued;
      if (candidates.recordEscalation(candidate.family(), candidate.id(), level,
          note(stage, level, recipients), now, correlationId(candidate, level)))
        escalated++;
    }
    return new SlaRun(open.size(), escalated, notified, failed, true);
  }

  /**
   * Posts one message per recipient and returns how many were accepted.
   *
   * <p>A recipient the Integration Service refuses does not stop the rest: telling three of four
   * people beats telling none. The item keeps its old rung only when nobody at all could be reached,
   * so the next pass tries the whole set again and the idempotency there drops what already landed.
   */
  private int raiseAll(IntegrationServiceClient client, SlaCandidate candidate, SlaStage stage,
      int level, Instant now, List<String> recipients) {
    int queued = 0;
    for (String recipient : recipients) {
      try {
        client.raiseNotification(new NotificationRequest(eventType(candidate.family(), stage, level),
            candidate.id(), Channel.EMAIL, recipient, template(stage),
            values(candidate, stage, level, now), null, null, stage.critical(),
            correlationId(candidate, level)));
        queued++;
      } catch (RuntimeException refused) {
        log.warn("Could not raise the SLA {} for {} to {}: {}", stage, candidate.id(), recipient,
            refused.getClass().getSimpleName());
      }
    }
    return queued;
  }

  /**
   * The rung is part of the event type, not a field beside it.
   *
   * <p>That is what makes the Integration Service's idempotency do the right thing: the same item at
   * the same rung is one notification however often this runs, and the next rung is genuinely a new
   * one.
   */
  static String eventType(SlaFamily family, SlaStage stage, int level) {
    return switch (stage) {
      case DUE_SOON -> family.eventPrefix() + "_DUE_SOON";
      case BREACHED -> family.eventPrefix() + "_SLA_BREACHED";
      case ESCALATED -> family.eventPrefix() + "_ESCALATED_" + SlaLadder.escalationStep(level);
    };
  }

  private static String template(SlaStage stage) {
    return switch (stage) {
      case DUE_SOON -> TEMPLATE_DUE_SOON;
      case BREACHED -> TEMPLATE_BREACHED;
      case ESCALATED -> TEMPLATE_ESCALATED;
    };
  }

  private static String correlationId(SlaCandidate candidate, int level) {
    return "sla-" + candidate.id().toLowerCase(Locale.ROOT) + "-L" + level;
  }

  private String note(SlaStage stage, int level, List<String> recipients) {
    String told = String.join(", ", recipients);
    return switch (stage) {
      case DUE_SOON -> "Due soon; warned " + told + ".";
      case BREACHED -> "Past its due date; told " + told + ".";
      case ESCALATED -> "Escalation " + SlaLadder.escalationStep(level) + "; told " + told + ".";
    };
  }

  /**
   * Every value the three templates can ask for, supplied whichever one is being rendered.
   *
   * <p>The Integration Service refuses to render a message with an unresolved placeholder - a
   * deliberate choice there, so nobody receives "work item &nbsp; is overdue" - which makes this map
   * a contract with the seeded template text. Supplying the full set rather than a per-stage subset
   * means adding a placeholder to one of those templates cannot turn every escalation into a dead
   * letter.
   */
  Map<String, String> values(SlaCandidate candidate, SlaStage stage, int level, Instant now) {
    var values = new LinkedHashMap<String, String>();
    values.put("kind", candidate.family().label());
    values.put("taskId", candidate.id());
    values.put("title", candidate.title());
    values.put("owner", candidate.owner() == null ? "nobody - it is unclaimed" : candidate.owner());
    values.put("team", candidate.team() == null ? "unassigned" : candidate.team());
    values.put("dueAt", readableTime(candidate.dueAt()));
    values.put("dueIn", readableDuration(Duration.between(now, candidate.dueAt())));
    values.put("overdueFor", readableDuration(Duration.between(candidate.dueAt(), now)));
    values.put("step", Integer.toString(Math.max(SlaLadder.escalationStep(level), 1)));
    return values;
  }

  private String readableTime(Instant instant) {
    return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
        .withZone(ZoneId.of(properties.zone())).format(instant) + " (" + properties.zone() + ")";
  }

  /**
   * A span a person reads, rounded down to the two units that matter.
   *
   * <p>"2 days, 6 hours" rather than PT54H: this ends up in a sentence somebody skims on a phone.
   */
  static String readableDuration(Duration duration) {
    Duration span = duration.isNegative() ? duration.negated() : duration;
    long days = span.toDays();
    long hours = span.toHoursPart();
    long minutes = span.toMinutesPart();
    if (days > 0) return plural(days, "day") + (hours > 0 ? ", " + plural(hours, "hour") : "");
    if (hours > 0) return plural(hours, "hour") + (minutes > 0 ? ", " + plural(minutes, "minute") : "");
    return plural(Math.max(minutes, 1), "minute");
  }

  private static String plural(long count, String unit) {
    return count + " " + unit + (count == 1 ? "" : "s");
  }
}
