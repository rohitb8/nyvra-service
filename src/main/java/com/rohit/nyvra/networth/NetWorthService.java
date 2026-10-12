package com.rohit.nyvra.networth;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rohit.nyvra.accounts.FinancialAccountRepository;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.networth.dto.NetWorthHistoryResponse;
import com.rohit.nyvra.networth.dto.NetWorthResponse;
import com.rohit.nyvra.user.CurrentUserService;

/**
 * Net-worth use cases for the signed-in user: the live figure, the daily snapshot that feeds the trend, and the
 * trend itself. The figure is always computed on demand from accounts, manual entries and the portfolio, so it
 * is never stale; snapshots only exist to draw the history.
 */
@Service
public class NetWorthService {

    /** The user's display time zone, which decides where a snapshot day starts. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of(NetWorthSnapshotStore.DISPLAY_ZONE);

    /** Days of history returned when the caller gives no range. */
    static final int DEFAULT_HISTORY_DAYS = 180;

    /** Longest range a single history call may cover, to bound the query. */
    static final int MAX_HISTORY_DAYS = 3660;

    /** Account persistence. */
    private final FinancialAccountRepository accounts;
    /** Manual entry persistence. */
    private final ManualAssetLiabilityRepository manualItems;
    /** Snapshot persistence. */
    private final NetWorthSnapshotStore snapshots;
    /** The portfolio module's contribution, if that module provides one. */
    private final ObjectProvider<PortfolioValueSource> portfolio;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param accounts    the account repository
     * @param manualItems the manual entry repository
     * @param snapshots   the snapshot store
     * @param portfolio   the optional portfolio valuation seam; absent means an empty portfolio
     * @param currentUser the signed-in user resolver
     */
    public NetWorthService(FinancialAccountRepository accounts, ManualAssetLiabilityRepository manualItems,
                           NetWorthSnapshotStore snapshots, ObjectProvider<PortfolioValueSource> portfolio,
                           CurrentUserService currentUser) {
        this.accounts = accounts;
        this.manualItems = manualItems;
        this.snapshots = snapshots;
        this.portfolio = portfolio;
        this.currentUser = currentUser;
    }

    /**
     * Values the caller's net worth right now without storing anything.
     *
     * @return the live figure with its breakdown
     */
    @Transactional(readOnly = true)
    public NetWorthResponse current() {
        UUID userId = currentUser.currentUser().getId();
        return NetWorthResponse.from(compute(userId, today()), Instant.now());
    }

    /**
     * Values the caller's net worth and stores it as today's snapshot, replacing an earlier one from the same
     * day.
     *
     * @return the stored figure
     */
    @Transactional
    public NetWorthResponse recordSnapshot() {
        return recordSnapshot(currentUser.currentUser().getId());
    }

    /**
     * Values a user's net worth and stores it as today's snapshot.
     *
     * @param userId the owner
     * @return the stored figure
     */
    @Transactional
    public NetWorthResponse recordSnapshot(UUID userId) {
        LocalDate today = today();
        NetWorthComputation computation = compute(userId, today);
        snapshots.upsert(userId, today.atStartOfDay(DISPLAY_ZONE).toInstant(), computation);
        return NetWorthResponse.from(computation, Instant.now());
    }

    /**
     * Returns the caller's net-worth trend.
     *
     * @param from        first day, default 180 days before {@code to}
     * @param to          last day, default today
     * @param granularity bucket size, default {@link Granularity#WEEK}
     * @return the trend
     * @throws BadRequestException if {@code from} is after {@code to} or the range is longer than ten years
     */
    @Transactional(readOnly = true)
    public NetWorthHistoryResponse history(LocalDate from, LocalDate to, Granularity granularity) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        Granularity bucket = granularity != null ? granularity : Granularity.WEEK;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        if (start.plusDays(MAX_HISTORY_DAYS).isBefore(end)) {
            throw new BadRequestException("The range can span at most ten years");
        }
        UUID userId = currentUser.currentUser().getId();
        List<NetWorthSnapshotStore.Point> points = snapshots.history(userId,
            start.atStartOfDay(DISPLAY_ZONE).toInstant(), end.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant(),
            bucket);
        return NetWorthHistoryResponse.from(bucket, start, end, points);
    }

    /**
     * Gathers the inputs and values them.
     *
     * @param userId the owner
     * @param today  the day used to judge staleness
     * @return the computation
     */
    private NetWorthComputation compute(UUID userId, LocalDate today) {
        PortfolioValueSource source = portfolio.getIfAvailable();
        Money portfolioValue = source == null ? Money.zero(Money.INR) : source.currentValue(userId);
        return NetWorthCalculator.calculate(
            accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId),
            manualItems.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId),
            portfolioValue, today);
    }

    /**
     * Returns today's date in the display time zone.
     *
     * @return today
     */
    static LocalDate today() {
        return LocalDate.now(DISPLAY_ZONE);
    }
}
