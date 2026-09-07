package com.orisenc.workflow.delegation;

import com.orisenc.workflow.notify.DelegationNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Closes delegation windows that have passed (REQ-0027, automatic expiry).
 *
 * <p>The requirement asks for expiry to be automatic, and the reason is worth stating plainly: a
 * delegation that ends only when somebody remembers to end it is a standing grant of authority with
 * a date on it. Nobody notices an expiry that did not happen.
 *
 * <h2>Why the window is not enough on its own</h2>
 *
 * <p>{@link DelegationEntity#inForce} already refuses to authorize anybody outside the window, so an
 * unexpired row is harmless in the moment. What this pass buys is a record that says so: a
 * delegation left {@code ACTIVE} forever reads, on a screen and in an audit, as cover that is still
 * in place. The authority is gone either way; the honesty is not.
 *
 * <p>Idempotent, as the blueprint requires of every scheduler here: the query finds only
 * non-terminal rows past their end, the entity refuses to expire one that has already ended, and a
 * second pass over the same minute therefore closes nothing and sends nothing.
 */
@Service
public class DelegationExpiryService {

  private static final Logger log = LoggerFactory.getLogger(DelegationExpiryService.class);

  private static final List<DelegationStatus> OPEN =
      List.of(DelegationStatus.PENDING_APPROVAL, DelegationStatus.ACTIVE);

  private final jakarta.persistence.EntityManager entityManager;
  private final DelegationProperties properties;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  // Two constructors: this one for Spring, the package-private one below for tests with a fixed
  // Clock. Without @Autowired Spring cannot choose - see SpringBeanConstructorTest.
  @Autowired
  public DelegationExpiryService(jakarta.persistence.EntityManager entityManager,
      DelegationProperties properties, ApplicationEventPublisher events) {
    this(entityManager, properties, events, Clock.systemUTC());
  }

  DelegationExpiryService(jakarta.persistence.EntityManager entityManager,
      DelegationProperties properties, ApplicationEventPublisher events, Clock clock) {
    this.entityManager = entityManager;
    this.properties = properties;
    this.events = events;
    this.clock = clock;
  }

  /** What one pass did. Returned so an operator can trigger a run and see the result. */
  public record ExpiryRun(int expired, int abandoned) {}

  @Scheduled(cron = "${orisenc.workflow.delegation.expiry-cron:0 5 * * * *}",
      zone = "${orisenc.workflow.delegation.zone:Asia/Kolkata}")
  // The scheduler enters here; the internal runOnce() call does not cross Spring's proxy.
  @Transactional
  public void scheduledRun() {
    var result = runOnce();
    if (result.expired() > 0 || result.abandoned() > 0)
      log.info("Delegation expiry: {} closed, {} of them never approved.", result.expired(),
          result.abandoned());
  }

  /**
   * Closes every window that has passed.
   *
   * <p>Two things end here, and they are counted separately because they mean different things. A
   * delegation that ran its course is cover working as intended. One that expired while still
   * waiting on a decision is somebody who arranged cover, went away, and never got it - which is a
   * process failure rather than a lifecycle event, and worth being able to count.
   */
  @Transactional
  public ExpiryRun runOnce() {
    Instant now = clock.instant();
    var due = entityManager.createQuery(
            "select d from DelegationEntity d where d.status in :open and d.validUntil <= :now"
                + " order by d.validUntil asc", DelegationEntity.class)
        .setParameter("open", OPEN)
        .setParameter("now", now)
        .setMaxResults(properties.batchSize())
        .getResultList();

    int expired = 0;
    int abandoned = 0;
    for (DelegationEntity delegation : due) {
      boolean neverApproved = delegation.getStatus() == DelegationStatus.PENDING_APPROVAL;
      try {
        delegation.expire(now, "delegation-expiry-" + delegation.getId().toLowerCase());
      } catch (IllegalArgumentException alreadyEnded) {
        // An overlapping pass got there first. Not an error; the row is already correct.
        continue;
      }
      expired++;
      if (neverApproved) abandoned++;
      // Flushed per row, not once at the end, because the notification names the event row that
      // recorded this expiry and that row has no id until it is written.
      entityManager.flush();
      // No actor, so both parties hear about it - which is the case that matters most, because
      // nobody was there to notice.
      events.publishEvent(new DelegationNotification(DelegationNotification.Kind.ENDED,
          delegation.getId(), eventRef(delegation), delegation.getDelegatorUserId(),
          delegation.getDelegateUserId(), null, delegation.getScope().name(),
          delegation.getTaskType(), delegation.getDepartment(), delegation.getValidFrom(),
          delegation.getValidUntil(), delegation.getReason(),
          neverApproved ? "expired without ever being approved" : "expired"));
    }
    return new ExpiryRun(expired, abandoned);
  }

  private static String eventRef(DelegationEntity delegation) {
    var history = delegation.getEvents();
    return history.isEmpty() ? delegation.getId()
        : delegation.getId() + "#" + history.getLast().getId();
  }
}
