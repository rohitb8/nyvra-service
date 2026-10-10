package com.rohit.nyvra.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

/** Exercises the V13 Ingestion DDL directly — the module has no entities or repositories yet. */
class IngestionSchemaIntegrationTest extends AbstractIntegrationTest {

    /** Raw SQL access, since there are no entities to go through. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Used to create the owning user rows. */
    @Autowired
    private UserProfileRepository users;

    /** Consent status and data range are validated, and the external consent id is unique. */
    @Test
    void consentValidatesStatusRangeAndUniqueConsentId() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());

        insertConsent(user.getId(), "REQUESTED", "c-1", "2025-01-01", "2025-06-01");

        assertThatThrownBy(() -> insertConsent(user.getId(), "UNKNOWN", "c-2", "2025-01-01", "2025-06-01"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertConsent(user.getId(), "ACTIVE", "c-3", "2025-06-01", "2025-01-01"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertConsent(user.getId(), "ACTIVE", "c-1", "2025-01-01", "2025-06-01"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A fetch session is idempotent per dedup key, and its status is constrained. */
    @Test
    void fetchSessionIsIdempotentPerDedupKey() {
        UUID consent = insertConsent(users.save(UserProfileMother.aUserProfile()).getId(),
            "ACTIVE", "c-10", "2025-01-01", "2025-06-01");

        insertSession(consent, "PENDING", "hash-1");

        assertThatThrownBy(() -> insertSession(consent, "PENDING", "hash-1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSession(consent, "DONE", "hash-2"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Raw records store ciphertext bytes, require a purge date and a known payload type. */
    @Test
    void rawRecordStoresCiphertextAndRequiresPurgeDateAndKnownType() {
        UUID consent = insertConsent(users.save(UserProfileMother.aUserProfile()).getId(),
            "ACTIVE", "c-20", "2025-01-01", "2025-06-01");
        UUID session = insertSession(consent, "COMPLETED", "hash-20");

        jdbc.update("INSERT INTO raw_financial_record (id, fetch_session_id, payload_type, raw_json, purge_after) "
            + "VALUES (?, ?, 'DEPOSIT', ?, DATE '2025-07-01')", UUID.randomUUID(), session, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO raw_financial_record (id, fetch_session_id, payload_type) VALUES (?, ?, 'DEPOSIT')",
            UUID.randomUUID(), session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO raw_financial_record (id, fetch_session_id, payload_type, purge_after) "
                + "VALUES (?, ?, 'CRYPTO', DATE '2025-07-01')", UUID.randomUUID(), session))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A normalisation run can be recorded for a session but not with a negative event count. */
    @Test
    void normalisationRunRejectsNegativeEventCount() {
        UUID consent = insertConsent(users.save(UserProfileMother.aUserProfile()).getId(),
            "ACTIVE", "c-30", "2025-01-01", "2025-06-01");
        UUID session = insertSession(consent, "COMPLETED", "hash-30");

        jdbc.update("INSERT INTO normalisation_run (id, fetch_session_id, produced_events, status) "
            + "VALUES (?, ?, 5, 'OK')", UUID.randomUUID(), session);

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO normalisation_run (id, fetch_session_id, produced_events) VALUES (?, ?, -1)",
            UUID.randomUUID(), session)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserts one {@code aggregator_consent} row.
     *
     * @param userId     owning user
     * @param status     consent status
     * @param consentId  external consent id (unique)
     * @param rangeFrom  ISO start date of the data range
     * @param rangeTo    ISO end date of the data range
     * @return the new row id
     */
    private UUID insertConsent(UUID userId, String status, String consentId, String rangeFrom, String rangeTo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO aggregator_consent (id, user_id, aa_handle, consent_id, status, data_range_from, "
            + "data_range_to) VALUES (?, ?, 'user@aa', ?, ?, DATE '" + rangeFrom + "', DATE '" + rangeTo + "')",
            id, userId, consentId, status);
        return id;
    }

    /**
     * Inserts one {@code fetch_session} row.
     *
     * @param consentId parent consent
     * @param status    fetch status
     * @param dedupKey  idempotency key
     * @return the new row id
     */
    private UUID insertSession(UUID consentId, String status, String dedupKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fetch_session (id, consent_id, status, dedup_key) VALUES (?, ?, ?, ?)",
            id, consentId, status, dedupKey);
        return id;
    }
}
