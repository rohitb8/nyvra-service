package com.rohit.nyvra.dashboard.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.analytics.dto.HealthScoreResponse;
import com.rohit.nyvra.expense.dto.SpendingHabitsResponse;
import com.rohit.nyvra.goals.dto.GoalResponse;
import com.rohit.nyvra.income.dto.IncomeSummaryResponse;
import com.rohit.nyvra.networth.dto.NetWorthResponse;
import com.rohit.nyvra.portfolio.dto.PortfolioSummaryResponse;

/**
 * The {@code GET /dashboard/summary} body: one headline block per area of the home screen. A section that
 * could not be produced is omitted and named in {@code unavailable}, so one failing area never blanks the page.
 *
 * @param asOf        when the summary was assembled
 * @param netWorth    live net worth with its breakdown
 * @param portfolio   portfolio totals and allocation
 * @param healthScore the latest health score
 * @param goals       the goals block
 * @param income      trailing three-month income summary
 * @param spending    this month's spending breakdown (Asia/Kolkata)
 * @param unavailable names of the sections left out because they failed to load
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardSummaryResponse(
    Instant asOf,
    NetWorthResponse netWorth,
    PortfolioSummaryResponse portfolio,
    HealthScoreResponse healthScore,
    GoalsBlock goals,
    IncomeSummaryResponse income,
    SpendingHabitsResponse spending,
    List<String> unavailable) {

    /**
     * Goals headline.
     *
     * @param activeCount number of active goals
     * @param upcoming    up to three active goals, soonest target date first, with their progress
     */
    public record GoalsBlock(long activeCount, List<GoalResponse> upcoming) {
    }
}
