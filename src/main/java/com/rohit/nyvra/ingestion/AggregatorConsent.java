package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The user's consent for an Account Aggregator to share their financial data. Owns the state machine
 * {@link ConsentStatus}; every transition rejects an illegal source state with a 409
 * {@code INVALID_CONSENT_STATE}.
 */
@Entity
@Table(name = "aggregator_consent")
public class AggregatorConsent extends AbstractEntity {

    /** Domain code of the 409 raised when a transition is not allowed from the current status. */
    public static final String INVALID_STATE = "INVALID_CONSENT_STATE";

    /** Owning user; fixed at creation and used to scope every lookup. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The user's Account Aggregator handle, for example {@code someone@onemoney}. */
    @Column(name = "aa_handle", nullable = false)
    private String aaHandle;

    /** Financial information providers (banks etc.) the consent covers; may be empty for "all". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fip_list")
    private List<String> fipList;

    /** Reference the Account Aggregator issued when the consent was requested. */
    @Column(name = "consent_handle")
    private String consentHandle;

    /** Id of the approved consent artefact; set on activation and unique across consents. */
    @Column(name = "consent_id")
    private String consentId;

    /** Current lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConsentStatus status;

    /** First day of data the consent covers. */
    @Column(name = "data_range_from")
    private LocalDate dataRangeFrom;

    /** Last day of data the consent covers. */
    @Column(name = "data_range_to")
    private LocalDate dataRangeTo;

    /** How often data may be fetched, as an Account Aggregator frequency string such as {@code 1/DAY}. */
    @Column(name = "frequency")
    private String frequency;

    /** When the approved consent lapses; {@code null} until activation. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Required by JPA; not for application use. */
    protected AggregatorConsent() {
        // for JPA
    }

    /**
     * Creates a consent in {@code REQUESTED}.
     *
     * @param userId        owning user, required
     * @param aaHandle      aggregator handle, required
     * @param fipList       providers covered, may be {@code null}
     * @param consentHandle aggregator reference for the request
     * @param from          first day of data covered
     * @param to            last day of data covered
     * @param frequency     permitted fetch frequency, may be {@code null}
     */
    public AggregatorConsent(UUID userId, String aaHandle, List<String> fipList, String consentHandle,
                             LocalDate from, LocalDate to, String frequency) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.aaHandle = Objects.requireNonNull(aaHandle, "aaHandle");
        this.fipList = fipList;
        this.consentHandle = consentHandle;
        this.dataRangeFrom = from;
        this.dataRangeTo = to;
        this.frequency = frequency;
        this.status = ConsentStatus.REQUESTED;
    }

    /**
     * Moves {@code REQUESTED -> ACTIVE} once the user has approved the consent.
     *
     * @param approvedConsentId consent artefact id issued on approval, required
     * @param expiry            when the consent lapses
     * @throws ConflictException if the consent is not {@code REQUESTED}
     */
    public void activate(String approvedConsentId, Instant expiry) {
        requireStatus("activate", ConsentStatus.REQUESTED);
        this.consentId = Objects.requireNonNull(approvedConsentId, "approvedConsentId");
        this.expiresAt = expiry;
        this.status = ConsentStatus.ACTIVE;
    }

    /**
     * Moves {@code ACTIVE -> PAUSED}.
     *
     * @throws ConflictException if the consent is not {@code ACTIVE}
     */
    public void pause() {
        requireStatus("pause", ConsentStatus.ACTIVE);
        this.status = ConsentStatus.PAUSED;
    }

    /**
     * Moves {@code PAUSED -> ACTIVE}.
     *
     * @throws ConflictException if the consent is not {@code PAUSED}
     */
    public void resume() {
        requireStatus("resume", ConsentStatus.PAUSED);
        this.status = ConsentStatus.ACTIVE;
    }

    /**
     * Moves to {@code REVOKED} from any non-terminal state.
     *
     * @throws ConflictException if the consent is already {@code REVOKED} or {@code EXPIRED}
     */
    public void revoke() {
        requireStatus("revoke", ConsentStatus.REQUESTED, ConsentStatus.ACTIVE, ConsentStatus.PAUSED);
        this.status = ConsentStatus.REVOKED;
    }

    /**
     * Moves {@code ACTIVE} or {@code PAUSED} to {@code EXPIRED} once the consent has run past its expiry.
     *
     * @throws ConflictException if the consent is not {@code ACTIVE} or {@code PAUSED}
     */
    public void expire() {
        requireStatus("expire", ConsentStatus.ACTIVE, ConsentStatus.PAUSED);
        this.status = ConsentStatus.EXPIRED;
    }

    /**
     * Tells whether data may be fetched now: {@code ACTIVE} and not past its expiry.
     *
     * @param now the current instant
     * @return true when a fetch is allowed
     */
    public boolean canFetch(Instant now) {
        return status == ConsentStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * Rejects a transition whose source state is not one of the allowed ones.
     *
     * @param action  the attempted transition, for the message
     * @param allowed the states the transition may start from
     * @throws ConflictException if the current status is not allowed
     */
    private void requireStatus(String action, ConsentStatus... allowed) {
        for (ConsentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new ConflictException(INVALID_STATE, "Cannot %s a consent that is %s".formatted(action, status));
    }

    /** @return the owning user's id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the aggregator handle */
    public String getAaHandle() {
        return aaHandle;
    }

    /** @return the providers covered, possibly {@code null} */
    public List<String> getFipList() {
        return fipList;
    }

    /** @return the aggregator's reference for the request */
    public String getConsentHandle() {
        return consentHandle;
    }

    /** @return the approved consent artefact id, or {@code null} before activation */
    public String getConsentId() {
        return consentId;
    }

    /** @return the current status */
    public ConsentStatus getStatus() {
        return status;
    }

    /** @return first day of data covered */
    public LocalDate getDataRangeFrom() {
        return dataRangeFrom;
    }

    /** @return last day of data covered */
    public LocalDate getDataRangeTo() {
        return dataRangeTo;
    }

    /** @return the permitted fetch frequency, possibly {@code null} */
    public String getFrequency() {
        return frequency;
    }

    /** @return when the consent lapses, or {@code null} before activation */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
