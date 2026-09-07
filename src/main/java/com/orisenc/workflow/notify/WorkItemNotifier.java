package com.orisenc.workflow.notify;

import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.Channel;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import com.orisenc.workflow.notify.WorkItemNotification.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tells people that work moved (REQ-0023, the emission half of REQ-0022).
 *
 * <p>Runs after the transaction commits, never inside it. An assignment message that arrives for a
 * change the database then rolled back is worse than no message, and an HTTP call made while a write
 * transaction is open charges a mail queue's latency to a database lock.
 *
 * <p>Failure here is logged and dropped rather than propagated. By the time this runs the work has
 * already been committed and the caller has already been answered; throwing would either be
 * invisible or - worse - surface as a failure of an operation that actually succeeded. This is the
 * one place in the service where losing a message is the right trade, and it is bounded: the
 * Integration Service's delivery log is the record of what was raised, and the SLA pass chases
 * anything that then goes quiet.
 */
@Component
public class WorkItemNotifier {

  private static final Logger log = LoggerFactory.getLogger(WorkItemNotifier.class);

  /** Templates the Integration Service renders. Their wording and version live there, not here. */
  static final String TEMPLATE_ASSIGNED = "TASK_ASSIGNED";
  static final String TEMPLATE_STATUS_CHANGED = "TASK_STATUS_CHANGED";

  private final Supplier<IntegrationServiceClient> integration;
  private final String zone;

  @Autowired
  public WorkItemNotifier(ObjectProvider<IntegrationServiceClient> integration,
      @Value("${orisenc.workflow.sla.zone:Asia/Kolkata}") String zone) {
    // Resolved per event rather than at construction, so whether a route exists is the client's own
    // configuration condition rather than this bean's startup ordering.
    this(integration::getIfAvailable, zone);
  }

  WorkItemNotifier(Supplier<IntegrationServiceClient> integration, String zone) {
    this.integration = integration;
    this.zone = zone;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(WorkItemNotification event) {
    raise(event);
  }

  /**
   * Posts one message per recipient, and returns how many were queued.
   *
   * <p>Package-visible and returning a count so a test can assert what would be sent without a
   * transaction manager in the way.
   */
  int raise(WorkItemNotification event) {
    var client = integration.get();
    if (client == null) return 0;

    var recipients = recipientsFor(event);
    if (recipients.isEmpty()) return 0;

    int queued = 0;
    for (String recipient : recipients) {
      try {
        client.raiseNotification(new NotificationRequest(eventType(event), event.eventRef(),
            Channel.EMAIL, recipient, template(event.kind()), values(event), null, null,
            // Critical: work handed to you, and work you raised changing under you, are both things
            // an opt-out from routine mail should not be able to silence.
            Boolean.TRUE, correlationId(event)));
        queued++;
      } catch (RuntimeException refused) {
        log.warn("Could not raise the {} notification for {} to {}: {}", event.kind(), event.taskId(),
            recipient, refused.getClass().getSimpleName());
      }
    }
    return queued;
  }

  /**
   * Who hears about it.
   *
   * <pre>
   *   ASSIGNED         the new owner
   *   STATUS_CHANGED   the owner and whoever raised the task
   * </pre>
   *
   * <p>The actor is always removed, and the list is deduplicated case-insensitively - these are UPNs,
   * compared case-insensitively everywhere else on the platform. Without that, somebody who raised a
   * task, claimed it and then moved it would get two copies of their own status change, and the
   * Integration Service's idempotency would not catch it because its key uses the recipient exactly
   * as written.
   */
  static List<String> recipientsFor(WorkItemNotification event) {
    // Built by hand rather than with List.of, which rejects a null element - and an unclaimed task
    // has a null owner, which is the ordinary case rather than the exceptional one.
    var wanted = new ArrayList<String>();
    wanted.add(event.owner());
    if (event.kind() != Kind.ASSIGNED) wanted.add(event.creator());

    var seen = new LinkedHashSet<String>();
    var recipients = new ArrayList<String>();
    for (String candidate : wanted) {
      if (candidate == null || candidate.isBlank()) continue;
      String trimmed = candidate.trim();
      if (event.actor() != null && trimmed.equalsIgnoreCase(event.actor().trim())) continue;
      if (seen.add(trimmed.toLowerCase(Locale.ROOT))) recipients.add(trimmed);
    }
    return List.copyOf(recipients);
  }

  /** The family prefix keeps an approval's delivery log separable from a work item's. */
  static String eventType(WorkItemNotification event) {
    return event.family().eventPrefix() + "_" + event.kind().name();
  }

  private static String template(Kind kind) {
    return kind == Kind.ASSIGNED ? TEMPLATE_ASSIGNED : TEMPLATE_STATUS_CHANGED;
  }

  private static String correlationId(WorkItemNotification event) {
    return event.kind().name().toLowerCase(Locale.ROOT) + "-" + event.eventRef().toLowerCase(Locale.ROOT);
  }

  /**
   * Every value the two templates can ask for, supplied whichever one is being rendered - the same
   * contract, and for the same reason, as the SLA pass: the Integration Service refuses to render a
   * message with an unresolved placeholder, so a per-kind subset would make adding a placeholder to
   * a template a way to turn every message into a dead letter.
   */
  Map<String, String> values(WorkItemNotification event) {
    var values = new LinkedHashMap<String, String>();
    values.put("kind", event.family().label());
    values.put("taskId", event.taskId());
    values.put("title", event.title());
    values.put("owner", blankTo(event.owner(), "nobody - it is unclaimed"));
    values.put("actor", blankTo(event.actor(), "the system"));
    values.put("status", blankTo(event.status(), "unchanged"));
    values.put("previousStatus", blankTo(event.previousStatus(), "nothing"));
    values.put("reason", blankTo(event.reason(), "no reason was given"));
    values.put("dueAt", event.dueAt() == null ? "no due date" : readableTime(event.dueAt()));
    return values;
  }

  private String readableTime(Instant instant) {
    return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
        .withZone(ZoneId.of(zone)).format(instant) + " (" + zone + ")";
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
