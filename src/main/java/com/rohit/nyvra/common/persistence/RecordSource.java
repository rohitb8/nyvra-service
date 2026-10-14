package com.rohit.nyvra.common.persistence;

/**
 * Where a user-visible row came from — the audit {@code source} column every context carries
 * ({@code docs/engineering/DATABASE_DESIGN.md} "Global conventions"). Stored as {@code TEXT + CHECK}.
 */
public enum RecordSource {
    /** Fetched through the Account Aggregator. */
    AA,
    /** Entered by the user. */
    MANUAL,
    /** Computed by the system from other records. */
    DERIVED,
    /** Parsed from the user's email. */
    GMAIL
}
