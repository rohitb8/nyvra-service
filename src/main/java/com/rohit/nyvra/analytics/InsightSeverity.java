package com.rohit.nyvra.analytics;

/** How serious an insight is; also its display precedence, most serious first. */
public enum InsightSeverity {
    /** Worth knowing; no action required. */
    INFO,
    /** A threshold is breached; action advised. */
    WARN,
    /** A fundamental is broken; act first. */
    CRITICAL
}
