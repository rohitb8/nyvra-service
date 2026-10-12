package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.rohit.nyvra.common.money.Money;

/**
 * Pure straight-line goal progress maths, kept free of persistence so it can be unit-tested against
 * {@code HEALTH_SCORE_SPEC.md} section 3.6: the requirement accrues evenly from the start date to the target date.
 */
public final class GoalProgressCalculator {

    /** Scale of {@link GoalProgress#progressRatio()}. */
    private static final int RATIO_SCALE = 4;
    /** Scale of {@link GoalProgress#percentComplete()}. */
    private static final int PERCENT_SCALE = 2;

    /** Static holder; not instantiable. */
    private GoalProgressCalculator() {
    }

    /**
     * Measures a goal's progress on one day.
     *
     * @param goal      the goal (its start date, target date and target amount are used)
     * @param allocated the sum of its contributions, in the goal's currency
     * @param asOf      the day to measure on
     * @return the progress
     */
    public static GoalProgress calculate(Goal goal, Money allocated, LocalDate asOf) {
        Money target = goal.getTargetAmount();
        String currency = target.currency();

        long totalDays = ChronoUnit.DAYS.between(goal.getStartDate(), goal.getTargetDate());
        long elapsedDays = Math.max(0, Math.min(totalDays, ChronoUnit.DAYS.between(goal.getStartDate(), asOf)));
        BigDecimal required = target.amount()
            .multiply(BigDecimal.valueOf(elapsedDays))
            .divide(BigDecimal.valueOf(totalDays), Money.SCALE, RoundingMode.HALF_UP);

        BigDecimal ratio = required.signum() == 0
            ? BigDecimal.ONE.setScale(RATIO_SCALE)
            : allocated.amount().divide(required, RATIO_SCALE, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE.setScale(RATIO_SCALE)).max(BigDecimal.ZERO.setScale(RATIO_SCALE));

        BigDecimal percent = allocated.amount().multiply(BigDecimal.valueOf(100))
            .divide(target.amount(), PERCENT_SCALE, RoundingMode.HALF_UP)
            .min(BigDecimal.valueOf(100).setScale(PERCENT_SCALE));

        BigDecimal remaining = target.amount().subtract(allocated.amount()).max(BigDecimal.ZERO);
        return new GoalProgress(asOf, allocated, Money.of(required, currency), Money.of(remaining, currency),
            percent, ratio, Math.max(0, ChronoUnit.DAYS.between(asOf, goal.getTargetDate())),
            allocated.amount().compareTo(required) >= 0,
            allocated.amount().compareTo(target.amount()) >= 0);
    }
}
