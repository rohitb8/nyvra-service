package com.rohit.nyvra.common.partition;

import java.time.Clock;
import java.time.YearMonth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Keeps monthly partitions of {@code transaction} and {@code expense} created a few months ahead, so
 * day-to-day inserts never fall into the {@code DEFAULT} partition. Plain DDL rather than
 * {@code pg_partman} — {@code database/decisions.md} §2. ShedLock makes it run once across instances.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nyvra.jobs.enabled", havingValue = "true")
public class MonthlyRangePartitionMaintenance {

    private final MonthlyPartitions partitions;
    private final int monthsAhead;
    private final Clock clock;

    public MonthlyRangePartitionMaintenance(
        MonthlyPartitions partitions,
        @Value("${nyvra.partitions.months-ahead:3}") int monthsAhead
    ) {
        this.partitions = partitions;
        this.monthsAhead = monthsAhead;
        this.clock = Clock.systemUTC();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${nyvra.partitions.cron:0 15 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "monthly-range-partition-maintenance", lockAtMostFor = "PT10M")
    public void createUpcomingPartitions() {
        YearMonth current = YearMonth.now(clock);
        for (String table : MonthlyPartitions.PARTITIONED_TABLES) {
            for (int i = 0; i <= monthsAhead; i++) {
                partitions.ensureMonth(table, current.plusMonths(i));
            }
        }
        log.info("Monthly partitions ensured through {}", current.plusMonths(monthsAhead));
    }
}
