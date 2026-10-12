package com.rohit.nyvra.portfolio.dto;

import java.time.LocalDate;
import java.util.List;

import com.rohit.nyvra.common.money.MoneyDto;

/**
 * The caller's INR portfolio value over time. Each point values today's open holdings, at today's quantities
 * and costs, with the price in force at the end of that day (Asia/Kolkata); a holding counts from the day it
 * was opened. Quantity changes are not replayed, so earlier points show how the current positions performed.
 *
 * @param currency currency of every amount (INR)
 * @param interval spacing between points
 * @param points   one point per interval step, oldest first
 */
public record ValuationHistoryResponse(String currency, Interval interval, List<Point> points) {

    /** Spacing between history points. */
    public enum Interval {
        /** One point per day. */
        DAILY,
        /** One point every seven days, counting back from the end date. */
        WEEKLY,
        /** One point at each month end that falls in range, plus the end date. */
        MONTHLY
    }

    /**
     * One dated value.
     *
     * @param date                the day measured (end of day, Asia/Kolkata)
     * @param value               market value of holdings that had a price; zero when none did
     * @param invested            cost of the holdings open that day whose cost is known
     * @param pricedHoldingCount  open holdings that had a price on or before that day
     * @param unpricedHoldingCount open holdings with no price yet that day
     */
    public record Point(LocalDate date, MoneyDto value, MoneyDto invested, int pricedHoldingCount,
                        int unpricedHoldingCount) {
    }
}
