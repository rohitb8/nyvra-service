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
 * @param allocation                   target equity allocation and drift bands (section 4.1)
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
    Allocation allocation,
    int debounceSeconds
) {

    /**
     * The equity-allocation target (FINANCIAL_RULES section 4.1) and the drift bands that cost diversification
     * points (HEALTH_SCORE_SPEC section 3.5). The user's age is not stored yet, so {@code assumedAge} stands in
     * for it until it is captured.
     *
     * @param assumedAge            age used for the glide path while the user's real age is unknown
     * @param glideBase             the glide path is {@code glideBase - age}
     * @param glideMinPct           lowest base equity percentage on the glide path
     * @param glideMaxPct           highest base equity percentage on the glide path
     * @param conservativeAdjust    percentage points added to the base for a conservative user (negative)
     * @param balancedAdjust        percentage points added to the base for a balanced user
     * @param aggressiveAdjust      percentage points added to the base for an aggressive user
     * @param targetMinPct          lowest equity target after the risk adjustment
     * @param targetMaxPct          highest equity target after the risk adjustment
     * @param rebalanceBand         percentage points of drift tolerated before the first penalty
     * @param rebalanceBandHard     percentage points of drift beyond which the larger penalty applies
     * @param driftPenalty          penalty for drift outside {@code rebalanceBand}
     * @param driftHardPenalty      penalty for drift outside {@code rebalanceBandHard} (replaces the former)
     * @param maxUnclassifiedShare  drift is only scored while holdings of unknown equity share (mutual funds,
     *                              NPS) are at most this share of the portfolio
     */
    public record Allocation(
        int assumedAge,
        int glideBase,
        BigDecimal glideMinPct,
        BigDecimal glideMaxPct,
        BigDecimal conservativeAdjust,
        BigDecimal balancedAdjust,
        BigDecimal aggressiveAdjust,
        BigDecimal targetMinPct,
        BigDecimal targetMaxPct,
        BigDecimal rebalanceBand,
        BigDecimal rebalanceBandHard,
        BigDecimal driftPenalty,
        BigDecimal driftHardPenalty,
        BigDecimal maxUnclassifiedShare
    ) {
    }
}
