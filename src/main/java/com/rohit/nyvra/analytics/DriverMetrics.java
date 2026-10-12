package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The metrics that drive the health score, gathered from the other modules by whatever triggers a
 * recompute. A {@code null} driver means its data-sufficiency gate (FINANCIAL_RULES §6) failed, so the
 * matching sub-score is not evaluated. Ratios are fractions (0.30 is 30%).
 *
 * @param savingsRate             trailing-window savings rate; may be negative
 * @param essentialRatio          essential spend over net income
 * @param discretionaryRatio      discretionary spend over net income
 * @param lifestyleCreepActive    whether lifestyle creep (FINANCIAL_RULES §5) is active
 * @param emergencyFundMonths     liquid assets over monthly essential burn
 * @param emergencyMonthsTarget   the user's effective months target; {@code null} uses the rules default
 * @param debt                    debt metrics; {@code null} when the debt gate failed
 * @param diversificationPenalty  total diversification penalty points (spec §3.5), computed where the
 *                                portfolio is known; {@code null} when the gate failed
 * @param goalProgressScore       weighted goal completion, 0-100 (spec §3.6); {@code null} with no goals
 * @param highInterestDebtCritical a critical high-interest-debt insight is active (hard cap)
 * @param fixedObligationsOverCeiling fixed obligations exceed their hard ceiling (hard cap)
 * @param maxAccountStaleDays     days since the least recently refreshed linked account; {@code null}
 *                                when no account is linked, which counts as fully fresh
 * @param historyDays             days of data history the user has
 * @param missingData             failed gates to report if the score turns out insufficient
 */
public record DriverMetrics(
    BigDecimal savingsRate,
    BigDecimal essentialRatio,
    BigDecimal discretionaryRatio,
    boolean lifestyleCreepActive,
    BigDecimal emergencyFundMonths,
    BigDecimal emergencyMonthsTarget,
    DebtMetrics debt,
    BigDecimal diversificationPenalty,
    BigDecimal goalProgressScore,
    boolean highInterestDebtCritical,
    boolean fixedObligationsOverCeiling,
    Integer maxAccountStaleDays,
    int historyDays,
    Set<MissingData> missingData
) {

    /**
     * Debt drivers (spec §3.4).
     *
     * @param noDebt           the user has no debt (confirmed, or no liabilities on a linked account)
     * @param dti              debt-to-income; {@code null} when unknown
     * @param ccUtilisation    card utilisation; {@code null} when no card is linked
     * @param highInterestDebt any debt at or above the high-interest APR with a balance
     * @param revolvingBalance a revolving card balance was detected
     */
    public record DebtMetrics(boolean noDebt, BigDecimal dti, BigDecimal ccUtilisation,
                              boolean highInterestDebt, boolean revolvingBalance) {
    }

    /**
     * Metrics for a user with no usable data at all.
     *
     * @param missingData the gates to report as failed
     * @return metrics in which every driver is absent
     */
    public static DriverMetrics nothing(Set<MissingData> missingData) {
        return new DriverMetrics(null, null, null, false, null, null, null, null, null,
            false, false, null, 0, missingData);
    }

    /**
     * Renders every input in a fixed order with normalised numbers, so equal metrics always give an
     * identical string and {@code inputsHash} only changes when a driver really changed.
     *
     * @return the canonical text of these metrics
     */
    String canonical() {
        Map<String, String> fields = new TreeMap<>();
        fields.put("savingsRate", num(savingsRate));
        fields.put("essentialRatio", num(essentialRatio));
        fields.put("discretionaryRatio", num(discretionaryRatio));
        fields.put("lifestyleCreepActive", String.valueOf(lifestyleCreepActive));
        fields.put("emergencyFundMonths", num(emergencyFundMonths));
        fields.put("emergencyMonthsTarget", num(emergencyMonthsTarget));
        fields.put("debt.present", String.valueOf(debt != null));
        if (debt != null) {
            fields.put("debt.noDebt", String.valueOf(debt.noDebt()));
            fields.put("debt.dti", num(debt.dti()));
            fields.put("debt.ccUtilisation", num(debt.ccUtilisation()));
            fields.put("debt.highInterestDebt", String.valueOf(debt.highInterestDebt()));
            fields.put("debt.revolvingBalance", String.valueOf(debt.revolvingBalance()));
        }
        fields.put("diversificationPenalty", num(diversificationPenalty));
        fields.put("goalProgressScore", num(goalProgressScore));
        fields.put("highInterestDebtCritical", String.valueOf(highInterestDebtCritical));
        fields.put("fixedObligationsOverCeiling", String.valueOf(fixedObligationsOverCeiling));
        fields.put("maxAccountStaleDays", String.valueOf(maxAccountStaleDays));
        fields.put("historyDays", String.valueOf(historyDays));
        StringBuilder out = new StringBuilder();
        fields.forEach((k, v) -> out.append(k).append('=').append(v).append(';'));
        return out.toString();
    }

    /**
     * Normalises a number so 0.30 and 0.3 render alike.
     *
     * @param value the number, possibly {@code null}
     * @return its plain-string form without trailing zeros, or {@code "null"}
     */
    private static String num(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
