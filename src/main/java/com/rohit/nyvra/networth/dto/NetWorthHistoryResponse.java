package com.rohit.nyvra.networth.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.networth.Granularity;
import com.rohit.nyvra.networth.NetWorthSnapshotStore;

/**
 * The net-worth trend over a date range.
 *
 * @param granularity bucket size
 * @param from        first day requested
 * @param to          last day requested
 * @param points      one point per bucket that has a snapshot, oldest first; empty buckets are omitted
 * @param change      movement from the first to the last point; absent with fewer than two points
 */
public record NetWorthHistoryResponse(
    Granularity granularity,
    LocalDate from,
    LocalDate to,
    List<Point> points,
    Change change) {

    /**
     * One point of the series.
     *
     * @param asOf             the snapshot day's start
     * @param totalAssets      assets on that day
     * @param totalLiabilities liabilities on that day
     * @param netWorth         net worth on that day
     */
    public record Point(Instant asOf, MoneyDto totalAssets, MoneyDto totalLiabilities, MoneyDto netWorth) {
    }

    /**
     * Movement across the series, computed by the server so the client never does the arithmetic.
     *
     * @param amount  last net worth minus first
     * @param percent {@code amount} as a percentage of the first net worth, one decimal; absent when the first
     *                net worth is zero or negative
     */
    public record Change(MoneyDto amount, Double percent) {
    }

    /**
     * Builds the response from stored points.
     *
     * @param granularity bucket size
     * @param from        first day requested
     * @param to          last day requested
     * @param stored      the stored points, oldest first
     * @return the response
     */
    public static NetWorthHistoryResponse from(Granularity granularity, LocalDate from, LocalDate to,
                                               List<NetWorthSnapshotStore.Point> stored) {
        List<Point> points = stored.stream()
            .map(p -> new Point(p.asOf(), MoneyDto.from(p.totalAssets()), MoneyDto.from(p.totalLiabilities()),
                MoneyDto.from(p.netWorth())))
            .toList();
        Change change = null;
        if (stored.size() >= 2) {
            var first = stored.get(0).netWorth();
            var last = stored.get(stored.size() - 1).netWorth();
            Double percent = first.amount().signum() > 0
                ? last.minus(first).amount().multiply(BigDecimal.valueOf(100))
                    .divide(first.amount(), 1, RoundingMode.HALF_UP).doubleValue()
                : null;
            change = new Change(MoneyDto.from(last.minus(first)), percent);
        }
        return new NetWorthHistoryResponse(granularity, from, to, points, change);
    }
}
