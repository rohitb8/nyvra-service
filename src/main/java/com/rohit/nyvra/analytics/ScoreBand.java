package com.rohit.nyvra.analytics;

/** Overall health-score band (spec §2); {@code INSUFFICIENT_DATA} is never stored. */
public enum ScoreBand {
    /** 0-39: one or more fundamentals broken. */
    NEEDS_ATTENTION,
    /** 40-59: getting by, clear gaps. */
    FAIR,
    /** 60-79: in control, room to optimise. */
    GOOD,
    /** 80-100: strong across the board. */
    EXCELLENT,
    /** Fewer than the minimum number of sub-scores could be evaluated, so there is no overall. */
    INSUFFICIENT_DATA
}
