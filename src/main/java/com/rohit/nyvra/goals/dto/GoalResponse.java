package com.rohit.nyvra.goals.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.Goal;
import com.rohit.nyvra.goals.GoalProgress;
import com.rohit.nyvra.goals.GoalStatus;
import com.rohit.nyvra.goals.GoalType;

/**
 * API view of a goal together with its current progress.
 *
 * @param id           goal id
 * @param name         label
 * @param type         what the goal is for
 * @param targetAmount amount to reach
 * @param startDate    first day of accrual
 * @param targetDate   day by which to reach the target
 * @param weight       relative importance among goals
 * @param status       lifecycle state
 * @param progress     progress as of today (India time)
 * @param createdAt    creation time (UTC)
 * @param updatedAt    last modification time (UTC)
 */
public record GoalResponse(
    UUID id,
    String name,
    GoalType type,
    MoneyDto targetAmount,
    LocalDate startDate,
    LocalDate targetDate,
    int weight,
    GoalStatus status,
    GoalProgressResponse progress,
    Instant createdAt,
    Instant updatedAt) {

    /**
     * Maps a goal and its progress to the response.
     *
     * @param g        the goal
     * @param progress its progress
     * @return the response
     */
    public static GoalResponse from(Goal g, GoalProgress progress) {
        return new GoalResponse(g.getId(), g.getName(), g.getType(), MoneyDto.from(g.getTargetAmount()),
            g.getStartDate(), g.getTargetDate(), g.getWeight(), g.getStatus(), GoalProgressResponse.from(progress),
            g.getCreatedAt(), g.getUpdatedAt());
    }
}
