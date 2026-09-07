package com.orisenc.workflow.notify;

import com.orisenc.workflow.integration.IntegrationServiceClient;
import com.orisenc.workflow.integration.IntegrationServiceClient.Channel;
import com.orisenc.workflow.integration.IntegrationServiceClient.NotificationRequest;
import com.orisenc.workflow.notify.DelegationNotification.Kind;
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
 * Tells the two people a delegation concerns that it started or stopped (REQ-0027, REQ-0023).
 *
 * <p>Separate from {@link WorkItemNotifier} rather than another {@code Kind} on it, because the two
 * carry different facts: a task notification is about a task, and nothing in a delegation is one.
 * Folding them together would mean a value map where half the keys are null for half the messages,
 * which is precisely the shape that turns a template edit into a dead letter.
 *
 * <p><b>Approvers are not notified.</b> A privileged delegation waits on a holder of
 * {@code platform.delegation.approve}, and this service cannot name those people: Common Platform
 * resolves permissions for a presented token and has no "who holds this code" query. The pending
 * queue is therefore a screen somebody opens, not a message that finds them - a real gap, and the
 * reason {@code GET /api/delegations?status=PENDING_APPROVAL} exists.
 */
@Component
public class DelegationNotifier {

  private static final Logger log = LoggerFactory.getLogger(DelegationNotifier.class);

  /** Templates the Integration Service renders. Their wording and version live there, not here. */
  static final String TEMPLATE_ACTIVE = "DELEGATION_ACTIVE";
  static final String TEMPLATE_ENDED = "DELEGATION_ENDED";

  private final Supplier<IntegrationServiceClient> integration;
  private final String zone;

  @Autowired
  public DelegationNotifier(ObjectProvider<IntegrationServiceClient> integration,
      @Value("${orisenc.workflow.delegation.zone:Asia/Kolkata}") String zone) {
    this(integration::getIfAvailable, zone);
  }

  DelegationNotifier(Supplier<IntegrationServiceClient> integration, String zone) {
    this.integration = integration;
    this.zone = zone;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void on(DelegationNotification event) {
    raise(event);
  }

  /** Posts one message per recipient and returns how many were queued. */
  int raise(DelegationNotification event) {
    var client = integration.get();
    if (client == null) return 0;

    int queued = 0;
    for (String recipient : recipientsFor(event)) {
      try {
        client.raiseNotification(new NotificationRequest(eventType(event), event.eventRef(),
            Channel.EMAIL, recipient, template(event.kind()), values(event), null, null,
            // Critical, both kinds. Somebody who has muted routine mail still needs to know when
            // they gained authority over another person's queue, and when they lost it.
            Boolean.TRUE, correlationId(event)));
        queued++;
      } catch (RuntimeException refused) {
        log.warn("Could not raise the delegation {} notification for {} to {}: {}", event.kind(),
            event.delegationId(), recipient, refused.getClass().getSimpleName());
      }
    }
    return queued;
  }

  /**
   * Who hears about it.
   *
   * <pre>
   *   ACTIVE   the delegate - it is their new authority
   *   ENDED    both parties - the delegator needs to know their cover has stopped
   * </pre>
   *
   * <p>The actor is dropped, so a delegator who revokes their own delegation is not told that they
   * did. An expiry has no actor, so both parties hear about it - which is the case that matters
   * most, because nobody was there to notice.
   */
  static List<String> recipientsFor(DelegationNotification event) {
    var wanted = new ArrayList<String>();
    wanted.add(event.delegate());
    if (event.kind() == Kind.ENDED) wanted.add(event.delegator());

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

  static String eventType(DelegationNotification event) {
    return "DELEGATION_" + event.kind().name();
  }

  private static String template(Kind kind) {
    return kind == Kind.ACTIVE ? TEMPLATE_ACTIVE : TEMPLATE_ENDED;
  }

  private static String correlationId(DelegationNotification event) {
    return "delegation-" + event.eventRef().toLowerCase(Locale.ROOT);
  }

  /**
   * Every value the two templates can ask for, supplied whichever is being rendered - the same
   * contract, and the same reason, as everything else this service raises: the Integration Service
   * refuses to render an unresolved placeholder.
   */
  Map<String, String> values(DelegationNotification event) {
    var values = new LinkedHashMap<String, String>();
    values.put("delegationId", event.delegationId());
    values.put("delegator", blankTo(event.delegator(), "unknown"));
    values.put("delegate", blankTo(event.delegate(), "unknown"));
    values.put("actor", blankTo(event.actor(), "the system"));
    values.put("scope", readableScope(event.scope()));
    values.put("taskType", blankTo(event.taskType(), "every type"));
    values.put("department", blankTo(event.department(), "every department"));
    values.put("validFrom", readableTime(event.validFrom()));
    values.put("validUntil", readableTime(event.validUntil()));
    values.put("reason", blankTo(event.reason(), "no reason was given"));
    values.put("outcome", blankTo(event.outcome(), "ended"));
    return values;
  }

  /** {@code WORK_ITEMS} is a database value; "work items" is what somebody reads in a sentence. */
  private static String readableScope(String scope) {
    if (scope == null || scope.isBlank()) return "all work";
    return switch (scope) {
      case "ALL" -> "approvals and work items";
      case "APPROVALS" -> "approvals";
      case "WORK_ITEMS" -> "work items";
      default -> scope.toLowerCase(Locale.ROOT).replace('_', ' ');
    };
  }

  private String readableTime(Instant instant) {
    if (instant == null) return "an unspecified time";
    return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
        .withZone(ZoneId.of(zone)).format(instant) + " (" + zone + ")";
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
