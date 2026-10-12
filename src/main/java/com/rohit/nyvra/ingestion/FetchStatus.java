package com.rohit.nyvra.ingestion;

/** Progress of a {@link FetchSession}. */
public enum FetchStatus {

    /** Created but not started. */
    PENDING,

    /** Data is being requested from the Account Aggregator or normalised. */
    RUNNING,

    /** Raw records stored and normalised. */
    COMPLETED,

    /** The fetch failed; see the session's {@code error}. */
    FAILED
}
