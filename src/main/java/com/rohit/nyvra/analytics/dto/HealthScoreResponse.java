package com.rohit.nyvra.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.rohit.nyvra.analytics.CapReason;
import com.rohit.nyvra.analytics.ConfidenceLevel;
import com.rohit.nyvra.analytics.HealthScoreResult;
import com.rohit.nyvra.analytics.MissingData;
import com.rohit.nyvra.analytics.ScoreBand;
import com.rohit.nyvra.analytics.ScoreStatus;
import com.rohit.nyvra.analytics.SubScoreKey;

/**
 * The {@code GET /health-score} body. Absent values are omitted from the JSON, so an unevaluated
 * sub-score carries only {@code weight} and {@code evaluated}. {@code inputsHash} is internal and not
 * exposed.
 *
 * @param asOf             when computed; absent if no score was ever computed
 * @param overall          0-100, one decimal; absent for {@code INSUFFICIENT_DATA}
 * @param band             the band
 * @param confidence       0-1, three decimals
 * @param confidenceLevel  server-side bucketing of {@code confidence}
 * @param subScores        all six sub-scores
 * @param topDrivers       up to three sub-scores to improve, then the cap reason if one applied
 * @param cap              the hard cap that lowered {@code overall}, if any
 * @param missingData      failed gates, present only for {@code INSUFFICIENT_DATA}
 * @param rulesetVersion   the ruleset the score was computed with
 * @param scoreSpecVersion the spec version the score was computed with
 */
public record HealthScoreResponse(
    Instant asOf,
    BigDecimal overall,
    ScoreBand band,
    BigDecimal confidence,
    ConfidenceLevel confidenceLevel,
    SubScores subScores,
    List<String> topDrivers,
    Cap cap,
    List<MissingData> missingData,
    String rulesetVersion,
    String scoreSpecVersion
) {

    /**
     * The six sub-scores under their contract names.
     *
     * @param savings         savings rate
     * @param spendingControl spending ratios
     * @param emergencyFund   emergency fund months
     * @param debt            debt load
     * @param diversification allocation and concentration
     * @param goalProgress    goal completion
     */
    public record SubScores(SubScore savings, SubScore spendingControl, SubScore emergencyFund, SubScore debt,
                            SubScore diversification, SubScore goalProgress) {
    }

    /**
     * One sub-score.
     *
     * @param value           0-100, one decimal; absent when not evaluated
     * @param weight          base weight
     * @param effectiveWeight redistributed weight, three decimals; absent when not evaluated
     * @param status          {@code OK}, {@code WARN} or {@code CRITICAL}; absent when not evaluated
     * @param evaluated       whether its data gate passed
     */
    public record SubScore(BigDecimal value, BigDecimal weight, BigDecimal effectiveWeight, ScoreStatus status,
                           boolean evaluated) {
    }

    /**
     * A hard cap.
     *
     * @param value  the ceiling applied
     * @param reason why
     */
    public record Cap(BigDecimal value, CapReason reason) {
    }

    /**
     * Maps a score to its response shape.
     *
     * @param result           the score
     * @param level            its confidence bucket
     * @param fallbackRuleset  ruleset version to report for rows stored without one
     * @param fallbackSpec     spec version to report for rows stored without one
     * @return the response
     */
    public static HealthScoreResponse from(HealthScoreResult result, ConfidenceLevel level,
                                           String fallbackRuleset, String fallbackSpec) {
        SubScores subScores = new SubScores(
            sub(result, SubScoreKey.SAVINGS), sub(result, SubScoreKey.SPENDING_CONTROL),
            sub(result, SubScoreKey.EMERGENCY_FUND), sub(result, SubScoreKey.DEBT),
            sub(result, SubScoreKey.DIVERSIFICATION), sub(result, SubScoreKey.GOAL_PROGRESS));
        boolean insufficient = result.band() == ScoreBand.INSUFFICIENT_DATA;
        return new HealthScoreResponse(result.asOf(), result.overall(), result.band(), result.confidence(), level,
            subScores, result.topDrivers(),
            result.cap() == null ? null : new Cap(result.cap().value(), result.cap().reason()),
            insufficient ? result.missingData() : null,
            result.rulesetVersion() != null ? result.rulesetVersion() : fallbackRuleset,
            result.scoreSpecVersion() != null ? result.scoreSpecVersion() : fallbackSpec);
    }

    /**
     * Converts one sub-score.
     *
     * @param result the score
     * @param key    which sub-score
     * @return its response form
     */
    private static SubScore sub(HealthScoreResult result, SubScoreKey key) {
        HealthScoreResult.SubScore s = result.subScores().get(key);
        return new SubScore(s.value(), s.weight(), s.effectiveWeight(), s.status(), s.evaluated());
    }
}
