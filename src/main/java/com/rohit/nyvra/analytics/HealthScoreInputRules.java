package com.rohit.nyvra.analytics;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parameters for gathering the health-score drivers from the other modules: window lengths, the
 * data-sufficiency gates of {@code FINANCIAL_RULES.md} section 6, the concentration limits of section 4.2
 * and the recompute debounce. Bound from {@code nyvra.health-score-inputs}; the score formula itself stays
 * in {@link FinancialRules}.
 *
 * @param windowMonths                 full calendar months, ending last month, behind the savings and spending ratios
 * @param historyMonths                full calendar months of expenses read to judge burn rate and history depth
 * @param minSpendMonths               months of categorised spending needed for the cash-flow gate
 * @param minEmergencyHistoryMonths    months of essential spending needed for the emergency-fund gate
 * @param variableIncomeMonthsTarget   emergency-fund months target for irregular or multi-business income
 * @param minCorpus                    smallest portfolio value (INR) for the diversification gate
 * @param minHoldings                  fewest open holdings for the diversification gate
 * @param singleStockWarnShare         share of the equity portfolio above which one stock costs a penalty
 * @param singleStockCriticalShare     share above which one stock costs the larger penalty
 * @param singleStockWarnPenalty       penalty per stock above the warn share
 * @param singleStockCriticalPenalty   penalty per stock above the critical share (replaces the warn penalty)
 * @param singleStockMaxPenalty        ceiling on the total single-stock penalty
 * @param debounceSeconds              quiet time after a change before a user's score is recomputed
 */
@ConfigurationProperties(prefix = "nyvra.health-score-inputs")
public record HealthScoreInputRules(
    int windowMonths,
    int historyMonths,
    int minSpendMonths,
    int minEmergencyHistoryMonths,
    BigDecimal variableIncomeMonthsTarget,
    BigDecimal minCorpus,
    int minHoldings,
    BigDecimal singleStockWarnShare,
    BigDecimal singleStockCriticalShare,
    BigDecimal singleStockWarnPenalty,
    BigDecimal singleStockCriticalPenalty,
    BigDecimal singleStockMaxPenalty,
    int debounceSeconds
) {
}
