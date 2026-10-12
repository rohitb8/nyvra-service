package com.rohit.nyvra.ingestion;

/**
 * Lifecycle of an {@link AggregatorConsent}: {@code REQUESTED -> ACTIVE -> (PAUSED <-> ACTIVE) -> REVOKED | EXPIRED}.
 * No data may be fetched unless the consent is {@code ACTIVE}.
 */
public enum ConsentStatus {

    /** Asked of the Account Aggregator; the user has not approved yet. */
    REQUESTED,

    /** Approved by the user; data may be fetched. */
    ACTIVE,

    /** Temporarily switched off by the user; can be resumed. */
    PAUSED,

    /** Withdrawn by the user; terminal. */
    REVOKED,

    /** Ran past its expiry; terminal. */
    EXPIRED
}
