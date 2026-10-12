package com.rohit.nyvra.goals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the straight-line progress maths against {@code HEALTH_SCORE_SPEC.md} section 3.6. The reference
 * goal is 120000.00 INR over 100 days, so the requirement accrues 1200.00 INR per day.
 */
class GoalProgressCalculatorTest {

    /** First day of the reference goal. */
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    /** Last day of the reference goal, 100 days after {@link #START}. */
    private static final LocalDate TARGET = START.plusDays(100);

    /** @return the reference goal: 120000.00 INR from {@link #START} to {@link #TARGET} */
    private static Goal goal() {
        return new Goal(UUID.randomUUID(), "House", GoalType.HOME, Money.inr("120000.00"), START, TARGET, 1);
    }

    /**
     * Runs the calculator for the reference goal.
     *
     * @param allocated the saved amount
     * @param asOf      days after the start date to measure on
     * @return the progress
     */
    private static GoalProgress at(String allocated, int asOf) {
        return GoalProgressCalculator.calculate(goal(), Money.inr(allocated), START.plusDays(asOf));
    }

    /** Halfway through the period the requirement is half the target. */
    @Test
    void requirementAccruesStraightLine() {
        GoalProgress p = at("0.00", 50);
        assertThat(p.requiredToDate()).isEqualTo(Money.inr("60000.00"));
        assertThat(p.daysRemaining()).isEqualTo(50);
    }

    /** Saving exactly the requirement gives a ratio of one and counts as on track. */
    @Test
    void savingTheRequirementIsOnTrack() {
        GoalProgress p = at("60000.00", 50);
        assertThat(p.progressRatio()).isEqualByComparingTo("1");
        assertThat(p.onTrack()).isTrue();
        assertThat(p.achieved()).isFalse();
        assertThat(p.percentComplete()).isEqualByComparingTo("50");
        assertThat(p.remaining()).isEqualTo(Money.inr("60000.00"));
    }

    /** Saving a fraction of the requirement gives that fraction as the ratio. */
    @Test
    void behindScheduleGivesFractionalRatio() {
        GoalProgress p = at("15000.00", 50);
        assertThat(p.progressRatio()).isEqualByComparingTo("0.25");
        assertThat(p.onTrack()).isFalse();
    }

    /** Saving more than required never pushes the ratio above one. */
    @Test
    void aheadOfScheduleIsClampedToOne() {
        assertThat(at("100000.00", 10).progressRatio()).isEqualByComparingTo("1");
    }

    /** Before any time has elapsed nothing is required, so the ratio is one even with nothing saved. */
    @Test
    void nothingRequiredOnTheStartDate() {
        GoalProgress p = at("0.00", 0);
        assertThat(p.requiredToDate()).isEqualTo(Money.zero("INR"));
        assertThat(p.progressRatio()).isEqualByComparingTo("1");
        assertThat(p.onTrack()).isTrue();
    }

    /** A measure date before the start date behaves like the start date. */
    @Test
    void dateBeforeStartIsTreatedAsStart() {
        assertThat(at("0.00", -5).requiredToDate()).isEqualTo(Money.zero("INR"));
    }

    /** After the target date the whole target is required and no days remain. */
    @Test
    void pastTargetDateRequiresTheWholeTarget() {
        GoalProgress p = at("30000.00", 150);
        assertThat(p.requiredToDate()).isEqualTo(Money.inr("120000.00"));
        assertThat(p.daysRemaining()).isZero();
        assertThat(p.progressRatio()).isEqualByComparingTo("0.25");
    }

    /** Reaching the target is achieved, with 100 percent and nothing remaining, even when over-saved. */
    @Test
    void overSavedGoalIsAchievedAndCapped() {
        GoalProgress p = at("150000.00", 100);
        assertThat(p.achieved()).isTrue();
        assertThat(p.percentComplete()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(p.remaining()).isEqualTo(Money.zero("INR"));
    }
}
