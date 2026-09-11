package com.orisenc.workflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/**
 * The two custom JPQL queries — {@code findDue} and {@code findActiveForUser} — against a real JPA
 * provider.
 *
 * <p>These queries filter on nullable columns ({@code notified_at}, {@code dismissed_at}), and
 * that class of predicate is where JPQL and SQL diverge enough that a mock returning a hardcoded
 * list cannot say whether the query itself is correct.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ActivityNotificationRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
  private static final Instant DUE = NOW.plus(2, ChronoUnit.HOURS);
  /** A notifyAt that has already arrived — used in findDue tests. Must be <= NOW. */
  private static final Instant PAST_NOTIFY_AT = NOW.minus(1, ChronoUnit.MINUTES);
  /** A notifyAt still in the future — used to verify findDue does not pick it up. */
  private static final Instant FUTURE_NOTIFY_AT = NOW.plus(30, ChronoUnit.MINUTES);
  /** Default notifyAt for findActiveForUser tests (value is irrelevant to that query). */
  private static final Instant NOTIFY_AT = PAST_NOTIFY_AT;

  @Autowired private TestEntityManager em;
  @Autowired private ActivityNotificationRepository repository;

  @BeforeEach
  void clearTable() {
    em.getEntityManager().createQuery("delete from ActivityNotificationEntity").executeUpdate();
    em.flush();
  }

  // ------------------------------------------------------------ findDue

  @Test
  void findDueReturnsRowsWhoseWindowHasArrivedAndHaveNotBeenFiredOrDismissed() {
    // notifyAt before NOW, notifiedAt null, dismissedAt null — the target case.
    persist(notification("asha@orisenc.com", PAST_NOTIFY_AT, null, null));

    assertThat(repository.findDue(NOW)).hasSize(1);
  }

  @Test
  void findDueExcludesRowsThatHaveAlreadyBeenFired() {
    persist(notification("asha@orisenc.com", PAST_NOTIFY_AT, NOW.minusSeconds(60), null));

    assertThat(repository.findDue(NOW)).isEmpty();
  }

  @Test
  void findDueExcludesDismissedRows() {
    persist(notification("asha@orisenc.com", PAST_NOTIFY_AT, null, NOW.minusSeconds(5)));

    assertThat(repository.findDue(NOW)).isEmpty();
  }

  @Test
  void findDueExcludesRowsWhoseWindowHasNotArrivedYet() {
    // notifyAt is still in the future from NOW's perspective.
    persist(notification("asha@orisenc.com", FUTURE_NOTIFY_AT, null, null));

    assertThat(repository.findDue(NOW)).isEmpty();
  }

  @Test
  void findDueReturnsMultipleEligibleRowsAcrossUsers() {
    persist(notification("asha@orisenc.com", PAST_NOTIFY_AT, null, null));
    persist(notification("ravi@orisenc.com", PAST_NOTIFY_AT, null, null));

    assertThat(repository.findDue(NOW)).hasSize(2);
  }

  // ------------------------------------------------------------ findActiveForUser

  @Test
  void findActiveForUserReturnsNotifiedUndismissedRowsForTheGivenUser() {
    persist(notification("asha@orisenc.com", NOTIFY_AT, NOW.minusSeconds(10), null));

    assertThat(repository.findActiveForUser("asha@orisenc.com")).hasSize(1);
  }

  @Test
  void findActiveForUserExcludesOtherUsersNotifications() {
    persist(notification("ravi@orisenc.com", NOTIFY_AT, NOW.minusSeconds(10), null));

    assertThat(repository.findActiveForUser("asha@orisenc.com")).isEmpty();
  }

  @Test
  void findActiveForUserExcludesUnfiredNotifications() {
    // notifiedAt IS NULL — the row is scheduled but not yet delivered to the user.
    persist(notification("asha@orisenc.com", NOTIFY_AT, null, null));

    assertThat(repository.findActiveForUser("asha@orisenc.com")).isEmpty();
  }

  @Test
  void findActiveForUserExcludesDismissedNotifications() {
    persist(notification("asha@orisenc.com", NOTIFY_AT, NOW.minusSeconds(10), NOW.minusSeconds(5)));

    assertThat(repository.findActiveForUser("asha@orisenc.com")).isEmpty();
  }

  @Test
  void findActiveForUserOrdersByDueAtAscending() {
    var earlier = DUE.minus(30, ChronoUnit.MINUTES);
    var later   = DUE.plus(1, ChronoUnit.HOURS);
    persist(notification("asha@orisenc.com", NOTIFY_AT, NOW.minusSeconds(10), null, later));
    persist(notification("asha@orisenc.com", NOTIFY_AT, NOW.minusSeconds(10), null, earlier));

    var results = repository.findActiveForUser("asha@orisenc.com");

    assertThat(results).extracting(ActivityNotificationEntity::getDueAt)
        .containsExactly(earlier, later);
  }

  // ------------------------------------------------------------ findByActivityId

  @Test
  void findByActivityIdLocatesTheEntryForThatActivity() {
    var saved = persist(notificationWithActivityId("asha@orisenc.com", 9001L, NOTIFY_AT, null, null));

    assertThat(repository.findByActivityId(9001L)).isPresent();
    assertThat(repository.findByActivityId(9002L)).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private long nextActivityId = 1000L;

  private ActivityNotificationEntity persist(ActivityNotificationEntity entity) {
    em.persistAndFlush(entity);
    em.clear();
    return entity;
  }

  private ActivityNotificationEntity notification(String userId, Instant notifyAt,
      Instant notifiedAt, Instant dismissedAt) {
    return notification(userId, notifyAt, notifiedAt, dismissedAt, DUE);
  }

  private ActivityNotificationEntity notification(String userId, Instant notifyAt,
      Instant notifiedAt, Instant dismissedAt, Instant dueAt) {
    return notificationWithActivityId(userId, nextActivityId++, notifyAt, notifiedAt, dismissedAt,
        dueAt);
  }

  private static ActivityNotificationEntity notificationWithActivityId(String userId,
      Long activityId, Instant notifyAt, Instant notifiedAt, Instant dismissedAt) {
    return notificationWithActivityId(userId, activityId, notifyAt, notifiedAt, dismissedAt, DUE);
  }

  private static ActivityNotificationEntity notificationWithActivityId(String userId,
      Long activityId, Instant notifyAt, Instant notifiedAt, Instant dismissedAt, Instant dueAt) {
    var e = new ActivityNotificationEntity();
    e.setUserId(userId);
    e.setOrganizationId(1000L);
    e.setActivityId(activityId);
    e.setTitle("CALL: Intro call");
    e.setDueAt(dueAt);
    e.setNotifyAt(notifyAt);
    e.setNotifiedAt(notifiedAt);
    e.setDismissedAt(dismissedAt);
    e.setCreatedAt(NOW);
    return e;
  }
}
