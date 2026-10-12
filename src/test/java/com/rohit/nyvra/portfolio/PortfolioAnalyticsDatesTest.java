package com.rohit.nyvra.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse.Interval;
import org.junit.jupiter.api.Test;

/** Unit tests of the date helpers behind the valuation history. */
class PortfolioAnalyticsDatesTest {

    /** Daily covers every day, ends included. */
    @Test
    void dailyIncludesEveryDay() {
        assertThat(PortfolioAnalyticsService.dates(LocalDate.parse("2026-01-30"), LocalDate.parse("2026-02-02"),
            Interval.DAILY)).hasSize(4);
    }

    /** Weekly steps back from the end date so the last point is always the end date; the start is kept if it lands on one. */
    @Test
    void weeklyEndsOnTheEndDate() {
        var dates = PortfolioAnalyticsService.dates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-22"),
            Interval.WEEKLY);
        assertThat(dates).containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-08"),
            LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-22"));
    }

    /** Monthly gives month ends in range plus the end date. */
    @Test
    void monthlyUsesMonthEndsAndTheEndDate() {
        var dates = PortfolioAnalyticsService.dates(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-03-10"),
            Interval.MONTHLY);
        assertThat(dates).containsExactly(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"),
            LocalDate.parse("2026-03-10"));
    }

    /** A day ends one microsecond before midnight Asia/Kolkata. */
    @Test
    void endOfDayIsLastMicrosecondInKolkata() {
        assertThat(PortfolioAnalyticsService.endOfDay(LocalDate.parse("2026-01-01")))
            .isEqualTo(Instant.parse("2026-01-01T18:29:59.999999Z"));
    }
}
