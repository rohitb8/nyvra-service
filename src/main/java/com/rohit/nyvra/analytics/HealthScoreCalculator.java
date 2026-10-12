package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rohit.nyvra.analytics.DriverMetrics.DebtMetrics;
import org.springframework.stereotype.Component;

/**
 * Turns {@link DriverMetrics} into a {@link HealthScoreResult} exactly as {@code HEALTH_SCORE_SPEC.md}
 * §3-§4 describes. Pure: no I/O, no clock, so the same inputs always give the same score. Every number
 * comes from {@link FinancialRules}. Arithmetic is full-precision {@link BigDecimal}; values are rounded
 * only for output (FINANCIAL_RULES §7).
 */
@Component
public class HealthScoreCalculator {

    /** Scale used for intermediate divisions. */
    private static final int WORKING_SCALE = 12;
    /** The top of every 0-100 scale. */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** The ceiling of a ramp's input fraction. */
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** The parameters every calculation reads. */
    private final FinancialRules rules;

    /**
     * Creates the calculator.
     *
     * @param rules the financial rule parameters
     */
    public HealthScoreCalculator(FinancialRules rules) {
        this.rules = rules;
    }

    /** An un-rounded sub-score before weighting. */
    private record Raw(BigDecimal value, ScoreStatus status) {
    }

    /**
     * Computes the health score for a set of driver metrics.
     *
     * @param metrics the drivers; absent drivers leave their sub-score unevaluated
     * @param asOf    the computation time to stamp on the result
     * @return the score; its band is {@code INSUFFICIENT_DATA} when too few sub-scores were evaluated
     */
    public HealthScoreResult calculate(DriverMetrics metrics, Instant asOf) {
        Map<SubScoreKey, Raw> raw = new EnumMap<>(SubScoreKey.class);
        putIfPresent(raw, SubScoreKey.SAVINGS, savings(metrics));
        putIfPresent(raw, SubScoreKey.SPENDING_CONTROL, spendingControl(metrics));
        putIfPresent(raw, SubScoreKey.EMERGENCY_FUND, emergencyFund(metrics));
        putIfPresent(raw, SubScoreKey.DEBT, debt(metrics));
        putIfPresent(raw, SubScoreKey.DIVERSIFICATION, diversification(metrics));
        putIfPresent(raw, SubScoreKey.GOAL_PROGRESS, goalProgress(metrics));

        BigDecimal evaluatedWeight = raw.keySet().stream()
            .map(rules.weights()::of).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<SubScoreKey, HealthScoreResult.SubScore> subScores = new EnumMap<>(SubScoreKey.class);
        BigDecimal overallRaw = BigDecimal.ZERO;
        for (SubScoreKey key : SubScoreKey.values()) {
            BigDecimal weight = rules.weights().of(key);
            Raw r = raw.get(key);
            if (r == null) {
                subScores.put(key, new HealthScoreResult.SubScore(null, weight, null, null, false));
                continue;
            }
            BigDecimal effective = weight.divide(evaluatedWeight, WORKING_SCALE, RoundingMode.HALF_UP);
            overallRaw = overallRaw.add(r.value().multiply(effective));
            subScores.put(key, new HealthScoreResult.SubScore(
                round(r.value(), 1), weight, round(effective, 3), r.status(), true));
        }

        BigDecimal confidence = confidence(evaluatedWeight, metrics);
        String hash = inputsHash(metrics);
        boolean sufficient = raw.size() >= rules.minEvaluatedSubScores();
        if (!sufficient) {
            return new HealthScoreResult(asOf, null, ScoreBand.INSUFFICIENT_DATA, confidence, subScores,
                List.of(), null, metrics.missingData().stream().sorted().toList(),
                rules.rulesetVersion(), rules.scoreSpecVersion(), hash);
        }

        BigDecimal overall = round(overallRaw, 1);
        HealthScoreResult.Cap cap = applicableCap(metrics, raw, overall);
        List<String> drivers = new ArrayList<>(topDrivers(raw));
        if (cap != null) {
            overall = round(cap.value(), 1);
            drivers.add(cap.reason().name());
        }
        return new HealthScoreResult(asOf, overall, bandFor(overall), confidence, subScores, drivers, cap,
            List.of(), rules.rulesetVersion(), rules.scoreSpecVersion(), hash);
    }

    /**
     * Buckets a numeric confidence into the level shown to users.
     *
     * @param confidence 0-1
     * @return {@code HIGH} above the high threshold, {@code MEDIUM} from the medium threshold, else {@code LOW}
     */
    public ConfidenceLevel confidenceLevel(BigDecimal confidence) {
        FinancialRules.Confidence c = rules.confidence();
        if (confidence.compareTo(c.highAbove()) > 0) {
            return ConfidenceLevel.HIGH;
        }
        return confidence.compareTo(c.mediumFrom()) >= 0 ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    /**
     * The result shown to a user whose score has never been computed: nothing evaluated, zero confidence,
     * and every gate that can be passed listed as missing.
     *
     * @return an {@code INSUFFICIENT_DATA} result with no {@code asOf}
     */
    public HealthScoreResult noScoreYet() {
        List<MissingData> gates = new ArrayList<>(List.of(MissingData.values()));
        gates.remove(MissingData.GOALS);
        HealthScoreResult computed = calculate(DriverMetrics.nothing(Set.copyOf(gates)), null);
        return new HealthScoreResult(null, null, ScoreBand.INSUFFICIENT_DATA, BigDecimal.ZERO.setScale(3),
            computed.subScores(), List.of(), null, gates, computed.rulesetVersion(),
            computed.scoreSpecVersion(), computed.inputsHash());
    }

    // ------------------------------------------------------------------ sub-scores

    /**
     * Adds a sub-score to the working map when it was evaluated.
     *
     * @param map   the working map
     * @param key   the sub-score
     * @param value its raw result, or {@code null} when not evaluated
     */
    private static void putIfPresent(Map<SubScoreKey, Raw> map, SubScoreKey key, Raw value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Spec §3.1: a ramp of the savings rate between the minimum and stretch rates.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} without a savings rate
     */
    private Raw savings(DriverMetrics m) {
        if (m.savingsRate() == null) {
            return null;
        }
        FinancialRules.Savings s = rules.savings();
        BigDecimal rate = m.savingsRate();
        ScoreStatus status = rate.compareTo(s.targetRate()) >= 0 ? ScoreStatus.OK
            : rate.compareTo(s.minAcceptable()) >= 0 ? ScoreStatus.WARN : ScoreStatus.CRITICAL;
        return new Raw(ramp(rate, s.minAcceptable(), s.stretchRate()), status);
    }

    /**
     * Spec §3.2: the average of two inverse ramps, less the lifestyle-creep penalty.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} unless both ratios are known
     */
    private Raw spendingControl(DriverMetrics m) {
        if (m.essentialRatio() == null || m.discretionaryRatio() == null) {
            return null;
        }
        FinancialRules.Spending s = rules.spending();
        BigDecimal essential = invRamp(m.essentialRatio(), s.essentialFullScoreRatio(), s.essentialZeroScoreRatio());
        BigDecimal discretionary = invRamp(m.discretionaryRatio(), s.discretionaryFullScoreRatio(),
            s.discretionaryZeroScoreRatio());
        BigDecimal half = new BigDecimal("0.5");
        BigDecimal score = essential.multiply(half).add(discretionary.multiply(half));
        if (m.lifestyleCreepActive()) {
            score = floorAtZero(score.subtract(s.lifestyleCreepPenalty()));
        }
        ScoreStatus status;
        if (m.essentialRatio().compareTo(s.essentialCriticalRatio()) > 0) {
            status = ScoreStatus.CRITICAL;
        } else if (m.essentialRatio().compareTo(s.essentialTargetRatio()) > 0
            || m.discretionaryRatio().compareTo(s.discretionaryTargetRatio()) > 0) {
            status = ScoreStatus.WARN;
        } else {
            status = ScoreStatus.OK;
        }
        return new Raw(score, status);
    }

    /**
     * Spec §3.3: a ramp of the months covered between the minimum and the effective target.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} without an emergency-fund figure
     */
    private Raw emergencyFund(DriverMetrics m) {
        if (m.emergencyFundMonths() == null) {
            return null;
        }
        FinancialRules.Emergency e = rules.emergency();
        BigDecimal target = m.emergencyMonthsTarget() != null ? m.emergencyMonthsTarget() : e.monthsTarget();
        BigDecimal months = m.emergencyFundMonths();
        ScoreStatus status = months.compareTo(target) >= 0 ? ScoreStatus.OK
            : months.compareTo(e.monthsMin()) >= 0 ? ScoreStatus.WARN : ScoreStatus.CRITICAL;
        return new Raw(ramp(months, e.monthsMin(), target), status);
    }

    /**
     * Spec §3.4: 100 minus penalties for DTI, card utilisation, high-interest and revolving debt.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} when the debt gate failed
     */
    private Raw debt(DriverMetrics m) {
        DebtMetrics d = m.debt();
        if (d == null) {
            return null;
        }
        if (d.noDebt()) {
            return new Raw(HUNDRED, ScoreStatus.OK);
        }
        FinancialRules.Debt r = rules.debt();
        BigDecimal penalty = BigDecimal.ZERO;
        if (d.dti() != null) {
            if (d.dti().compareTo(r.dtiCeiling()) > 0) {
                penalty = penalty.add(r.dtiAboveCeilingPenalty());
            } else if (d.dti().compareTo(r.dtiTarget()) > 0) {
                penalty = penalty.add(r.dtiAboveTargetPenalty());
            }
        }
        if (d.ccUtilisation() != null) {
            if (d.ccUtilisation().compareTo(r.ccUtilCritical()) > 0) {
                penalty = penalty.add(r.ccUtilAboveCriticalPenalty());
            } else if (d.ccUtilisation().compareTo(r.ccUtilHigh()) > 0) {
                penalty = penalty.add(r.ccUtilAboveHighPenalty());
            } else if (d.ccUtilisation().compareTo(r.ccUtilTarget()) > 0) {
                penalty = penalty.add(r.ccUtilAboveTargetPenalty());
            }
        }
        if (d.highInterestDebt()) {
            penalty = penalty.add(r.highInterestPenalty());
        }
        if (d.revolvingBalance()) {
            penalty = penalty.add(r.revolvingBalancePenalty());
        }
        return new Raw(floorAtZero(HUNDRED.subtract(penalty)), penaltyStatus(penalty));
    }

    /**
     * Spec §3.5: 100 minus the diversification penalty computed where the portfolio is known.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} when the diversification gate failed
     */
    private Raw diversification(DriverMetrics m) {
        if (m.diversificationPenalty() == null) {
            return null;
        }
        return new Raw(floorAtZero(HUNDRED.subtract(m.diversificationPenalty())),
            penaltyStatus(m.diversificationPenalty()));
    }

    /**
     * Spec §3.6: the weighted goal completion, already on a 0-100 scale. The spec defines no status
     * thresholds for goals, so an evaluated goal sub-score is reported {@code OK}.
     *
     * @param m the drivers
     * @return the raw sub-score, or {@code null} when the user has no goals
     */
    private Raw goalProgress(DriverMetrics m) {
        if (m.goalProgressScore() == null) {
            return null;
        }
        return new Raw(m.goalProgressScore().max(BigDecimal.ZERO).min(HUNDRED), ScoreStatus.OK);
    }

    /**
     * Maps a total penalty to a status using the shared warn and critical thresholds.
     *
     * @param penalty total penalty points
     * @return {@code CRITICAL}, {@code WARN} or {@code OK}
     */
    private ScoreStatus penaltyStatus(BigDecimal penalty) {
        if (penalty.compareTo(rules.penaltyStatus().criticalAt()) >= 0) {
            return ScoreStatus.CRITICAL;
        }
        return penalty.compareTo(rules.penaltyStatus().warnAt()) >= 0 ? ScoreStatus.WARN : ScoreStatus.OK;
    }

    // ------------------------------------------------------------------ caps, drivers, bands

    /**
     * Spec §2: the lowest applicable hard cap, if it actually lowers the score.
     *
     * @param m       the drivers
     * @param raw     the evaluated sub-scores
     * @param overall the rounded overall before capping
     * @return the cap that lowered the score, or {@code null}
     */
    private HealthScoreResult.Cap applicableCap(DriverMetrics m, Map<SubScoreKey, Raw> raw, BigDecimal overall) {
        List<HealthScoreResult.Cap> applicable = new ArrayList<>();
        FinancialRules.Caps caps = rules.caps();
        if (raw.containsKey(SubScoreKey.EMERGENCY_FUND)
            && m.emergencyFundMonths().compareTo(rules.emergency().monthsMin()) < 0) {
            applicable.add(new HealthScoreResult.Cap(caps.emergencyFundBelowMin(),
                CapReason.CAP_EMERGENCY_FUND_BELOW_MIN));
        }
        if (m.highInterestDebtCritical()) {
            applicable.add(new HealthScoreResult.Cap(caps.highInterestDebt(), CapReason.CAP_HIGH_INTEREST_DEBT));
        }
        if (m.fixedObligationsOverCeiling()) {
            applicable.add(new HealthScoreResult.Cap(caps.fixedObligationsOverCeiling(),
                CapReason.CAP_FIXED_OBLIGATIONS_OVER_CEILING));
        }
        if (m.savingsRate() != null && m.savingsRate().signum() < 0) {
            applicable.add(new HealthScoreResult.Cap(caps.negativeSavings(), CapReason.CAP_NEGATIVE_SAVINGS));
        }
        return applicable.stream()
            .min(Comparator.comparing(HealthScoreResult.Cap::value))
            .filter(c -> c.value().compareTo(overall) < 0)
            .orElse(null);
    }

    /**
     * Spec §1: up to three evaluated sub-scores with the largest {@code weight * (100 - value)}.
     *
     * @param raw the evaluated sub-scores
     * @return their JSON names, biggest gap first; sub-scores with no gap are omitted
     */
    private List<String> topDrivers(Map<SubScoreKey, Raw> raw) {
        return raw.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), rules.weights().of(e.getKey()).multiply(HUNDRED.subtract(e.getValue().value()))))
            .filter(e -> e.getValue().signum() > 0)
            .sorted(Map.Entry.<SubScoreKey, BigDecimal>comparingByValue().reversed())
            .limit(3)
            .map(e -> e.getKey().json())
            .toList();
    }

    /**
     * Spec §2: the band for a final overall score.
     *
     * @param overall the final overall
     * @return the band it falls into
     */
    private ScoreBand bandFor(BigDecimal overall) {
        FinancialRules.Bands b = rules.bands();
        if (overall.compareTo(b.excellentMin()) >= 0) {
            return ScoreBand.EXCELLENT;
        }
        if (overall.compareTo(b.goodMin()) >= 0) {
            return ScoreBand.GOOD;
        }
        return overall.compareTo(b.fairMin()) >= 0 ? ScoreBand.FAIR : ScoreBand.NEEDS_ATTENTION;
    }

    // ------------------------------------------------------------------ confidence and hash

    /**
     * Spec §4.3: a blend of evaluated weight share, data freshness and history depth.
     *
     * @param evaluatedWeight sum of base weights of the evaluated sub-scores
     * @param m               the drivers
     * @return confidence rounded to three decimals
     */
    private BigDecimal confidence(BigDecimal evaluatedWeight, DriverMetrics m) {
        FinancialRules.Confidence c = rules.confidence();
        BigDecimal freshness = BigDecimal.ONE;
        if (m.maxAccountStaleDays() != null && m.maxAccountStaleDays() > c.freshWithinDays()) {
            BigDecimal progress = ratio(BigDecimal.valueOf(m.maxAccountStaleDays() - c.freshWithinDays()),
                BigDecimal.valueOf(c.staleAfterDays() - c.freshWithinDays())).min(ONE);
            freshness = ONE.subtract(progress.multiply(ONE.subtract(c.staleFactor())));
        }
        BigDecimal history = ramp(BigDecimal.valueOf(m.historyDays()), BigDecimal.valueOf(c.historyMinDays()),
            BigDecimal.valueOf(c.historyFullDays())).divide(HUNDRED, WORKING_SCALE, RoundingMode.HALF_UP);
        return round(c.evaluatedWeightShare().multiply(evaluatedWeight)
            .add(c.dataFreshness().multiply(freshness))
            .add(c.historyDepth().multiply(history)), 3);
    }

    /**
     * Spec §5: {@code sha256} of the canonical driver metrics plus both versions.
     *
     * @param m the drivers
     * @return {@code sha256:} followed by the lower-case hex digest
     */
    private String inputsHash(DriverMetrics m) {
        String canonical = m.canonical() + "ruleset=" + rules.rulesetVersion() + ";spec=" + rules.scoreSpecVersion();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    // ------------------------------------------------------------------ maths

    /**
     * {@code ramp(x, lo, hi) = clamp((x - lo) / (hi - lo), 0, 1) * 100}.
     *
     * @param x  the value
     * @param lo the value that scores 0
     * @param hi the value that scores 100
     * @return the score, 0-100
     */
    private static BigDecimal ramp(BigDecimal x, BigDecimal lo, BigDecimal hi) {
        BigDecimal fraction = ratio(x.subtract(lo), hi.subtract(lo)).max(BigDecimal.ZERO).min(ONE);
        return fraction.multiply(HUNDRED);
    }

    /**
     * {@code invramp(x, lo, hi) = 100 - ramp(x, lo, hi)}.
     *
     * @param x  the value
     * @param lo the value at or below which the score is 100
     * @param hi the value at or above which the score is 0
     * @return the score, 0-100
     */
    private static BigDecimal invRamp(BigDecimal x, BigDecimal lo, BigDecimal hi) {
        return HUNDRED.subtract(ramp(x, lo, hi));
    }

    /**
     * Divides at working precision.
     *
     * @param numerator   the dividend
     * @param denominator the divisor, non-zero
     * @return the quotient
     */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, WORKING_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Clamps a score at zero from below.
     *
     * @param value the score
     * @return {@code value}, or zero if it is negative
     */
    private static BigDecimal floorAtZero(BigDecimal value) {
        return value.max(BigDecimal.ZERO);
    }

    /**
     * Rounds half-up for output.
     *
     * @param value the number
     * @param scale decimal places
     * @return the rounded number
     */
    private static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
