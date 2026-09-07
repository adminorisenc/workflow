package com.orisenc.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rung rule, tested hardest, because it is the part of the SLA feature that decides how much
 * mail a person receives.
 *
 * <p>The failure this guards against is not subtle once it happens and is invisible until it does:
 * a task three days past its deadline generating a warning that it is due tomorrow, followed by a
 * breach, followed by two escalations, all within one minute of the first pass over an existing
 * table.
 */
class SlaLadderTest {

  private static final Instant DUE = Instant.parse("2026-09-10T12:00:00Z");

  private final SlaProperties properties = new SlaProperties(true, null, null, Duration.ofHours(24),
      List.of(Duration.ofHours(24), Duration.ofHours(72)), null, 500);

  @Test
  void nothingIsCrossedWhileTheDeadlineIsStillOutOfSight() {
    assertThat(SlaLadder.levelAt(DUE, DUE.minus(Duration.ofHours(25)), properties))
        .isEqualTo(SlaLadder.NONE);
  }

  @Test
  void theWarningStartsExactlyOneLeadTimeBeforeTheDeadline() {
    // The boundary is inclusive. An exclusive one would mean a pass landing precisely on it does
    // nothing, and the next pass warns fifteen minutes into the window - defensible, but then the
    // configured lead time is not the lead time anybody gets.
    assertThat(SlaLadder.levelAt(DUE, DUE.minus(Duration.ofHours(24)), properties)).isEqualTo(1);
    assertThat(SlaLadder.levelAt(DUE, DUE.minus(Duration.ofMinutes(1)), properties)).isEqualTo(1);
  }

  @Test
  void theDeadlineItselfIsABreachAndNotAWarning() {
    assertThat(SlaLadder.levelAt(DUE, DUE, properties)).isEqualTo(2);
    assertThat(SlaLadder.stageOf(SlaLadder.levelAt(DUE, DUE, properties))).isEqualTo(SlaStage.BREACHED);
  }

  @Test
  void eachConfiguredStepIsOneFurtherRung() {
    assertThat(SlaLadder.levelAt(DUE, DUE.plus(Duration.ofHours(23)), properties)).isEqualTo(2);
    assertThat(SlaLadder.levelAt(DUE, DUE.plus(Duration.ofHours(24)), properties)).isEqualTo(3);
    assertThat(SlaLadder.levelAt(DUE, DUE.plus(Duration.ofHours(71)), properties)).isEqualTo(3);
    assertThat(SlaLadder.levelAt(DUE, DUE.plus(Duration.ofHours(72)), properties)).isEqualTo(4);
  }

  @Test
  void wellPastTheLastStepStaysOnTheLastStep() {
    // The ladder ends. A task neglected for a year is at the top rung, not on rung 8,760 - there is
    // no further person to escalate to, and re-raising forever would be noise rather than pressure.
    assertThat(SlaLadder.levelAt(DUE, DUE.plus(Duration.ofDays(365)), properties)).isEqualTo(4);
  }

  @Test
  void aTaskThatIsAlreadyLateGetsOneMessageRatherThanFour() {
    // The rule this class exists for: the first pass over a neglected backlog raises the rung the
    // task is actually at. Everything below it is skipped, not queued.
    int level = SlaLadder.levelAt(DUE, DUE.plus(Duration.ofDays(4)), properties);

    assertThat(level).isEqualTo(4);
    assertThat(SlaLadder.stageOf(level)).isEqualTo(SlaStage.ESCALATED);
    assertThat(SlaLadder.escalationStep(level)).isEqualTo(2);
  }

  @Test
  void stagesAndStepsReadTheWayTheMessagesDo() {
    assertThat(SlaLadder.stageOf(1)).isEqualTo(SlaStage.DUE_SOON);
    assertThat(SlaLadder.stageOf(2)).isEqualTo(SlaStage.BREACHED);
    assertThat(SlaLadder.stageOf(3)).isEqualTo(SlaStage.ESCALATED);
    // Not escalations, so no step number to print.
    assertThat(SlaLadder.escalationStep(1)).isZero();
    assertThat(SlaLadder.escalationStep(2)).isZero();
    assertThat(SlaLadder.escalationStep(3)).isEqualTo(1);
  }

  @Test
  void aWarningIsMuteableAndEverythingAboveItIsNot() {
    assertThat(SlaStage.DUE_SOON.critical()).isFalse();
    assertThat(SlaStage.BREACHED.critical()).isTrue();
    assertThat(SlaStage.ESCALATED.critical()).isTrue();
  }

  @Test
  void anItemWithNoDeadlineCrossesNothing() {
    assertThat(SlaLadder.levelAt(null, DUE, properties)).isEqualTo(SlaLadder.NONE);
  }

  @Test
  void theHorizonIsExactlyTheEarliestDeadlineThatCanBeAtRungOne() {
    // What the scan's where clause uses. Too near and a warning is missed for a whole interval; too
    // far and every pass loads work due next month.
    assertThat(SlaLadder.horizon(DUE, properties)).isEqualTo(DUE.plus(Duration.ofHours(24)));
  }

  @Test
  void stepsAreSortedAndDeduplicatedWhateverAnOperatorWrites() {
    // The stored level is an index into this list, so an unsorted one would make level 3 sometimes
    // mean "further along" than level 4.
    var muddled = new SlaProperties(true, null, null, Duration.ofHours(24),
        List.of(Duration.ofHours(72), Duration.ofHours(24), Duration.ofHours(72), Duration.ZERO),
        null, 500);

    assertThat(muddled.escalationSteps())
        .containsExactly(Duration.ofHours(24), Duration.ofHours(72));
  }

  @Test
  void anEmptyStepListFallsBackToTheShippedLadderRatherThanNoEscalationAtAll() {
    var empty = new SlaProperties(true, null, null, null, List.of(), null, 0);

    assertThat(empty.escalationSteps()).containsExactly(Duration.ofHours(24), Duration.ofHours(72));
    assertThat(empty.dueSoonLead()).isEqualTo(Duration.ofHours(24));
    assertThat(empty.batchSize()).isEqualTo(500);
    assertThat(empty.cron()).isEqualTo("0 */15 * * * *");
  }
}
