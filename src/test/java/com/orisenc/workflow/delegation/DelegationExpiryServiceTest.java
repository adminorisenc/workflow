package com.orisenc.workflow.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orisenc.workflow.notify.DelegationNotification;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The automatic half of REQ-0027.
 *
 * <p>The reason this is worth its own tests: nobody notices an expiry that did not happen. A
 * delegation left {@code ACTIVE} forever reads on a screen and in an audit as cover that is still in
 * place, and the failure surfaces only when somebody asks who was covering last March.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DelegationExpiryServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");

  @Autowired private EntityManager entityManager;

  private DelegationExpiryService expiry;
  private final List<Object> published = new ArrayList<>();

  @BeforeEach
  void setUp() {
    published.clear();
    expiry = new DelegationExpiryService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), published::add,
        Clock.fixed(NOW, ZoneOffset.UTC));
    entityManager.createQuery("delete from DelegationEventEntity").executeUpdate();
    entityManager.createQuery("delete from DelegationEntity").executeUpdate();
    entityManager.flush();
  }

  private DelegationEntity persist(String id, DelegationScope scope, Instant from, Instant until) {
    var delegation = new DelegationEntity(id, "priya@orisenc.com", "ravi@orisenc.com", scope, null,
        null, from, until, "Annual leave", "priya@orisenc.com", from);
    delegation.addEvent("REQUESTED", "priya@orisenc.com", "Annual leave", from, "c");
    entityManager.persist(delegation);
    entityManager.flush();
    return delegation;
  }

  @Test
  void aWindowThatHasClosedIsClosedOnTheRecord() {
    persist("DLG-DONE", DelegationScope.WORK_ITEMS, NOW.minus(Duration.ofDays(10)),
        NOW.minus(Duration.ofDays(1)));

    var run = expiry.runOnce();
    entityManager.clear();

    assertThat(run.expired()).isEqualTo(1);
    var reloaded = entityManager.find(DelegationEntity.class, "DLG-DONE");
    assertThat(reloaded.getStatus()).isEqualTo(DelegationStatus.EXPIRED);
    assertThat(reloaded.getEndedAt()).isEqualTo(NOW);
    assertThat(reloaded.getEvents()).extracting(DelegationEventEntity::getAction)
        .contains("EXPIRED");
  }

  @Test
  void aWindowStillOpenIsLeftAlone() {
    persist("DLG-LIVE", DelegationScope.WORK_ITEMS, NOW.minus(Duration.ofDays(1)),
        NOW.plus(Duration.ofDays(5)));

    assertThat(expiry.runOnce().expired()).isZero();
  }

  @Test
  void aDelegationNobodyEverApprovedIsCountedSeparately() {
    // Somebody arranged cover, went away, and never got it. That is a process failure rather than a
    // lifecycle event, and worth being able to count on its own.
    persist("DLG-ABANDONED", DelegationScope.APPROVALS, NOW.minus(Duration.ofDays(10)),
        NOW.minus(Duration.ofDays(1)));

    var run = expiry.runOnce();

    assertThat(run.expired()).isEqualTo(1);
    assertThat(run.abandoned()).isEqualTo(1);
  }

  @Test
  void bothPartiesAreToldBecauseNobodyWasThereToNotice() {
    persist("DLG-DONE", DelegationScope.WORK_ITEMS, NOW.minus(Duration.ofDays(10)),
        NOW.minus(Duration.ofDays(1)));

    expiry.runOnce();

    assertThat(published).singleElement().satisfies(event -> {
      var notification = (DelegationNotification) event;
      assertThat(notification.kind()).isEqualTo(DelegationNotification.Kind.ENDED);
      // No actor, so the notifier drops nobody from the audience.
      assertThat(notification.actor()).isNull();
      assertThat(notification.outcome()).isEqualTo("expired");
      // The event row, not the delegation id, so a delegation that ends twice in its life is two
      // messages rather than one suppressed by idempotency.
      assertThat(notification.eventRef()).startsWith("DLG-DONE#");
    });
  }

  @Test
  void runningItTwiceOverTheSameMinuteClosesNothingTheSecondTime() {
    // The blueprint asks every scheduler here to be idempotent, and this is what that means: the
    // second pass finds nothing, writes nothing, and tells nobody a second time.
    persist("DLG-DONE", DelegationScope.WORK_ITEMS, NOW.minus(Duration.ofDays(10)),
        NOW.minus(Duration.ofDays(1)));
    expiry.runOnce();
    published.clear();

    var second = expiry.runOnce();

    assertThat(second.expired()).isZero();
    assertThat(published).isEmpty();
  }

  @Test
  void aRevokedDelegationIsNotExpiredOnTopOfItsRevocation() {
    // It has already ended, and overwriting how it ended would lose why.
    var delegation = persist("DLG-REVOKED", DelegationScope.WORK_ITEMS,
        NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)));
    delegation.revoke("priya@orisenc.com", "Back early.", NOW.minus(Duration.ofDays(5)), "c");
    entityManager.flush();
    entityManager.clear();

    assertThat(expiry.runOnce().expired()).isZero();
    assertThat(entityManager.find(DelegationEntity.class, "DLG-REVOKED").getStatus())
        .isEqualTo(DelegationStatus.REVOKED);
  }

  @Test
  void theBatchSizeIsAHardCeilingOnOnePass() {
    for (int index = 0; index < 5; index++)
      persist("DLG-" + index, DelegationScope.WORK_ITEMS, NOW.minus(Duration.ofDays(10)),
          NOW.minus(Duration.ofDays(index + 1)));
    var bounded = new DelegationExpiryService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 2), published::add,
        Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(bounded.runOnce().expired()).isEqualTo(2);
  }
}
