package com.rohit.nyvra.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rohit.nyvra.analytics.HealthScoreService;
import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.dashboard.dto.DashboardSummaryResponse;
import com.rohit.nyvra.expense.SpendingService;
import com.rohit.nyvra.goals.GoalService;
import com.rohit.nyvra.income.IncomeSummaryService;
import com.rohit.nyvra.networth.NetWorthService;
import com.rohit.nyvra.portfolio.PortfolioService;

/** Unit tests of {@link DashboardService}'s partial-failure behaviour, with every source mocked. */
class DashboardServiceTest {

    /** A failing section is omitted and named in {@code unavailable}; the others still load. */
    @Test
    void failingSectionIsOmittedAndNamed() {
        NetWorthService netWorth = mock(NetWorthService.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        HealthScoreService score = mock(HealthScoreService.class);
        GoalService goals = mock(GoalService.class);
        IncomeSummaryService income = mock(IncomeSummaryService.class);
        SpendingService spending = mock(SpendingService.class);
        when(netWorth.current()).thenThrow(new IllegalStateException("boom"));
        when(goals.listGoals(any(), anyInt(), anyInt(), isNull()))
            .thenReturn(new PageResponse<>(List.of(), 0, 3, 4, 2));

        DashboardSummaryResponse result =
            new DashboardService(netWorth, portfolio, score, goals, income, spending).summary();

        assertThat(result.netWorth()).isNull();
        assertThat(result.unavailable()).containsExactly("netWorth");
        assertThat(result.goals().activeCount()).isEqualTo(4);
    }
}
