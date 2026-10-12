package com.rohit.nyvra.goals;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of goal progress for other modules, chiefly analytics. The health score computes
 * {@code 100 * Σ(progressRatio * weight) / Σ(weight)} over the result and treats an empty list as "goalProgress
 * not evaluated" (spec section 3.6).
 */
public interface GoalProgressSource {

    /**
     * Measures every active goal of a user on one day. Archived goals are excluded.
     *
     * @param userId the owning user
     * @param asOf   the day to measure on
     * @return one snapshot per active goal; empty when the user has none
     */
    List<GoalProgressSnapshot> activeGoalProgress(UUID userId, LocalDate asOf);
}
