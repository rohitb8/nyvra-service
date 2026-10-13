package com.rohit.nyvra.accounts;

/** Stored as {@code TEXT + CHECK} (V2__accounts.sql). */
public enum AccountStatus {
    /** Open and in use. */
    ACTIVE,
    /** Closed by the user; read-only. */
    CLOSED,
    /** Open but its balance has not been refreshed recently. */
    STALE
}
