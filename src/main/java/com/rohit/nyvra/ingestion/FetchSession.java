package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pull of data under a consent. Idempotent per consent and data range through {@code dedupKey}, so
 * asking twice for the same range returns the same session instead of fetching again.
 */
@Entity
@Table(name = "fetch_session")
public class FetchSession extends IngestionEntity {

    /** Longest error text kept, so a verbose provider message cannot bloat the row. */
    private static final int MAX_ERROR_LENGTH = 500;

    /** The consent this fetch runs under. */
    @Column(name = "consent_id", nullable = false, updatable = false)
    private UUID consentId;

    /** The aggregator's own session id, when it issues one. */
    @Column(name = "session_id")
    private String sessionId;

    /** Distinct FI types the fetch returned. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fi_types")
    private List<String> fiTypes;

    /** Progress of the fetch. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FetchStatus status;

    /** When the fetch was requested. */
    @Column(name = "requested_at")
    private Instant requestedAt;

    /** When the fetch finished, successfully or not. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Short failure reason, set only when {@code status} is {@code FAILED}. */
    @Column(name = "error")
    private String error;

    /** Hash of consent and data range that makes the fetch idempotent. */
    @Column(name = "dedup_key", updatable = false)
    private String dedupKey;

    /** Required by JPA; not for application use. */
    protected FetchSession() {
        // for JPA
    }

    /**
     * Creates a session in {@code RUNNING}.
     *
     * @param consentId   the consent the fetch runs under, required
     * @param dedupKey    idempotency hash for consent and range, required
     * @param requestedAt when the fetch was requested, required
     */
    public FetchSession(UUID consentId, String dedupKey, Instant requestedAt) {
        this.consentId = Objects.requireNonNull(consentId, "consentId");
        this.dedupKey = Objects.requireNonNull(dedupKey, "dedupKey");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.status = FetchStatus.RUNNING;
    }

    /**
     * Marks the fetch finished successfully.
     *
     * @param fetchedTypes distinct FI types returned
     * @param at           completion time
     */
    public void complete(List<String> fetchedTypes, Instant at) {
        this.fiTypes = fetchedTypes;
        this.status = FetchStatus.COMPLETED;
        this.completedAt = at;
        this.error = null;
    }

    /**
     * Marks the fetch failed.
     *
     * @param reason short failure reason; truncated to a safe length
     * @param at     failure time
     */
    public void fail(String reason, Instant at) {
        this.status = FetchStatus.FAILED;
        this.completedAt = at;
        this.error = reason == null ? null
            : reason.substring(0, Math.min(reason.length(), MAX_ERROR_LENGTH));
    }

    /** @return the consent id */
    public UUID getConsentId() {
        return consentId;
    }

    /** @return the aggregator's session id, possibly {@code null} */
    public String getSessionId() {
        return sessionId;
    }

    /** @return the FI types returned, possibly {@code null} */
    public List<String> getFiTypes() {
        return fiTypes;
    }

    /** @return the fetch status */
    public FetchStatus getStatus() {
        return status;
    }

    /** @return when the fetch was requested */
    public Instant getRequestedAt() {
        return requestedAt;
    }

    /** @return when the fetch finished, or {@code null} while running */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /** @return the failure reason, or {@code null} */
    public String getError() {
        return error;
    }

    /** @return the idempotency hash */
    public String getDedupKey() {
        return dedupKey;
    }
}
