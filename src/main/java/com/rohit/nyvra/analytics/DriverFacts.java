package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.util.List;

import com.rohit.nyvra.user.RiskBand;

/**
 * The raw, already-aggregated facts about one user that {@link DriverMetricsCalculator} turns into
 * {@link DriverMetrics}. It holds no entities, so the gate and ratio rules can be tested without a database.
 * Money is INR throughout.
 *
 * @param windowIncomeNet         net income received over the ratio window
 * @param incomeEntryCount        income entries seen in the window (zero fails the income gate)
 * @param monthsWithSpend         months of the window that have categorised spending
 * @param windowSpend             spending over the window, excluding savings transfers and excluded rows
 * @param windowEssential         essential spending over the window
 * @param windowDiscretionary     discretionary spending over the window
 * @param windowDebtRepayment     debt-repayment spending over the window
 * @param burnMonths              months of the history window that have essential or debt-repayment spending
 * @param burnTotal               essential plus debt-repayment spending over the history window
 * @param liquidKnown             whether the user has at least one savings or current account
 * @param liquidAssets            sum of savings and current balances
 * @param variableIncome          income is irregular or comes from more than one business source
 * @param hasAccount              whether any open account is linked
 * @param liabilityAccountCount   open loan and card accounts
 * @param cardOutstanding         outstanding card balances, by magnitude
 * @param cardLimit               credit limits of the cards
 * @param portfolioValue          market value of open INR holdings (cost when unpriced)
 * @param holdingCount            open holdings
 * @param equityValue             value of direct equity holdings, the base of the concentration check
 * @param stockValues             value of each direct equity holding
 * @param goalRatios              progress ratio and weight of each active goal
 * @param maxAccountStaleDays     days since the least recently refreshed linked account; {@code null} if none
 * @param historyDays             days of spending history
 * @param unclassifiedValue       value of holdings whose equity share is unknown (mutual funds, NPS)
 * @param riskBand                the user's risk appetite from their preferences
 * @param userEmergencyTarget     the user's own emergency-fund months target; {@code null} when unset
 * @param ageYears                the user's age in whole years; {@code null} when no date of birth is stored, in
 *                                which case the configured assumed age is used
 */
public record DriverFacts(
    BigDecimal windowIncomeNet,
    int incomeEntryCount,
    int monthsWithSpend,
    BigDecimal windowSpend,
    BigDecimal windowEssential,
    BigDecimal windowDiscretionary,
    BigDecimal windowDebtRepayment,
    int burnMonths,
    BigDecimal burnTotal,
    boolean liquidKnown,
    BigDecimal liquidAssets,
    boolean variableIncome,
    boolean hasAccount,
    int liabilityAccountCount,
    BigDecimal cardOutstanding,
    BigDecimal cardLimit,
    BigDecimal portfolioValue,
    int holdingCount,
    BigDecimal equityValue,
    List<BigDecimal> stockValues,
    List<GoalRatio> goalRatios,
    Integer maxAccountStaleDays,
    int historyDays,
    BigDecimal unclassifiedValue,
    RiskBand riskBand,
    BigDecimal userEmergencyTarget,
    Integer ageYears
) {

    /**
     * Facts with no known age, so the glide path uses the configured assumed age.
     *
     * @param windowIncomeNet         net income over the ratio window
     * @param incomeEntryCount        income entries seen in the window
     * @param monthsWithSpend         months with categorised spending
     * @param windowSpend             spending over the window
     * @param windowEssential         essential spending over the window
     * @param windowDiscretionary     discretionary spending over the window
     * @param windowDebtRepayment     debt-repayment spending over the window
     * @param burnMonths              months with essential or debt-repayment spending
     * @param burnTotal               essential plus debt-repayment spending over the history window
     * @param liquidKnown             whether a savings or current account exists
     * @param liquidAssets            sum of savings and current balances
     * @param variableIncome          income is irregular or multi-business
     * @param hasAccount              whether any open account is linked
     * @param liabilityAccountCount   open loan and card accounts
     * @param cardOutstanding         outstanding card balances
     * @param cardLimit               credit limits of the cards
     * @param portfolioValue          market value of open INR holdings
     * @param holdingCount            open holdings
     * @param equityValue             value of direct equity holdings
     * @param stockValues             value of each direct equity holding
     * @param goalRatios              progress ratio and weight of each active goal
     * @param maxAccountStaleDays     days since the least recently refreshed linked account
     * @param historyDays             days of spending history
     * @param unclassifiedValue       value of holdings whose equity share is unknown
     * @param riskBand                the user's risk appetite
     * @param userEmergencyTarget     the user's own emergency-fund months target; {@code null} when unset
     */
    public DriverFacts(BigDecimal windowIncomeNet, int incomeEntryCount, int monthsWithSpend,
                       BigDecimal windowSpend, BigDecimal windowEssential, BigDecimal windowDiscretionary,
                       BigDecimal windowDebtRepayment, int burnMonths, BigDecimal burnTotal, boolean liquidKnown,
                       BigDecimal liquidAssets, boolean variableIncome, boolean hasAccount,
                       int liabilityAccountCount, BigDecimal cardOutstanding, BigDecimal cardLimit,
                       BigDecimal portfolioValue, int holdingCount, BigDecimal equityValue,
                       List<BigDecimal> stockValues, List<GoalRatio> goalRatios, Integer maxAccountStaleDays,
                       int historyDays, BigDecimal unclassifiedValue, RiskBand riskBand,
                       BigDecimal userEmergencyTarget) {
        this(windowIncomeNet, incomeEntryCount, monthsWithSpend, windowSpend, windowEssential,
            windowDiscretionary, windowDebtRepayment, burnMonths, burnTotal, liquidKnown, liquidAssets,
            variableIncome, hasAccount, liabilityAccountCount, cardOutstanding, cardLimit, portfolioValue,
            holdingCount, equityValue, stockValues, goalRatios, maxAccountStaleDays, historyDays,
            unclassifiedValue, riskBand, userEmergencyTarget, null);
    }

    /**
     * Facts without preference or classification data: balanced risk, no own emergency target, nothing
     * unclassified. Keeps callers that only exercise the cash-flow, debt and concentration drivers short.
     *
     * @param windowIncomeNet         net income over the ratio window
     * @param incomeEntryCount        income entries seen in the window
     * @param monthsWithSpend         months with categorised spending
     * @param windowSpend             spending over the window
     * @param windowEssential         essential spending over the window
     * @param windowDiscretionary     discretionary spending over the window
     * @param windowDebtRepayment     debt-repayment spending over the window
     * @param burnMonths              months with essential or debt-repayment spending
     * @param burnTotal               essential plus debt-repayment spending over the history window
     * @param liquidKnown             whether a savings or current account exists
     * @param liquidAssets            sum of savings and current balances
     * @param variableIncome          income is irregular or multi-business
     * @param hasAccount              whether any open account is linked
     * @param liabilityAccountCount   open loan and card accounts
     * @param cardOutstanding         outstanding card balances
     * @param cardLimit               credit limits of the cards
     * @param portfolioValue          market value of open INR holdings
     * @param holdingCount            open holdings
     * @param equityValue             value of direct equity holdings
     * @param stockValues             value of each direct equity holding
     * @param goalRatios              progress ratio and weight of each active goal
     * @param maxAccountStaleDays     days since the least recently refreshed linked account
     * @param historyDays             days of spending history
     */
    public DriverFacts(BigDecimal windowIncomeNet, int incomeEntryCount, int monthsWithSpend,
                       BigDecimal windowSpend, BigDecimal windowEssential, BigDecimal windowDiscretionary,
                       BigDecimal windowDebtRepayment, int burnMonths, BigDecimal burnTotal, boolean liquidKnown,
                       BigDecimal liquidAssets, boolean variableIncome, boolean hasAccount,
                       int liabilityAccountCount, BigDecimal cardOutstanding, BigDecimal cardLimit,
                       BigDecimal portfolioValue, int holdingCount, BigDecimal equityValue,
                       List<BigDecimal> stockValues, List<GoalRatio> goalRatios, Integer maxAccountStaleDays,
                       int historyDays) {
        this(windowIncomeNet, incomeEntryCount, monthsWithSpend, windowSpend, windowEssential,
            windowDiscretionary, windowDebtRepayment, burnMonths, burnTotal, liquidKnown, liquidAssets,
            variableIncome, hasAccount, liabilityAccountCount, cardOutstanding, cardLimit, portfolioValue,
            holdingCount, equityValue, stockValues, goalRatios, maxAccountStaleDays, historyDays,
            BigDecimal.ZERO, RiskBand.BALANCED, null);
    }

    /**
     * One goal's contribution to the goal-progress sub-score.
     *
     * @param ratio  progress between 0 and 1
     * @param weight the goal's relative weight
     */
    public record GoalRatio(BigDecimal ratio, int weight) {
    }
}
