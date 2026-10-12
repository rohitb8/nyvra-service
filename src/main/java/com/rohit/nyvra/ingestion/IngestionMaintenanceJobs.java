package com.rohit.nyvra.ingestion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timers for {@link IngestionMaintenance}. Only active when {@code nyvra.jobs.enabled=true}, like the other
 * scheduled jobs, and ShedLock makes each run once across instances. Schedules are overridable with
 * {@code nyvra.ingestion.purge.cron} (default 03:00 UTC daily) and {@code nyvra.ingestion.consent-expiry.cron}
 * (default hourly).
 */
@Component
@ConditionalOnProperty(name = "nyvra.jobs.enabled", havingValue = "true")
public class IngestionMaintenanceJobs {

    /** The work the timers trigger. */
    private final IngestionMaintenance maintenance;

    /** Time source. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Creates the scheduler.
     *
     * @param maintenance the work to run
     */
    public IngestionMaintenanceJobs(IngestionMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    /** Purges raw provider payloads past their retention. */
    @Scheduled(cron = "${nyvra.ingestion.purge.cron:0 0 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "ingestion-raw-record-purge", lockAtMostFor = "PT30M")
    public void purgeRawRecords() {
        maintenance.purgeRawRecords(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** Expires consents that ran past their expiry. */
    @Scheduled(cron = "${nyvra.ingestion.consent-expiry.cron:0 5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "ingestion-consent-expiry", lockAtMostFor = "PT10M")
    public void expireConsents() {
        maintenance.expireConsents(clock.instant());
    }
}
