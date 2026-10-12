package com.rohit.nyvra.user;

/**
 * How much investment risk a user is comfortable with. Stored as {@code TEXT} guarded by the
 * {@code CHECK} constraint on {@code user_preferences.risk_band}; the names must stay in step with it.
 */
public enum RiskBand {
    /** Prefers stability; the equity target is shaded down. */
    CONSERVATIVE,
    /** The default; the equity target follows the age glide path unchanged. */
    BALANCED,
    /** Accepts volatility for growth; the equity target is shaded up. */
    AGGRESSIVE
}
