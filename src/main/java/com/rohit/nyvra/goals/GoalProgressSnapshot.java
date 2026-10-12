package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The slice of a goal's progress the health score needs.
 *
 * @param goalId        the goal
 * @param weight        the goal's relative weight, at least 1
 * @param progressRatio per-goal progress between 0 and 1, as in {@link GoalProgress#progressRatio()}
 */
public record GoalProgressSnapshot(UUID goalId, int weight, BigDecimal progressRatio) {
}
