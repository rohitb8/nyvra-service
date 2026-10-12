package com.rohit.nyvra.networth;

import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Records today's net-worth snapshot for every user. It is the body of the daily job, kept in its own bean so
 * it can be run and tested without the scheduler. One user's failure is logged and skipped so it cannot stop the
 * rest, and each user is stored in its own transaction.
 */
@Slf4j
@Service
public class NetWorthSnapshotRunner {

    /** Computes and stores a single user's snapshot. */
    private final NetWorthService netWorth;
    /** Lists the users to snapshot. */
    private final NetWorthSnapshotStore snapshots;

    /**
     * Creates the runner.
     *
     * @param netWorth  the net-worth service
     * @param snapshots the snapshot store, used to list users
     */
    public NetWorthSnapshotRunner(NetWorthService netWorth, NetWorthSnapshotStore snapshots) {
        this.netWorth = netWorth;
        this.snapshots = snapshots;
    }

    /**
     * Outcome of one run.
     *
     * @param recorded users whose snapshot was stored
     * @param failed   users whose snapshot failed and was skipped
     */
    public record Result(int recorded, int failed) {
    }

    /**
     * Stores today's snapshot for every user, replacing any earlier snapshot from the same day.
     *
     * @return how many snapshots were stored and how many failed
     */
    public Result recordForAllUsers() {
        int recorded = 0;
        int failed = 0;
        for (UUID userId : snapshots.allUserIds()) {
            try {
                netWorth.recordSnapshot(userId);
                recorded++;
            } catch (RuntimeException e) {
                failed++;
                log.error("Net-worth snapshot failed for user {}", userId, e);
            }
        }
        log.info("Net-worth snapshots recorded: {} ok, {} failed", recorded, failed);
        return new Result(recorded, failed);
    }
}
