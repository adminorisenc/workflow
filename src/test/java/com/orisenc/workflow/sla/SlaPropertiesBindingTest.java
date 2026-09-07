package com.orisenc.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The ladder binds from configuration exactly as {@code application.yml} writes it.
 *
 * <p>Written for the same reason {@code SpringBeanConstructorTest} exists: this is a failure no other
 * test in the suite can see. Every SLA test builds {@link SlaProperties} by calling its constructor,
 * so a record the binder cannot construct, or a comma-separated duration list it will not split,
 * passes the whole build and appears only when somebody starts the service - which on this platform
 * has historically been much later than it should be.
 *
 * <p>The values below are copied from the shipped defaults deliberately. If somebody changes the yml
 * and not this test, the two have disagreed, which is the thing worth being told about.
 */
class SlaPropertiesBindingTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
          ConfigurationPropertiesAutoConfiguration.class))
      .withUserConfiguration(Bound.class);

  @EnableConfigurationProperties(SlaProperties.class)
  static class Bound {}

  @Test
  void theShippedDefaultsBind() {
    runner.withPropertyValues(
            "orisenc.workflow.sla.enabled=true",
            "orisenc.workflow.sla.cron=0 */15 * * * *",
            "orisenc.workflow.sla.zone=Asia/Kolkata",
            "orisenc.workflow.sla.due-soon-lead=24h",
            // A comma-separated list is what an environment variable can express, and how the yml
            // writes it. A binder that treated this as one malformed duration would fail startup.
            "orisenc.workflow.sla.escalation-steps=24h,72h",
            "orisenc.workflow.sla.batch-size=500")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(SlaProperties.class);
          assertThat(properties.enabled()).isTrue();
          assertThat(properties.zone()).isEqualTo("Asia/Kolkata");
          assertThat(properties.dueSoonLead()).isEqualTo(Duration.ofHours(24));
          assertThat(properties.escalationSteps())
              .containsExactly(Duration.ofHours(24), Duration.ofHours(72));
          assertThat(properties.batchSize()).isEqualTo(500);
          // Blank in the shipped configuration until a deployment names a mailbox.
          assertThat(properties.escalationContact()).isNull();
        });
  }

  @Test
  void anEmptyEscalationContactBindsAsAbsentRatherThanAsARecipient() {
    // The yml ships it empty. Bound as "" it would become a recipient address of "", which the
    // Integration Service would accept and then dead-letter once per escalation.
    runner.withPropertyValues("orisenc.workflow.sla.escalation-contact=")
        .run(context -> assertThat(context.getBean(SlaProperties.class).escalationContact()).isNull());
  }

  @Test
  void aDeploymentThatConfiguresNothingStillGetsAWorkingLadder() {
    // Every field has a defensible fallback in the compact constructor, so a missing block is a
    // running ladder rather than a context that fails to start.
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      var properties = context.getBean(SlaProperties.class);
      assertThat(properties.cron()).isEqualTo("0 */15 * * * *");
      assertThat(properties.escalationSteps())
          .containsExactly(Duration.ofHours(24), Duration.ofHours(72));
    });
  }
}
