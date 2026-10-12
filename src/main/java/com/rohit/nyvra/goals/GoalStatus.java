package com.rohit.nyvra.goals;

/** Lifecycle of a goal. Whether it has been reached is derived from progress, not stored. */
public enum GoalStatus {
    /** Being saved for; counts towards the health score. */
    ACTIVE,
    /** Set aside by the user; kept for history, ignored by the health score and closed to new contributions. */
    ARCHIVED
}
