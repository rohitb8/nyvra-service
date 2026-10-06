package com.rohit.nyvra.income;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A payslip backing an {@link IncomeEntry}. The file itself lives in object storage under
 * {@code objectKey}; only the parsed salary breakup (🔒, a JSON string) is stored here.
 */
@Entity
@Table(name = "payslip_document")
public class PayslipDocument extends AbstractEntity {

    @Column(name = "income_entry_id", nullable = false, updatable = false)
    private UUID incomeEntryId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "parsed_fields")
    private String parsedFields;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected PayslipDocument() {
        // for JPA
    }

    public PayslipDocument(UUID incomeEntryId, String objectKey, Instant uploadedAt) {
        this.incomeEntryId = Objects.requireNonNull(incomeEntryId, "incomeEntryId");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt");
    }

    /** @param parsedFieldsJson the parsed breakup as JSON; encrypted at rest */
    public void recordParsedFields(String parsedFieldsJson) {
        this.parsedFields = parsedFieldsJson;
    }

    public UUID getIncomeEntryId() {
        return incomeEntryId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getParsedFields() {
        return parsedFields;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
