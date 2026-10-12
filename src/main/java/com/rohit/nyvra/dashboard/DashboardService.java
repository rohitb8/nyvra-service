package com.rohit.nyvra.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rohit.nyvra.analytics.HealthScoreService;
import com.rohit.nyvra.analytics.dto.HealthScoreResponse;
import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.dashboard.dto.DashboardSummaryResponse;
import com.rohit.nyvra.expense.SpendingService;
import com.rohit.nyvra.expense.dto.SpendingHabitsResponse;
import com.rohit.nyvra.goals.GoalService;
import com.rohit.nyvra.goals.GoalStatus;
import com.rohit.nyvra.goals.dto.GoalResponse;
import com.rohit.nyvra.income.IncomeSummaryService;
import com.rohit.nyvra.income.dto.IncomeSummaryResponse;
import com.rohit.nyvra.networth.NetWorthService;
import com.rohit.nyvra.networth.dto.NetWorthResponse;
import com.rohit.nyvra.portfolio.PortfolioService;
import com.rohit.nyvra.portfolio.dto.PortfolioSummaryResponse;

/**
 * Assembles the dashboard summary from the other modules' services. Each section is loaded independently
 * (each service call runs in its own read-only transaction), and a failure in one is logged and reported in
 * {@code unavailable} instead of failing the whole response.
 */
@Service
public class DashboardService {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    /** Trailing window, in months, for the income block. */
    private static final int INCOME_WINDOW_MONTHS = 3;
    /** Maximum goals listed in the goals block. */
    private static final int UPCOMING_GOALS = 3;

    /** Net worth source. */
    private final NetWorthService netWorth;
    /** Portfolio source. */
    private final PortfolioService portfolio;
    /** Health score source. */
    private final HealthScoreService healthScore;
    /** Goals source. */
    private final GoalService goals;
    /** Income source. */
    private final IncomeSummaryService income;
    /** Spending source. */
    private final SpendingService spending;

    /**
     * Creates the service.
     *
     * @param netWorth    net worth service
     * @param portfolio   portfolio service
     * @param healthScore health score service
     * @param goals       goal service
     * @param income      income summary service
     * @param spending    spending service
     */
    public DashboardService(NetWorthService netWorth, PortfolioService portfolio, HealthScoreService healthScore,
                            GoalService goals, IncomeSummaryService income, SpendingService spending) {
        this.netWorth = netWorth;
        this.portfolio = portfolio;
        this.healthScore = healthScore;
        this.goals = goals;
        this.income = income;
        this.spending = spending;
    }

    /**
     * Builds the caller's dashboard summary.
     *
     * @return the summary; sections that failed are omitted and listed in {@code unavailable}
     */
    public DashboardSummaryResponse summary() {
        List<String> unavailable = new ArrayList<>();
        NetWorthResponse nw = load("netWorth", netWorth::current, unavailable);
        PortfolioSummaryResponse pf = load("portfolio", portfolio::summary, unavailable);
        HealthScoreResponse hs = load("healthScore", healthScore::latest, unavailable);
        DashboardSummaryResponse.GoalsBlock gb = load("goals", this::goalsBlock, unavailable);
        IncomeSummaryResponse in = load("income", () -> income.summarize(INCOME_WINDOW_MONTHS), unavailable);
        SpendingHabitsResponse sp = load("spending", () -> spending.habits(null), unavailable);
        return new DashboardSummaryResponse(Instant.now(), nw, pf, hs, gb, in, sp,
            unavailable.isEmpty() ? null : List.copyOf(unavailable));
    }

    /**
     * Builds the goals block from the active goals.
     *
     * @return the active count and the soonest few goals
     */
    private DashboardSummaryResponse.GoalsBlock goalsBlock() {
        PageResponse<GoalResponse> page = goals.listGoals(Set.of(GoalStatus.ACTIVE), 0, UPCOMING_GOALS, null);
        return new DashboardSummaryResponse.GoalsBlock(page.totalElements(), page.content());
    }

    /**
     * Runs one section loader, recording its name when it fails.
     *
     * @param name        the section name
     * @param loader      the loader
     * @param unavailable collects the names of failed sections
     * @param <T>         the section type
     * @return the section, or {@code null} when it failed
     */
    private <T> T load(String name, Supplier<T> loader, List<String> unavailable) {
        try {
            return loader.get();
        } catch (RuntimeException e) {
            log.warn("Dashboard section {} unavailable", name, e);
            unavailable.add(name);
            return null;
        }
    }
}
