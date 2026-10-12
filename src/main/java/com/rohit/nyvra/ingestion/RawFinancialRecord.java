package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One provider payload exactly as received, kept encrypted until it has been normalised and its retention
 * date passes. The most sensitive data in the system: never returned by the API and never logged.
 */
@Entity
@Table(name = "raw_financial_record")
public class RawFinancialRecord extends IngestionEntity {

    /** The fetch that produced the record. */
    @Column(name = "fetch_session_id", nullable = false, updatable = false)
    private UUID fetchSessionId;

    /** Provider the record came from. */
    @Column(name = "fip_id")
    private String fipId;

    /** Provider-side reference of the account, as supplied. */
    @Column(name = "account_ref")
    private String accountRef;

    /** FI type of the payload. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false)
    private PayloadType payloadType;

    /** The payload JSON, encrypted at rest (AES-256-GCM) by the converter. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "raw_json")
    private String rawJson;

    /** When the record was received. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Day after which a scheduled job may hard-delete the record. */
    @Column(name = "purge_after", nullable = false)
    private LocalDate purgeAfter;

    /** Required by JPA; not for application use. */
    protected RawFinancialRecord() {
        // for JPA
    }

    /**
     * Creates a record.
     *
     * @param fetchSessionId the owning fetch, required
     * @param fipId          provider id
     * @param accountRef     provider-side account reference
     * @param payloadType    FI type, required
     * @param rawJson        payload JSON; encrypted on write
     * @param receivedAt     receipt time, required
     * @param purgeAfter     retention end date, required
     */
    public RawFinancialRecord(UUID fetchSessionId, String fipId, String accountRef, PayloadType payloadType,
                              String rawJson, Instant receivedAt, LocalDate purgeAfter) {
        this.fetchSessionId = Objects.requireNonNull(fetchSessionId, "fetchSessionId");
        this.fipId = fipId;
        this.accountRef = accountRef;
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.rawJson = rawJson;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter");
    }

    /** @return the owning fetch id */
    public UUID getFetchSessionId() {
        return fetchSessionId;
    }

    /** @return the provider id, possibly {@code null} */
    public String getFipId() {
        return fipId;
    }

    /** @return the provider-side account reference, possibly {@code null} */
    public String getAccountRef() {
        return accountRef;
    }

    /** @return the FI type */
    public PayloadType getPayloadType() {
        return payloadType;
    }

    /** @return the decrypted payload JSON */
    public String getRawJson() {
        return rawJson;
    }

    /** @return when the record was received */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    /** @return the retention end date */
    public LocalDate getPurgeAfter() {
        return purgeAfter;
    }
}
