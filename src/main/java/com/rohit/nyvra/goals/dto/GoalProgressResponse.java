package com.rohit.nyvra.goals.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.GoalProgress;

/**
 * API view of a goal's progress. Every figure is computed server-side; the client only displays it.
 *
 * @param asOf            the day the figures are for
 * @param allocated       sum of the goal's contributions
 * @param requiredToDate  straight-line share of the target that should be saved by {@code asOf}
 * @param remaining       amount still to save, never below zero
 * @param percentComplete percentage of the target saved, 0 to 100
 * @param progressRatio   per-goal health-score input, 0 to 1
 * @param daysRemaining   days until the target date, never below zero
 * @param onTrack         whether at least the straight-line requirement is saved
 * @param achieved        whether the target is reached
 */
public record GoalProgressResponse(
    LocalDate asOf,
    MoneyDto allocated,
    MoneyDto requiredToDate,
    MoneyDto remaining,
    BigDecimal percentComplete,
    BigDecimal progressRatio,
    long daysRemaining,
    boolean onTrack,
    boolean achieved) {

    /**
     * Maps a progress measure to its response.
     *
     * @param p the progress
     * @return the response
     */
    public static GoalProgressResponse from(GoalProgress p) {
        return new GoalProgressResponse(p.asOf(), MoneyDto.from(p.allocated()), MoneyDto.from(p.requiredToDate()),
            MoneyDto.from(p.remaining()), p.percentComplete(), p.progressRatio(), p.daysRemaining(),
            p.onTrack(), p.achieved());
    }
}
