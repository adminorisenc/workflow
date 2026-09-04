package com.orisenc.workflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskMasterDataReferenceTest {

  @Test
  void taskCarriesOrganizationAndOneBusinessPartyReference() {
    Instant now = Instant.parse("2026-09-03T08:00:00Z");
    UUID organizationId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    var task = new TaskEntity("TSK-MASTER", "Review credit", "CREDIT-1", "Accounts",
        TaskType.APPROVAL, TaskPriority.HIGH, "requester", null, "Review terms", null,
        now, now.plus(1, ChronoUnit.DAYS));

    task.linkToMaster(organizationId, customerId, null);

    assertThat(task.getOrganizationId()).isEqualTo(organizationId);
    assertThat(task.getCustomerId()).isEqualTo(customerId);
    assertThat(task.getVendorId()).isNull();
  }

  @Test
  void taskCannotMixCustomerAndVendorContexts() {
    Instant now = Instant.parse("2026-09-03T08:00:00Z");
    var task = new TaskEntity("TSK-MASTER", "Review", "REF-1", "Accounts",
        TaskType.APPROVAL, TaskPriority.HIGH, "requester", null, "Review", null,
        now, now.plus(1, ChronoUnit.DAYS));

    assertThatThrownBy(() -> task.linkToMaster(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both");
  }
}
