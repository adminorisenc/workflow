package com.orisenc.workflow.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the scheduler entry point through Spring without a test-owned transaction. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(DelegationScheduledExpiryTest.Configuration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DelegationScheduledExpiryTest {

  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");

  @Autowired private EntityManager entityManager;
  @Autowired private DelegationExpiryService expiry;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void theScheduledEntryPointCommitsExpiryAndDoesNotRepeatIt() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> {
      var delegation = new DelegationEntity("DLG-SCHEDULED", "owner@orisenc.com",
          "delegate@orisenc.com", DelegationScope.WORK_ITEMS, null, null,
          NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), "Leave",
          "owner@orisenc.com", NOW.minus(Duration.ofDays(2)));
      entityManager.persist(delegation);
    });

    try {
      expiry.scheduledRun();
      expiry.scheduledRun();

      transaction.executeWithoutResult(status -> {
        var delegation = entityManager.find(DelegationEntity.class, "DLG-SCHEDULED");
        assertThat(delegation.getStatus()).isEqualTo(DelegationStatus.EXPIRED);
        assertThat(delegation.getEndedAt()).isEqualTo(NOW);
        assertThat(delegation.getEvents()).extracting(DelegationEventEntity::getAction)
            .containsExactly("EXPIRED");
      });
    } finally {
      transaction.executeWithoutResult(status ->
          entityManager.remove(entityManager.find(DelegationEntity.class, "DLG-SCHEDULED")));
    }
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    DelegationExpiryService expiry(EntityManager entityManager, ApplicationEventPublisher events) {
      return new DelegationExpiryService(entityManager,
          new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), events,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }
}
