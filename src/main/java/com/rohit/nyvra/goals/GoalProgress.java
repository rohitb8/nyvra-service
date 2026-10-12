package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.rohit.nyvra.common.money.Money;

/**
 * How far along a goal is on a given day, following {@code HEALTH_SCORE_SPEC.md} section 3.6.
 *
 * @param asOf           the day the measure is for
 * @param allocated      sum of the goal's contributions
 * @param requiredToDate straight-line share of the target that should be saved by {@code asOf}
 * @param remaining      target minus allocated, never below zero
 * @param percentComplete allocated as a percentage of the target, capped at 100, scale 2
 * @param progressRatio  {@code clamp(allocated / requiredToDate, 0, 1)} (1 when nothing is required yet), scale 4;
 *                       this is the per-goal value the health score averages
 * @param daysRemaining  days from {@code asOf} to the target date, never below zero
 * @param onTrack        whether allocated is at least the straight-line requirement
 * @param achieved       whether allocated has reached the target
 */
public record GoalProgress(
    LocalDate asOf,
    Money allocated,
    Money requiredToDate,
    Money remaining,
    BigDecimal percentComplete,
    BigDecimal progressRatio,
    long daysRemaining,
    boolean onTrack,
    boolean achieved) {
}
