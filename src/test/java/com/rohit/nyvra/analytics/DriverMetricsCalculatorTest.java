package com.rohit.nyvra.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.rohit.nyvra.user.RiskBand;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link DriverMetricsCalculator}: each data-sufficiency gate of FINANCIAL_RULES section 6, the
 * driver formulas, and the "missing data" checklist. They use the shipped parameters and need no database.
 */
class DriverMetricsCalculatorTest {

    /** The shipped financial rules. */
    private static final FinancialRules RULES = FinancialRulesFixture.load();
    /** The shipped window and gate parameters. */
    private static final HealthScoreInputRules INPUTS = FinancialRulesFixture.loadInputs();

    /**
     * Shorthand for a decimal.
     *
     * @param value the decimal text
     * @return the number
     */
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /**
     * A healthy user: three months of cash flow, a known emergency fund, one savings account and no portfolio.
     *
     * @return the facts
     */
    private static DriverFacts healthy() {
        return new DriverFacts(d("300000"), 3, 3, d("180000"), d("120000"), d("60000"), BigDecimal.ZERO, 3,
            d("120000"), true, d("240000"), false, true, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
            BigDecimal.ZERO, List.of(), List.of(), 2, 90);
    }

    /**
     * Copies facts with different portfolio figures.
     *
     * @param base     the facts to copy
     * @param value    portfolio value
     * @param holdings open holdings
     * @param equity   direct-equity value
     * @param stocks   value of each direct stock
     * @return the changed facts
     */
    private static DriverFacts withPortfolio(DriverFacts base, String value, int holdings, String equity,
                                             List<BigDecimal> stocks) {
        return new DriverFacts(base.windowIncomeNet(), base.incomeEntryCount(), base.monthsWithSpend(),
            base.windowSpend(), base.windowEssential(), base.windowDiscretionary(), base.windowDebtRepayment(),
            base.burnMonths(), base.burnTotal(), base.liquidKnown(), base.liquidAssets(), base.variableIncome(),
            base.hasAccount(), base.liabilityAccountCount(), base.cardOutstanding(), base.cardLimit(), d(value),
            holdings, d(equity), stocks, base.goalRatios(), base.maxAccountStaleDays(), base.historyDays());
    }

    /**
     * Copies facts with different portfolio composition and preferences.
     *
     * @param base         the facts to copy
     * @param portfolio    portfolio value
     * @param equity       direct-equity value
     * @param unclassified value of mutual funds and NPS
     * @param band         the risk band
     * @param ownTarget    the user's own emergency target, or {@code null}
     * @return the changed facts
     */
    private static DriverFacts withPreferences(DriverFacts base, String portfolio, String equity,
                                               String unclassified, RiskBand band, String ownTarget) {
        return new DriverFacts(base.windowIncomeNet(), base.incomeEntryCount(), base.monthsWithSpend(),
            base.windowSpend(), base.windowEssential(), base.windowDiscretionary(), base.windowDebtRepayment(),
            base.burnMonths(), base.burnTotal(), base.liquidKnown(), base.liquidAssets(), base.variableIncome(),
            base.hasAccount(), base.liabilityAccountCount(), base.cardOutstanding(), base.cardLimit(),
            d(portfolio), 5, d(equity), List.of(), base.goalRatios(), base.maxAccountStaleDays(),
            base.historyDays(), d(unclassified), band, ownTarget == null ? null : d(ownTarget));
    }

    /** A user who passes the cash-flow and emergency gates gets ratios, months of cover and no debt. */
    @Test
    void healthyUserGetsRatiosAndEmergencyMonths() {
        DriverMetrics m = DriverMetricsCalculator.calculate(healthy(), INPUTS, RULES);

        assertThat(m.savingsRate()).isEqualByComparingTo("0.4");
        assertThat(m.essentialRatio()).isEqualByComparingTo("0.4");
        assertThat(m.discretionaryRatio()).isEqualByComparingTo("0.2");
        assertThat(m.emergencyFundMonths()).isEqualByComparingTo("6");
        assertThat(m.debt().noDebt()).isTrue();
        assertThat(m.maxAccountStaleDays()).isEqualTo(2);
        assertThat(m.historyDays()).isEqualTo(90);
        assertThat(m.missingData()).containsExactlyInAnyOrder(MissingData.HOLDINGS);
    }

    /** With nothing at all, every driver is gated out and the whole checklist is reported (goals excluded). */
    @Test
    void emptyUserFailsEveryGate() {
        DriverFacts none = new DriverFacts(BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, 0, BigDecimal.ZERO, false, BigDecimal.ZERO, false, false, 0, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), List.of(), null, 0);

        DriverMetrics m = DriverMetricsCalculator.calculate(none, INPUTS, RULES);

        assertThat(m.savingsRate()).isNull();
        assertThat(m.emergencyFundMonths()).isNull();
        assertThat(m.debt()).isNull();
        assertThat(m.diversificationPenalty()).isNull();
        assertThat(m.goalProgressScore()).isNull();
        assertThat(m.missingData()).containsExactlyInAnyOrder(MissingData.INCOME_ENTRY,
            MissingData.CATEGORISED_TRANSACTIONS_2_MONTHS, MissingData.LIQUID_ASSETS,
            MissingData.ESSENTIAL_SPEND_3_MONTHS, MissingData.LIABILITY_ACCOUNT_OR_NO_DEBT_CONFIRMATION,
            MissingData.HOLDINGS);
    }

    /** Spending that exceeds income gives a negative savings rate, floored at -1 for scoring. */
    @Test
    void savingsRateIsFlooredAtMinusOne() {
        DriverFacts overspent = new DriverFacts(d("100000"), 3, 3, d("500000"), d("300000"), d("200000"),
            BigDecimal.ZERO, 3, d("120000"), true, d("240000"), false, true, 0, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), List.of(), 2, 90);

        assertThat(DriverMetricsCalculator.calculate(overspent, INPUTS, RULES).savingsRate())
            .isEqualByComparingTo("-1");
    }

    /** One month of spending is not enough for the cash-flow gate. */
    @Test
    void oneMonthOfSpendFailsTheCashFlowGate() {
        DriverFacts f = healthy();
        DriverFacts oneMonth = new DriverFacts(f.windowIncomeNet(), f.incomeEntryCount(), 1, f.windowSpend(),
            f.windowEssential(), f.windowDiscretionary(), f.windowDebtRepayment(), f.burnMonths(), f.burnTotal(),
            f.liquidKnown(), f.liquidAssets(), f.variableIncome(), f.hasAccount(), f.liabilityAccountCount(),
            f.cardOutstanding(), f.cardLimit(), f.portfolioValue(), f.holdingCount(), f.equityValue(),
            f.stockValues(), f.goalRatios(), f.maxAccountStaleDays(), f.historyDays());

        DriverMetrics m = DriverMetricsCalculator.calculate(oneMonth, INPUTS, RULES);

        assertThat(m.savingsRate()).isNull();
        assertThat(m.missingData()).contains(MissingData.CATEGORISED_TRANSACTIONS_2_MONTHS);
    }

    /** Irregular or multi-business income lifts the emergency target to the variable-income months. */
    @Test
    void variableIncomeRaisesTheEmergencyTarget() {
        DriverFacts f = healthy();
        DriverFacts variable = new DriverFacts(f.windowIncomeNet(), f.incomeEntryCount(), f.monthsWithSpend(),
            f.windowSpend(), f.windowEssential(), f.windowDiscretionary(), f.windowDebtRepayment(), f.burnMonths(),
            f.burnTotal(), f.liquidKnown(), f.liquidAssets(), true, f.hasAccount(), f.liabilityAccountCount(),
            f.cardOutstanding(), f.cardLimit(), f.portfolioValue(), f.holdingCount(), f.equityValue(),
            f.stockValues(), f.goalRatios(), f.maxAccountStaleDays(), f.historyDays());

        assertThat(DriverMetricsCalculator.calculate(variable, INPUTS, RULES).emergencyMonthsTarget())
            .isEqualByComparingTo("9");
        assertThat(DriverMetricsCalculator.calculate(f, INPUTS, RULES).emergencyMonthsTarget()).isNull();
    }

    /** Liability accounts produce DTI from debt repayments and card utilisation from limits. */
    @Test
    void liabilitiesGiveDtiAndUtilisation() {
        DriverFacts f = healthy();
        DriverFacts indebted = new DriverFacts(f.windowIncomeNet(), f.incomeEntryCount(), f.monthsWithSpend(),
            f.windowSpend(), f.windowEssential(), f.windowDiscretionary(), d("90000"), f.burnMonths(), f.burnTotal(),
            f.liquidKnown(), f.liquidAssets(), f.variableIncome(), true, 2, d("60000"), d("200000"),
            f.portfolioValue(), f.holdingCount(), f.equityValue(), f.stockValues(), f.goalRatios(),
            f.maxAccountStaleDays(), f.historyDays());

        DriverMetrics.DebtMetrics debt = DriverMetricsCalculator.calculate(indebted, INPUTS, RULES).debt();

        assertThat(debt.noDebt()).isFalse();
        assertThat(debt.dti()).isEqualByComparingTo("0.3");
        assertThat(debt.ccUtilisation()).isEqualByComparingTo("0.3");
    }

    /** A portfolio below the corpus minimum or with too few holdings is not evaluated for diversification. */
    @Test
    void smallPortfolioFailsTheDiversificationGate() {
        DriverFacts tiny = withPortfolio(healthy(), "40000", 5, "40000", List.of());
        DriverFacts few = withPortfolio(healthy(), "500000", 2, "500000", List.of());

        assertThat(DriverMetricsCalculator.calculate(tiny, INPUTS, RULES).diversificationPenalty()).isNull();
        assertThat(DriverMetricsCalculator.calculate(few, INPUTS, RULES).diversificationPenalty()).isNull();
    }

    /** A stock over 20% of equity costs the larger penalty, one over 10% the smaller, and the total is capped at 25. */
    @Test
    void singleStockConcentrationIsPenalisedAndCapped() {
        DriverFacts balanced = withPortfolio(healthy(), "770000", 5, "500000",
            List.of(d("50000"), d("50000"), d("50000"), d("50000"), d("300000")));
        DriverFacts mixed = withPortfolio(healthy(), "770000", 5, "500000",
            List.of(d("60000"), d("40000"), d("40000")));
        DriverFacts overweight = withPortfolio(healthy(), "615000", 5, "400000",
            List.of(d("100000"), d("100000"), d("100000"), d("100000")));

        assertThat(DriverMetricsCalculator.calculate(balanced, INPUTS, RULES).diversificationPenalty())
            .isEqualByComparingTo("25");
        assertThat(DriverMetricsCalculator.calculate(mixed, INPUTS, RULES).diversificationPenalty())
            .isEqualByComparingTo("10");
        assertThat(DriverMetricsCalculator.calculate(overweight, INPUTS, RULES).diversificationPenalty())
            .isEqualByComparingTo("25");
        assertThat(DriverMetricsCalculator.calculate(
            withPortfolio(healthy(), "1540000", 5, "1000000", List.of(d("100000"), d("100000"), d("100000"))),
            INPUTS, RULES).diversificationPenalty()).isEqualByComparingTo("0");
    }

    /** Goal progress is the weight-averaged ratio times 100, and absent with no goals. */
    @Test
    void goalProgressIsWeightedAverage() {
        DriverFacts f = healthy();
        DriverFacts withGoals = new DriverFacts(f.windowIncomeNet(), f.incomeEntryCount(), f.monthsWithSpend(),
            f.windowSpend(), f.windowEssential(), f.windowDiscretionary(), f.windowDebtRepayment(), f.burnMonths(),
            f.burnTotal(), f.liquidKnown(), f.liquidAssets(), f.variableIncome(), f.hasAccount(),
            f.liabilityAccountCount(), f.cardOutstanding(), f.cardLimit(), f.portfolioValue(), f.holdingCount(),
            f.equityValue(), f.stockValues(),
            List.of(new DriverFacts.GoalRatio(d("1"), 1), new DriverFacts.GoalRatio(d("0.5"), 3)),
            f.maxAccountStaleDays(), f.historyDays());

        assertThat(DriverMetricsCalculator.calculate(withGoals, INPUTS, RULES).goalProgressScore())
            .isEqualByComparingTo("62.5");
        assertThat(DriverMetricsCalculator.calculate(f, INPUTS, RULES).goalProgressScore()).isNull();
    }

    /** The risk band shifts the equity target: the same 60% equity is on target for conservative, off for aggressive. */
    @Test
    void allocationDriftIsMeasuredAgainstTheRiskBandTarget() {
        DriverFacts base = healthy();
        // Assumed age 35 gives a base of 65; conservative 50, balanced 65, aggressive 75.
        assertThat(penalty(withPreferences(base, "1000000", "600000", "0", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("0");
        assertThat(penalty(withPreferences(base, "1000000", "500000", "0", RiskBand.CONSERVATIVE, null)))
            .isEqualByComparingTo("0");
        assertThat(penalty(withPreferences(base, "1000000", "600000", "0", RiskBand.CONSERVATIVE, null)))
            .isEqualByComparingTo("10");
        assertThat(penalty(withPreferences(base, "1000000", "600000", "0", RiskBand.AGGRESSIVE, null)))
            .isEqualByComparingTo("25");
    }

    /** Drift beyond the hard band costs the larger penalty, and the band edge itself is not a breach. */
    @Test
    void hardDriftReplacesTheSoftPenaltyAndTheBandEdgeIsTolerated() {
        DriverFacts base = healthy();

        assertThat(penalty(withPreferences(base, "1000000", "400000", "0", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("25");
        assertThat(penalty(withPreferences(base, "1000000", "700000", "0", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("0");
        assertThat(penalty(withPreferences(base, "1000000", "550000", "0", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("10");
    }

    /** Drift is not judged when too much of the portfolio is funds or NPS whose equity share is unknown. */
    @Test
    void driftIsSkippedWhenTheEquityShareIsUnknown() {
        DriverFacts base = healthy();

        assertThat(penalty(withPreferences(base, "1000000", "100000", "800000", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("0");
        assertThat(penalty(withPreferences(base, "1000000", "560000", "90000", RiskBand.BALANCED, null)))
            .isEqualByComparingTo("10");
    }

    /** The user's own emergency target wins over the default and the variable-income target. */
    @Test
    void ownEmergencyTargetOverridesTheDefaults() {
        DriverFacts base = healthy();

        assertThat(DriverMetricsCalculator.calculate(
            withPreferences(base, "0", "0", "0", RiskBand.BALANCED, "4"), INPUTS, RULES).emergencyMonthsTarget())
            .isEqualByComparingTo("4");
        DriverFacts variable = new DriverFacts(base.windowIncomeNet(), base.incomeEntryCount(),
            base.monthsWithSpend(), base.windowSpend(), base.windowEssential(), base.windowDiscretionary(),
            base.windowDebtRepayment(), base.burnMonths(), base.burnTotal(), base.liquidKnown(),
            base.liquidAssets(), true, base.hasAccount(), base.liabilityAccountCount(), base.cardOutstanding(),
            base.cardLimit(), base.portfolioValue(), base.holdingCount(), base.equityValue(), base.stockValues(),
            base.goalRatios(), base.maxAccountStaleDays(), base.historyDays(), BigDecimal.ZERO,
            RiskBand.BALANCED, d("12"));
        assertThat(DriverMetricsCalculator.calculate(variable, INPUTS, RULES).emergencyMonthsTarget())
            .isEqualByComparingTo("12");
    }

    /** A target at or below the minimum months would flatten the ramp, so it is ignored. */
    @Test
    void emergencyTargetAtOrBelowTheMinimumIsIgnored() {
        DriverFacts base = healthy();

        assertThat(DriverMetricsCalculator.calculate(
            withPreferences(base, "0", "0", "0", RiskBand.BALANCED, "3"), INPUTS, RULES).emergencyMonthsTarget())
            .isNull();
    }

    /**
     * The diversification penalty of the given facts.
     *
     * @param facts the facts, which must pass the corpus and holdings gates
     * @return the total penalty points
     */
    private static BigDecimal penalty(DriverFacts facts) {
        return DriverMetricsCalculator.calculate(facts, INPUTS, RULES).diversificationPenalty();
    }
}
