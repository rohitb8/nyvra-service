package com.rohit.nyvra.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps stored health scores current. {@link #markDirty} notes that a user's inputs changed; once a user
 * has been quiet for the debounce period, {@link #flushDue} recomputes them (HEALTH_SCORE_SPEC section 5,
 * "debounced 60s per user"). {@link #recomputeAll} is the daily pass that keeps confidence decay and the
 * trend current. {@link HealthScoreService#record} already skips unchanged inputs, so extra recomputes
 * cost a read but never a duplicate row.
 *
 * <p>The pending set is in memory: a restart drops it, and the daily pass repairs anything missed. A
 * failure for one user is logged and never stops the others.
 */
@Slf4j
@Service
public class HealthScoreRecomputer {

    /** Users with unrecomputed changes, and when each last changed. */
    private final Map<UUID, Instant> pending = new ConcurrentHashMap<>();
    /** Gathers a user's drivers. */
    private final DriverMetricsAssembler assembler;
    /** Scores and stores them. */
    private final HealthScoreService service;
    /** Lists the users for the daily pass. */
    private final JdbcTemplate jdbc;
    /** Supplies the debounce period. */
    private final HealthScoreInputRules inputs;

    /**
     * Creates the recomputer.
     *
     * @param assembler gathers a user's drivers
     * @param service   scores and stores them
     * @param jdbc      JDBC access, to list users
     * @param inputs    window and debounce parameters
     */
    public HealthScoreRecomputer(DriverMetricsAssembler assembler, HealthScoreService service, JdbcTemplate jdbc,
                                 HealthScoreInputRules inputs) {
        this.assembler = assembler;
        this.service = service;
        this.jdbc = jdbc;
        this.inputs = inputs;
    }

    /**
     * Notes that a user's inputs changed, restarting their debounce period.
     *
     * @param userId the user whose income, spending, accounts, holdings or goals changed
     */
    public void markDirty(UUID userId) {
        pending.put(userId, Instant.now());
    }

    /**
     * Recomputes every user who has been quiet for the debounce period.
     *
     * @return how many users were recomputed
     */
    public int flushDue() {
        return flushDue(Instant.now());
    }

    /**
     * Recomputes every user whose last change is at least the debounce period before {@code now}.
     *
     * @param now the instant to measure quiet time against
     * @return how many users were recomputed
     */
    int flushDue(Instant now) {
        Instant cutoff = now.minus(Duration.ofSeconds(inputs.debounceSeconds()));
        List<Map.Entry<UUID, Instant>> due = new ArrayList<>();
        pending.forEach((userId, changedAt) -> {
            if (!changedAt.isAfter(cutoff)) {
                due.add(Map.entry(userId, changedAt));
            }
        });
        int done = 0;
        for (Map.Entry<UUID, Instant> entry : due) {
            // Remove only if no newer change arrived while we were deciding; otherwise keep waiting.
            if (pending.remove(entry.getKey(), entry.getValue()) && tryRecompute(entry.getKey())) {
                done++;
            }
        }
        return done;
    }

    /**
     * Recomputes one user now.
     *
     * @param userId the user
     * @return the stored-or-skipped result
     */
    public HealthScoreResult recompute(UUID userId) {
        return service.record(userId, assembler.assemble(userId), Instant.now());
    }

    /**
     * Recomputes every user (the daily pass).
     *
     * @return how many users were recomputed
     */
    public int recomputeAll() {
        int done = 0;
        int failed = 0;
        for (UUID userId : jdbc.queryForList("SELECT id FROM user_profile ORDER BY created_at", UUID.class)) {
            if (tryRecompute(userId)) {
                done++;
            } else {
                failed++;
            }
        }
        log.info("Health scores recomputed: {} ok, {} failed", done, failed);
        return done;
    }

    /**
     * Recomputes one user, logging instead of throwing.
     *
     * @param userId the user
     * @return {@code true} if it succeeded
     */
    private boolean tryRecompute(UUID userId) {
        try {
            recompute(userId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Health-score recompute failed for user {}", userId, e);
            return false;
        }
    }
}
