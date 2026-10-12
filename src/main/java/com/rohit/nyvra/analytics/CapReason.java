package com.rohit.nyvra.analytics;

/** Why a hard cap lowered the overall score (spec §2). Also appended to {@code topDrivers}. */
public enum CapReason {
    /** Emergency fund below its minimum months. */
    CAP_EMERGENCY_FUND_BELOW_MIN,
    /** A critical high-interest-debt insight is active. */
    CAP_HIGH_INTEREST_DEBT,
    /** Fixed obligations are over their hard ceiling. */
    CAP_FIXED_OBLIGATIONS_OVER_CEILING,
    /** The savings rate over the window is negative. */
    CAP_NEGATIVE_SAVINGS
}
