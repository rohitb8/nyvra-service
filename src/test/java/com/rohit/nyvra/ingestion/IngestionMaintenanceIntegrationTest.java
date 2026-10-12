package com.rohit.nyvra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Integration tests for {@link IngestionMaintenance} against a real PostgreSQL: raw-record purge and consent expiry. */
@Import(IngestionMaintenanceIntegrationTest.ExpiryRecorder.class)
class IngestionMaintenanceIntegrationTest extends AbstractIntegrationTest {

    /** Records the {@link ConsentExpired} events the service publishes. */
    @TestConfiguration
    @Component
    static class ExpiryRecorder {

        /** Events seen during the current test. */
        static final List<ConsentExpired> SEEN = new CopyOnWriteArrayList<>();

        /**
         * Remembers an event.
         *
         * @param event the published event
         */
        @EventListener
        void on(ConsentExpired event) {
            SEEN.add(event);
        }
    }

    /** Unit under test. */
    @Autowired
    private IngestionMaintenance maintenance;

    /** Consents. */
    @Autowired
    private AggregatorConsentRepository consents;

    /** Fetch sessions. */
    @Autowired
    private FetchSessionRepository sessions;

    /** Raw records. */
    @Autowired
    private RawFinancialRecordRepository records;

    /** Normalisation runs. */
    @Autowired
    private NormalisationRunRepository runs;

    /** Direct SQL for fixtures. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Clears recorded events. */
    @BeforeEach
    void reset() {
        ExpiryRecorder.SEEN.clear();
    }

    /**
     * Inserts a user profile row and returns its id.
     *
     * @return the new user's id
     */
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile (id, keycloak_subject, display_name) VALUES (?, ?, 'T')", id, "sub-" + id);
        return id;
    }

    /**
     * Stores a consent in the given state with the given expiry.
     *
     * @param userId  owner
     * @param status  wanted status
     * @param expires expiry, may be null
     * @return the stored consent id
     */
    private UUID consent(UUID userId, ConsentStatus status, Instant expires) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO aggregator_consent (id, user_id, aa_handle, status, expires_at, consent_id)
            VALUES (?, ?, 'h@aa', ?, ?, ?)""", id, userId, status.name(),
            expires == null ? null : java.sql.Timestamp.from(expires), "cid-" + id);
        return id;
    }

    /** Active and paused consents past expiry become EXPIRED with an event; others are left alone. */
    @Test
    void expiresOnlyLapsedActiveAndPausedConsents() {
        UUID user = newUser();
        Instant now = Instant.now();
        UUID lapsedActive = consent(user, ConsentStatus.ACTIVE, now.minus(1, ChronoUnit.HOURS));
        UUID lapsedPaused = consent(user, ConsentStatus.PAUSED, now.minus(1, ChronoUnit.DAYS));
        UUID future = consent(user, ConsentStatus.ACTIVE, now.plus(1, ChronoUnit.DAYS));
        UUID noExpiry = consent(user, ConsentStatus.ACTIVE, null);
        UUID revoked = consent(user, ConsentStatus.REVOKED, now.minus(1, ChronoUnit.DAYS));

        int expired = maintenance.expireConsents(now);

        assertThat(expired).isGreaterThanOrEqualTo(2);
        assertThat(consents.findById(lapsedActive).orElseThrow().getStatus()).isEqualTo(ConsentStatus.EXPIRED);
        assertThat(consents.findById(lapsedPaused).orElseThrow().getStatus()).isEqualTo(ConsentStatus.EXPIRED);
        assertThat(consents.findById(future).orElseThrow().getStatus()).isEqualTo(ConsentStatus.ACTIVE);
        assertThat(consents.findById(noExpiry).orElseThrow().getStatus()).isEqualTo(ConsentStatus.ACTIVE);
        assertThat(consents.findById(revoked).orElseThrow().getStatus()).isEqualTo(ConsentStatus.REVOKED);
        assertThat(ExpiryRecorder.SEEN).extracting(ConsentExpired::consentId)
            .contains(lapsedActive, lapsedPaused).doesNotContain(future, noExpiry, revoked);
        assertThat(maintenance.expireConsents(now)).isZero();
    }

    /**
     * Stores a fetch session with one raw record.
     *
     * @param userId       owner of the consent
     * @param purgeAfter   retention date of the record
     * @param normalised   whether a completed normalisation run exists
     * @return the raw record's fetch session id
     */
    private UUID fetchWithRecord(UUID userId, LocalDate purgeAfter, boolean normalised) {
        UUID consentId = consent(userId, ConsentStatus.ACTIVE, null);
        FetchSession session = sessions.save(new FetchSession(consentId, "k-" + UUID.randomUUID(), Instant.now()));
        records.save(new RawFinancialRecord(session.getId(), "F", "a", PayloadType.DEPOSIT, "{}", Instant.now(),
            purgeAfter));
        if (normalised) {
            runs.save(new NormalisationRun(session.getId(), 0, java.util.Map.of()));
        }
        return session.getId();
    }

    /** Only records past retention and with a completed normalisation run are deleted. */
    @Test
    void purgesOnlyExpiredNormalisedRecords() {
        UUID user = newUser();
        LocalDate today = LocalDate.now();
        UUID due = fetchWithRecord(user, today.minusDays(1), true);
        UUID dueButNotNormalised = fetchWithRecord(user, today.minusDays(1), false);
        UUID notDue = fetchWithRecord(user, today.plusDays(5), true);
        UUID dueToday = fetchWithRecord(user, today, true);

        maintenance.purgeRawRecords(today);

        assertThat(records.findByFetchSessionIdOrderByReceivedAtAsc(due)).isEmpty();
        assertThat(records.findByFetchSessionIdOrderByReceivedAtAsc(dueButNotNormalised)).hasSize(1);
        assertThat(records.findByFetchSessionIdOrderByReceivedAtAsc(notDue)).hasSize(1);
        assertThat(records.findByFetchSessionIdOrderByReceivedAtAsc(dueToday)).hasSize(1);
    }
}
