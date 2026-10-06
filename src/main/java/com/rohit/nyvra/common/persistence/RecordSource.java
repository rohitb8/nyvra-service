package com.rohit.nyvra.common.persistence;

/**
 * Where a user-visible row came from — the audit {@code source} column every context carries
 * ({@code docs/engineering/DATABASE_DESIGN.md} "Global conventions"). Stored as {@code TEXT + CHECK}.
 */
public enum RecordSource {
    AA,
    MANUAL,
    DERIVED,
    GMAIL
}
