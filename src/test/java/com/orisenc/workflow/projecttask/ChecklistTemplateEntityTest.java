package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChecklistTemplateEntityTest {
  private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

  @Test
  void namesAndItemsAreTrimmedAndRequiredDefaultsOn() {
    var template = new ChecklistTemplateEntity("  Delivery  ", "  Evidence  ",
        List.of(new ChecklistTemplateItemInput("  Capture POD  ", null)), "admin", NOW);
    assertThat(template.getName()).isEqualTo("Delivery");
    assertThat(template.getItems().getFirst().getTitle()).isEqualTo("Capture POD");
    assertThat(template.getItems().getFirst().isRequired()).isTrue();
  }

  @Test
  void aTemplateNeedsAnItemAndCapsRunawayProcedures() {
    assertThatThrownBy(() -> new ChecklistTemplateEntity("Empty", null, List.of(), "admin", NOW))
        .hasMessageContaining("at least one");
    var tooMany = java.util.stream.IntStream.rangeClosed(1, 51)
        .mapToObj(i -> new ChecklistTemplateItemInput("Item " + i, true)).toList();
    assertThatThrownBy(() -> new ChecklistTemplateEntity("Too many", null, tooMany, "admin", NOW))
        .hasMessageContaining("50");
  }

  @Test
  void deactivationKeepsTheTemplateAndItsItemsForAudit() {
    var template = new ChecklistTemplateEntity("Delivery", null,
        List.of(new ChecklistTemplateItemInput("Capture POD", true)), "admin", NOW);
    template.deactivate("lead", NOW.plusSeconds(60));
    assertThat(template.isActive()).isFalse();
    assertThat(template.getItems()).extracting(ChecklistTemplateItemEntity::getTitle)
        .containsExactly("Capture POD");
  }
}
