package com.rohit.nyvra.networth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Daily trigger for {@link NetWorthSnapshotRunner}. It runs shortly after midnight in the display time zone so
 * every user gets a point for the new day even if they never open the app, which keeps the trend continuous.
 * ShedLock makes it run once across instances; the whole class is off unless {@code nyvra.jobs.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "nyvra.jobs.enabled", havingValue = "true")
public class NetWorthSnapshotJob {

    /** Does the actual work. */
    private final NetWorthSnapshotRunner runner;

    /**
     * Creates the job.
     *
     * @param runner the snapshot runner
     */
    public NetWorthSnapshotJob(NetWorthSnapshotRunner runner) {
        this.runner = runner;
    }

    /** Records today's snapshot for all users. */
    @Scheduled(cron = "${nyvra.networth.snapshot-cron:0 10 0 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "net-worth-daily-snapshot", lockAtMostFor = "PT30M")
    public void recordDailySnapshots() {
        runner.recordForAllUsers();
    }
}
