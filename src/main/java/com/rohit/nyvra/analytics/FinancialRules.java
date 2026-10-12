package com.rohit.nyvra.analytics;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The named, versioned parameters behind the health score, bound from {@code nyvra.financial-rules}
 * ({@code application.yml}). They mirror {@code FINANCIAL_RULES.md} §8 and the numbers quoted in
 * {@code HEALTH_SCORE_SPEC.md}; nothing in the calculator hard-codes them. A change that alters
 * user-visible scores must bump {@link #rulesetVersion()}.
 *
 * @param rulesetVersion         stamped onto every stored score and insight
 * @param scoreSpecVersion       {@code SCORE_SPEC_VERSION} of the spec the calculator implements
 * @param minEvaluatedSubScores  fewer evaluated sub-scores than this means no numeric overall (spec §4.2)
 * @param weights                base weight of each sub-score (spec §4.1)
 * @param bands                  lower bounds of the bands (spec §2)
 * @param caps                   hard-cap ceilings (spec §2)
 * @param savings                savings-rate thresholds (spec §3.1)
 * @param spending               essential/discretionary ratio thresholds (spec §3.2)
 * @param emergency              emergency-fund months thresholds (spec §3.3)
 * @param debt                   debt penalty thresholds and sizes (spec §3.4)
 * @param penaltyStatus          penalty totals at which a debt or diversification status worsens
 * @param confidence             confidence formula parameters (spec §4.3)
 */
@ConfigurationProperties(prefix = "nyvra.financial-rules")
public record FinancialRules(
    String rulesetVersion,
    String scoreSpecVersion,
    int minEvaluatedSubScores,
    Weights weights,
    Bands bands,
    Caps caps,
    Savings savings,
    Spending spending,
    Emergency emergency,
    Debt debt,
    PenaltyStatus penaltyStatus,
    Confidence confidence
) {

    /**
     * Base weights; they sum to 1.
     *
     * @param savings         weight of the savings sub-score
     * @param spendingControl weight of the spending-control sub-score
     * @param emergencyFund   weight of the emergency-fund sub-score
     * @param debt            weight of the debt sub-score
     * @param diversification weight of the diversification sub-score
     * @param goalProgress    weight of the goal-progress sub-score
     */
    public record Weights(BigDecimal savings, BigDecimal spendingControl, BigDecimal emergencyFund,
                          BigDecimal debt, BigDecimal diversification, BigDecimal goalProgress) {

        /**
         * Looks a weight up by sub-score.
         *
         * @param key the sub-score
         * @return its base weight
         */
        public BigDecimal of(SubScoreKey key) {
            return switch (key) {
                case SAVINGS -> savings;
                case SPENDING_CONTROL -> spendingControl;
                case EMERGENCY_FUND -> emergencyFund;
                case DEBT -> debt;
                case DIVERSIFICATION -> diversification;
                case GOAL_PROGRESS -> goalProgress;
            };
        }
    }

    /**
     * Band lower bounds on the 0-100 scale; anything below {@code fairMin} is {@code NEEDS_ATTENTION}.
     *
     * @param fairMin      lowest overall that is {@code FAIR}
     * @param goodMin      lowest overall that is {@code GOOD}
     * @param excellentMin lowest overall that is {@code EXCELLENT}
     */
    public record Bands(BigDecimal fairMin, BigDecimal goodMin, BigDecimal excellentMin) {
    }

    /**
     * Ceilings applied to {@code overall} after weighting.
     *
     * @param emergencyFundBelowMin     emergency fund under its minimum months
     * @param highInterestDebt          a critical high-interest-debt insight is active
     * @param fixedObligationsOverCeiling fixed obligations are over their hard ceiling
     * @param negativeSavings           the savings rate over the window is negative
     */
    public record Caps(BigDecimal emergencyFundBelowMin, BigDecimal highInterestDebt,
                       BigDecimal fixedObligationsOverCeiling, BigDecimal negativeSavings) {
    }

    /**
     * Savings-rate thresholds.
     *
     * @param targetRate    at or above this the status is {@code OK}
     * @param stretchRate   the rate that scores 100
     * @param minAcceptable the rate that scores 0; below it the status is {@code CRITICAL}
     */
    public record Savings(BigDecimal targetRate, BigDecimal stretchRate, BigDecimal minAcceptable) {
    }

    /**
     * Spending-control thresholds.
     *
     * @param essentialFullScoreRatio      essential ratio at or below which that component scores 100
     * @param essentialZeroScoreRatio      essential ratio at or above which that component scores 0
     * @param discretionaryFullScoreRatio  discretionary ratio at or below which that component scores 100
     * @param discretionaryZeroScoreRatio  discretionary ratio at or above which that component scores 0
     * @param essentialTargetRatio         essential ratio above which the status is {@code WARN}
     * @param discretionaryTargetRatio     discretionary ratio above which the status is {@code WARN}
     * @param essentialCriticalRatio       essential ratio above which the status is {@code CRITICAL}
     * @param lifestyleCreepPenalty        points deducted while lifestyle creep is active
     */
    public record Spending(BigDecimal essentialFullScoreRatio, BigDecimal essentialZeroScoreRatio,
                           BigDecimal discretionaryFullScoreRatio, BigDecimal discretionaryZeroScoreRatio,
                           BigDecimal essentialTargetRatio, BigDecimal discretionaryTargetRatio,
                           BigDecimal essentialCriticalRatio, BigDecimal lifestyleCreepPenalty) {
    }

    /**
     * Emergency-fund thresholds, in months of essential burn.
     *
     * @param monthsTarget default target; scores 100 at or above it
     * @param monthsMin    scores 0 here and triggers the hard cap below it
     */
    public record Emergency(BigDecimal monthsTarget, BigDecimal monthsMin) {
    }

    /**
     * Debt penalty thresholds and sizes; penalties are subtracted from 100.
     *
     * @param dtiTarget                  debt-to-income above this costs {@code dtiAboveTargetPenalty}
     * @param dtiCeiling                 debt-to-income above this costs {@code dtiAboveCeilingPenalty} instead
     * @param dtiAboveTargetPenalty      penalty for DTI in (target, ceiling]
     * @param dtiAboveCeilingPenalty     penalty for DTI above the ceiling
     * @param ccUtilTarget               card utilisation above this is penalised
     * @param ccUtilHigh                 card utilisation above this takes the larger penalty
     * @param ccUtilCritical             card utilisation above this takes the largest penalty
     * @param ccUtilAboveTargetPenalty   penalty for utilisation in (target, high]
     * @param ccUtilAboveHighPenalty     penalty for utilisation in (high, critical]
     * @param ccUtilAboveCriticalPenalty penalty for utilisation above critical
     * @param highInterestPenalty        penalty for any high-interest debt with a balance
     * @param revolvingBalancePenalty    penalty for a detected revolving card balance
     */
    public record Debt(BigDecimal dtiTarget, BigDecimal dtiCeiling, BigDecimal dtiAboveTargetPenalty,
                       BigDecimal dtiAboveCeilingPenalty, BigDecimal ccUtilTarget, BigDecimal ccUtilHigh,
                       BigDecimal ccUtilCritical, BigDecimal ccUtilAboveTargetPenalty,
                       BigDecimal ccUtilAboveHighPenalty, BigDecimal ccUtilAboveCriticalPenalty,
                       BigDecimal highInterestPenalty, BigDecimal revolvingBalancePenalty) {
    }

    /**
     * Penalty totals that move a debt or diversification status.
     *
     * @param warnAt     total penalty at which the status becomes {@code WARN}
     * @param criticalAt total penalty at which the status becomes {@code CRITICAL}
     */
    public record PenaltyStatus(BigDecimal warnAt, BigDecimal criticalAt) {
    }

    /**
     * Confidence formula parameters.
     *
     * @param evaluatedWeightShare multiplier of the evaluated-weight share
     * @param dataFreshness        multiplier of the data-freshness factor
     * @param historyDepth         multiplier of the history-depth factor
     * @param freshWithinDays      data this young is fully fresh
     * @param staleAfterDays       data this old is at {@code staleFactor}
     * @param staleFactor          freshness factor at and beyond {@code staleAfterDays}
     * @param historyMinDays       history shorter than this contributes 0
     * @param historyFullDays      history this long contributes fully
     * @param mediumFrom           lowest confidence reported as {@code MEDIUM}
     * @param highAbove            confidence strictly above this is reported as {@code HIGH}
     */
    public record Confidence(BigDecimal evaluatedWeightShare, BigDecimal dataFreshness, BigDecimal historyDepth,
                             int freshWithinDays, int staleAfterDays, BigDecimal staleFactor,
                             int historyMinDays, int historyFullDays, BigDecimal mediumFrom,
                             BigDecimal highAbove) {
    }
}
