package com.rohit.nyvra.income;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A payslip backing an {@link IncomeEntry}. The file itself lives in object storage under
 * {@code objectKey}; only its metadata and the parsed salary breakup (🔒, a JSON string) are stored here.
 */
@Entity
@Table(name = "payslip_document")
public class PayslipDocument extends AbstractEntity {

    /** Id of the entry this payslip belongs to; fixed at creation. */
    @Column(name = "income_entry_id", nullable = false, updatable = false)
    private UUID incomeEntryId;

    /** Key of the file in object storage. */
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** Original file name as uploaded, shown back to the user. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** Media type of the stored file: PDF, PNG or JPEG. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    /** Size of the stored file in bytes. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Where parsing stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false)
    private PayslipParseStatus parseStatus;

    /** Parsed breakup as JSON, encrypted at rest; {@code null} until parsing succeeds. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "parsed_fields")
    private String parsedFields;

    /** When the file was uploaded. */
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /** Required by JPA; not for application use. */
    protected PayslipDocument() {
        // for JPA
    }

    /**
     * Creates a freshly uploaded payslip, waiting to be parsed.
     *
     * @param incomeEntryId the entry it belongs to
     * @param objectKey     where the file is stored
     * @param fileName      the uploaded file's name
     * @param contentType   media type of the file
     * @param sizeBytes     size of the file in bytes
     * @param uploadedAt    upload time
     */
    public PayslipDocument(UUID incomeEntryId, String objectKey, String fileName, String contentType,
                           long sizeBytes, Instant uploadedAt) {
        this.incomeEntryId = Objects.requireNonNull(incomeEntryId, "incomeEntryId");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.sizeBytes = sizeBytes;
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt");
        this.parseStatus = PayslipParseStatus.PENDING;
    }

    /**
     * Stores the parsed breakup and marks the payslip parsed.
     *
     * @param parsedFieldsJson the parsed breakup as JSON; encrypted at rest
     */
    public void recordParsedFields(String parsedFieldsJson) {
        this.parsedFields = parsedFieldsJson;
        this.parseStatus = PayslipParseStatus.PARSED;
    }

    /** Marks the payslip as unreadable by the parser. */
    public void markParseFailed() {
        this.parseStatus = PayslipParseStatus.FAILED;
    }

    /** @return the owning entry's id */
    public UUID getIncomeEntryId() {
        return incomeEntryId;
    }

    /** @return the object-storage key of the file */
    public String getObjectKey() {
        return objectKey;
    }

    /** @return the uploaded file's name */
    public String getFileName() {
        return fileName;
    }

    /** @return the file's media type */
    public String getContentType() {
        return contentType;
    }

    /** @return the file size in bytes */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** @return where parsing stands */
    public PayslipParseStatus getParseStatus() {
        return parseStatus;
    }

    /** @return the parsed breakup as JSON, or {@code null} until parsing succeeds */
    public String getParsedFields() {
        return parsedFields;
    }

    /** @return when the file was uploaded */
    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
