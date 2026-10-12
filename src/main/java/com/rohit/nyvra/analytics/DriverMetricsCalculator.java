package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Set;

/**
 * Applies the data-sufficiency gates (FINANCIAL_RULES section 6) and the driver formulas to
 * {@link DriverFacts}. A driver whose gate fails is {@code null}, so its sub-score is "not evaluated" and
 * the matching {@link MissingData} item is reported. Pure arithmetic; every number comes from
 * {@link FinancialRules} or {@link HealthScoreInputRules}.
 *
 * <p>Not yet derivable from stored data, and therefore reported as inactive: lifestyle creep, high-interest
 * debt (loans carry no APR), revolving card balances, fixed-obligations ceiling, the user's own emergency
 * target and equity-allocation drift (no age or risk profile). Single-AMC, fund-overlap and employer-stock
 * penalties need data the portfolio does not hold yet.
 */
public final class DriverMetricsCalculator {

    /** Scale of ratios handed to the calculator. */
    private static final int SCALE = 6;
    /** Smallest savings rate that is scored (spec section 7: clamped to [-1, 0] when negative). */
    private static final BigDecimal MIN_SAVINGS_RATE = BigDecimal.ONE.negate();

    /** Utility class; not instantiable. */
    private DriverMetricsCalculator() {
    }

    /**
     * Turns facts into drivers.
     *
     * @param facts   the user's aggregated facts
     * @param inputs  the gate and window parameters
     * @param rules   the financial rule parameters
     * @return the drivers, with {@code null} for every gated-out one
     */
    public static DriverMetrics calculate(DriverFacts facts, HealthScoreInputRules inputs, FinancialRules rules) {
        Set<MissingData> missing = EnumSet.noneOf(MissingData.class);

        BigDecimal savingsRate = null;
        BigDecimal essentialRatio = null;
        BigDecimal discretionaryRatio = null;
        boolean incomeOk = facts.incomeEntryCount() > 0;
        boolean spendOk = facts.monthsWithSpend() >= inputs.minSpendMonths();
        if (!incomeOk) {
            missing.add(MissingData.INCOME_ENTRY);
        }
        if (!spendOk) {
            missing.add(MissingData.CATEGORISED_TRANSACTIONS_2_MONTHS);
        }
        BigDecimal income = facts.windowIncomeNet();
        if (incomeOk && spendOk && income.signum() > 0) {
            savingsRate = income.subtract(facts.windowSpend()).divide(income, SCALE, RoundingMode.HALF_UP)
                .max(MIN_SAVINGS_RATE);
            essentialRatio = facts.windowEssential().divide(income, SCALE, RoundingMode.HALF_UP);
            discretionaryRatio = facts.windowDiscretionary().divide(income, SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal emergencyMonths = null;
        boolean burnOk = facts.burnMonths() >= inputs.minEmergencyHistoryMonths();
        if (!facts.liquidKnown()) {
            missing.add(MissingData.LIQUID_ASSETS);
        }
        if (!burnOk) {
            missing.add(MissingData.ESSENTIAL_SPEND_3_MONTHS);
        }
        if (facts.liquidKnown() && burnOk && facts.burnTotal().signum() > 0) {
            BigDecimal monthlyBurn = facts.burnTotal()
                .divide(BigDecimal.valueOf(facts.burnMonths()), SCALE, RoundingMode.HALF_UP);
            emergencyMonths = facts.liquidAssets().divide(monthlyBurn, SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal emergencyTarget = facts.variableIncome()
            ? inputs.variableIncomeMonthsTarget().max(rules.emergency().monthsTarget()) : null;

        DriverMetrics.DebtMetrics debt = debt(facts, income, incomeOk && spendOk);
        if (debt == null) {
            missing.add(MissingData.LIABILITY_ACCOUNT_OR_NO_DEBT_CONFIRMATION);
        }

        BigDecimal diversification = diversification(facts, inputs);
        if (diversification == null) {
            missing.add(MissingData.HOLDINGS);
        }

        return new DriverMetrics(savingsRate, essentialRatio, discretionaryRatio, false, emergencyMonths,
            emergencyTarget, debt, diversification, goalProgress(facts), false, false,
            facts.maxAccountStaleDays(), facts.historyDays(), missing);
    }

    /**
     * Builds the debt drivers. With no liability account but at least one linked account the user has no debt;
     * with no account at all the gate fails.
     *
     * @param facts    the facts
     * @param income   net income over the ratio window
     * @param cashFlow whether the cash-flow gate passed, which DTI needs
     * @return the debt metrics, or {@code null} when the debt gate fails
     */
    private static DriverMetrics.DebtMetrics debt(DriverFacts facts, BigDecimal income, boolean cashFlow) {
        if (facts.liabilityAccountCount() == 0) {
            return facts.hasAccount() ? new DriverMetrics.DebtMetrics(true, null, null, false, false) : null;
        }
        BigDecimal dti = null;
        if (cashFlow && income.signum() > 0) {
            dti = facts.windowDebtRepayment().divide(income, SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal utilisation = null;
        if (facts.cardLimit().signum() > 0) {
            utilisation = facts.cardOutstanding().divide(facts.cardLimit(), SCALE, RoundingMode.HALF_UP);
        }
        return new DriverMetrics.DebtMetrics(false, dti, utilisation, false, false);
    }

    /**
     * Totals the diversification penalty (spec section 3.5) that can be derived from holdings: the
     * single-direct-stock concentration check.
     *
     * @param facts  the facts
     * @param inputs the gate and concentration parameters
     * @return the total penalty points, or {@code null} when the corpus or holdings gate fails
     */
    private static BigDecimal diversification(DriverFacts facts, HealthScoreInputRules inputs) {
        if (facts.portfolioValue().compareTo(inputs.minCorpus()) < 0 || facts.holdingCount() < inputs.minHoldings()) {
            return null;
        }
        BigDecimal penalty = BigDecimal.ZERO;
        if (facts.equityValue().signum() > 0) {
            for (BigDecimal stock : facts.stockValues()) {
                BigDecimal share = stock.divide(facts.equityValue(), SCALE, RoundingMode.HALF_UP);
                if (share.compareTo(inputs.singleStockCriticalShare()) > 0) {
                    penalty = penalty.add(inputs.singleStockCriticalPenalty());
                } else if (share.compareTo(inputs.singleStockWarnShare()) > 0) {
                    penalty = penalty.add(inputs.singleStockWarnPenalty());
                }
            }
        }
        return penalty.min(inputs.singleStockMaxPenalty());
    }

    /**
     * Weighted goal completion, 0-100 (spec section 3.6).
     *
     * @param facts the facts
     * @return the score, or {@code null} when the user has no active goals
     */
    private static BigDecimal goalProgress(DriverFacts facts) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        for (DriverFacts.GoalRatio goal : facts.goalRatios()) {
            BigDecimal weight = BigDecimal.valueOf(goal.weight());
            weighted = weighted.add(goal.ratio().multiply(weight));
            weights = weights.add(weight);
        }
        if (weights.signum() == 0) {
            return null;
        }
        return weighted.multiply(BigDecimal.valueOf(100)).divide(weights, 2, RoundingMode.HALF_UP);
    }
}
