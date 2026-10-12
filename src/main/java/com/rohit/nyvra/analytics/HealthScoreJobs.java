package com.rohit.nyvra.analytics;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the health-score recomputes: a short-interval flush of users whose debounce period has passed,
 * and a daily pass over everyone so confidence decay and the trend stay current (HEALTH_SCORE_SPEC section
 * 5). Off unless {@code nyvra.jobs.enabled=true}. The flush is per instance, because the pending set is in
 * memory; the daily pass takes a ShedLock so it runs once across instances.
 */
@Component
@ConditionalOnProperty(name = "nyvra.jobs.enabled", havingValue = "true")
public class HealthScoreJobs {

    /** Does the actual work. */
    private final HealthScoreRecomputer recomputer;

    /**
     * Creates the jobs.
     *
     * @param recomputer the recomputer
     */
    public HealthScoreJobs(HealthScoreRecomputer recomputer) {
        this.recomputer = recomputer;
    }

    /** Recomputes users whose inputs changed and have been quiet for the debounce period. */
    @Scheduled(fixedDelayString = "${nyvra.health-score-inputs.flush-interval-ms:15000}")
    public void flushDue() {
        recomputer.flushDue();
    }

    /** Recomputes every user's score once a day, after the net-worth snapshots. */
    @Scheduled(cron = "${nyvra.health-score-inputs.daily-cron:0 30 0 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "health-score-daily-recompute", lockAtMostFor = "PT30M")
    public void recomputeAll() {
        recomputer.recomputeAll();
    }
}
