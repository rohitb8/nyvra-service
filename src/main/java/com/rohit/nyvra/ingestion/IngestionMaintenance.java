package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping for the ingestion module: purging raw provider payloads once their retention has passed and
 * expiring consents that have run past their expiry. Kept apart from the scheduler
 * ({@link IngestionMaintenanceJobs}) so the logic can be run and tested without timers.
 */
@Service
public class IngestionMaintenance {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(IngestionMaintenance.class);

    /** Consents expired per transaction, so one run never holds a huge transaction open. */
    private static final int EXPIRY_BATCH = 200;

    /** Raw record persistence. */
    private final RawFinancialRecordRepository records;

    /** Consent persistence. */
    private final AggregatorConsentRepository consents;

    /** Publishes {@link ConsentExpired}. */
    private final ApplicationEventPublisher events;

    /**
     * Creates the service.
     *
     * @param records  raw record store
     * @param consents consent store
     * @param events   event publisher
     */
    public IngestionMaintenance(RawFinancialRecordRepository records, AggregatorConsentRepository consents,
                                ApplicationEventPublisher events) {
        this.records = records;
        this.consents = consents;
        this.events = events;
    }

    /**
     * Deletes raw records whose retention date is before {@code today} and whose fetch was normalised
     * successfully.
     *
     * @param today the current accounting date (UTC)
     * @return how many records were deleted
     */
    @Transactional
    public int purgeRawRecords(LocalDate today) {
        int deleted = records.deletePurgeable(today);
        if (deleted > 0) {
            log.info("Purged {} raw financial record(s) past retention", deleted);
        }
        return deleted;
    }

    /**
     * Marks every {@code ACTIVE} or {@code PAUSED} consent whose expiry is before {@code now} as
     * {@code EXPIRED} and publishes {@link ConsentExpired} for each.
     *
     * @param now the current instant
     * @return how many consents were expired
     */
    @Transactional
    public int expireConsents(Instant now) {
        int total = 0;
        List<AggregatorConsent> batch;
        do {
            batch = consents.findLapsed(EnumSet.of(ConsentStatus.ACTIVE, ConsentStatus.PAUSED), now,
                PageRequest.ofSize(EXPIRY_BATCH));
            for (AggregatorConsent consent : batch) {
                consent.expire();
                consents.save(consent);
                events.publishEvent(new ConsentExpired(consent.getUserId(), consent.getId()));
            }
            consents.flush();
            total += batch.size();
        } while (batch.size() == EXPIRY_BATCH);
        if (total > 0) {
            log.info("Expired {} consent(s)", total);
        }
        return total;
    }
}
