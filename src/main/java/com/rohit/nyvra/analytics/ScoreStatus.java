package com.rohit.nyvra.analytics;

/** Health of one sub-score. */
public enum ScoreStatus {
    /** At or better than target. */
    OK,
    /** Short of target but above the floor. */
    WARN,
    /** Below the floor. */
    CRITICAL
}
