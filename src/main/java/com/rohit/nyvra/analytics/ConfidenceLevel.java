package com.rohit.nyvra.analytics;

/** Server-side bucketing of the numeric confidence, so clients never apply the thresholds. */
public enum ConfidenceLevel {
    /** Below the medium threshold. */
    LOW,
    /** From the medium threshold up to the high threshold. */
    MEDIUM,
    /** Above the high threshold. */
    HIGH
}
