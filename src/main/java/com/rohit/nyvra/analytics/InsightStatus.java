package com.rohit.nyvra.analytics;

/** Lifecycle of an insight, derived from its {@code dismissed_at} and {@code superseded_at} timestamps. */
public enum InsightStatus {
    /** Currently raised and not dismissed. */
    ACTIVE,
    /** Hidden by the user, still current. */
    DISMISSED,
    /** Replaced by a newer insight for the same rule, or cleared because the condition went away. */
    SUPERSEDED
}
