package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one health-score computation, or one stored row read back (HEALTH_SCORE_SPEC §1).
 *
 * @param asOf             when the score was computed; {@code null} only for "no score yet"
 * @param overall          0-100 with one decimal; {@code null} when {@code band} is {@code INSUFFICIENT_DATA}
 * @param band             the band, or {@code INSUFFICIENT_DATA}
 * @param confidence       0-1 with three decimals
 * @param subScores        every sub-score, evaluated or not, in spec order
 * @param topDrivers       up to three sub-scores where improvement helps most, then the cap reason if any
 * @param cap              the hard cap that lowered {@code overall}, or {@code null}
 * @param missingData      failed gates; only meaningful when {@code band} is {@code INSUFFICIENT_DATA}
 * @param rulesetVersion   {@code ruleset_version} the score was computed with
 * @param scoreSpecVersion {@code SCORE_SPEC_VERSION} the score was computed with
 * @param inputsHash       {@code sha256:...} of the driver metrics and versions, for skip-recompute
 */
public record HealthScoreResult(
    Instant asOf,
    BigDecimal overall,
    ScoreBand band,
    BigDecimal confidence,
    Map<SubScoreKey, SubScore> subScores,
    List<String> topDrivers,
    Cap cap,
    List<MissingData> missingData,
    String rulesetVersion,
    String scoreSpecVersion,
    String inputsHash
) {

    /**
     * One sub-score.
     *
     * @param value           0-100 with one decimal; {@code null} when not evaluated
     * @param weight          base weight from the rules
     * @param effectiveWeight weight after redistribution, three decimals; {@code null} when not evaluated
     * @param status          {@code OK}, {@code WARN} or {@code CRITICAL}; {@code null} when not evaluated
     * @param evaluated       whether its data-sufficiency gate passed
     */
    public record SubScore(BigDecimal value, BigDecimal weight, BigDecimal effectiveWeight,
                           ScoreStatus status, boolean evaluated) {
    }

    /**
     * A hard cap that lowered the overall score.
     *
     * @param value  the ceiling that was applied
     * @param reason why it applied
     */
    public record Cap(BigDecimal value, CapReason reason) {
    }
}
