package com.rohit.nyvra.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import com.rohit.nyvra.analytics.DriverMetrics.DebtMetrics;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link HealthScoreCalculator} against {@code HEALTH_SCORE_SPEC.md}: the worked example,
 * redistribution, the insufficient-data rule, hard caps, penalties, confidence and the inputs hash. The
 * parameters are the shipped ones from {@code application.yml}.
 */
class HealthScoreCalculatorTest {

    /** A fixed computation time. */
    private static final Instant AS_OF = Instant.parse("2026-10-06T00:00:00Z");

    /** The calculator under test, built on the shipped rules. */
    private final HealthScoreCalculator calculator = new HealthScoreCalculator(FinancialRulesFixture.load());

    /**
     * Shorthand for a decimal.
     *
     * @param value its text
     * @return the number
     */
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /**
     * Metrics matching the spec §6 worked example: 30% savings, 45%/25% spend ratios, 6 months of cover,
     * no debt, an 80-point diversification (20 penalty), no goals, fresh data and 200 days of history.
     *
     * @return the example's drivers
     */
    private static DriverMetrics workedExample() {
        return new DriverMetrics(d("0.30"), d("0.45"), d("0.25"), false, d("6.0"), null,
            new DebtMetrics(true, null, null, false, false), d("20"), null,
            false, false, 0, 200, Set.of());
    }

    /**
     * Copies metrics with a different savings rate, for cap tests.
     *
     * @param m    the base metrics
     * @param rate the new savings rate
     * @return the changed metrics
     */
    private static DriverMetrics withSavings(DriverMetrics m, String rate) {
        return new DriverMetrics(d(rate), m.essentialRatio(), m.discretionaryRatio(), m.lifestyleCreepActive(),
            m.emergencyFundMonths(), m.emergencyMonthsTarget(), m.debt(), m.diversificationPenalty(),
            m.goalProgressScore(), m.highInterestDebtCritical(), m.fixedObligationsOverCeiling(),
            m.maxAccountStaleDays(), m.historyDays(), m.missingData());
    }

    /** The spec's worked example must reproduce its published numbers. */
    @Test
    void reproducesTheSpecWorkedExample() {
        HealthScoreResult r = calculator.calculate(workedExample(), AS_OF);

        assertThat(r.overall()).isEqualByComparingTo("96.8");
        assertThat(r.band()).isEqualTo(ScoreBand.EXCELLENT);
        assertThat(r.confidence()).isEqualByComparingTo("0.980");
        assertThat(calculator.confidenceLevel(r.confidence())).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(r.topDrivers()).containsExactly("diversification");
        assertThat(r.cap()).isNull();
        assertThat(r.subScores().get(SubScoreKey.DIVERSIFICATION).value()).isEqualByComparingTo("80.0");
        assertThat(r.subScores().get(SubScoreKey.SAVINGS).effectiveWeight()).isEqualByComparingTo("0.263");
        assertThat(r.subScores().get(SubScoreKey.DIVERSIFICATION).effectiveWeight()).isEqualByComparingTo("0.158");
        assertThat(r.subScores().get(SubScoreKey.GOAL_PROGRESS).evaluated()).isFalse();
        assertThat(r.subScores().get(SubScoreKey.GOAL_PROGRESS).value()).isNull();
        assertThat(r.rulesetVersion()).isEqualTo("2026.2");
        assertThat(r.scoreSpecVersion()).isEqualTo("2026.1");
    }

    /** Fewer than three evaluated sub-scores means no numeric overall and the checklist is passed on. */
    @Test
    void withFewerThanThreeEvaluatedReportsInsufficientData() {
        DriverMetrics only2 = new DriverMetrics(d("0.30"), null, null, false, null, null,
            new DebtMetrics(true, null, null, false, false), null, null, false, false, null, 10,
            Set.of(MissingData.HOLDINGS, MissingData.INCOME_ENTRY));

        HealthScoreResult r = calculator.calculate(only2, AS_OF);

        assertThat(r.band()).isEqualTo(ScoreBand.INSUFFICIENT_DATA);
        assertThat(r.overall()).isNull();
        assertThat(r.missingData()).containsExactly(MissingData.INCOME_ENTRY, MissingData.HOLDINGS);
        assertThat(r.subScores().get(SubScoreKey.DEBT).effectiveWeight()).isEqualByComparingTo("0.444");
    }

    /** Emergency fund under its minimum caps the score at 45 and appends the reason to the drivers. */
    @Test
    void emergencyFundBelowMinimumCapsAt45() {
        DriverMetrics m = new DriverMetrics(d("0.30"), d("0.45"), d("0.25"), false, d("2.0"), null,
            new DebtMetrics(true, null, null, false, false), d("0"), null, false, false, 0, 200, Set.of());

        HealthScoreResult r = calculator.calculate(m, AS_OF);

        assertThat(r.overall()).isEqualByComparingTo("45.0");
        assertThat(r.band()).isEqualTo(ScoreBand.FAIR);
        assertThat(r.cap()).isEqualTo(new HealthScoreResult.Cap(d("45"), CapReason.CAP_EMERGENCY_FUND_BELOW_MIN));
        assertThat(r.topDrivers()).containsExactly("emergencyFund", "CAP_EMERGENCY_FUND_BELOW_MIN");
        assertThat(r.subScores().get(SubScoreKey.EMERGENCY_FUND).status()).isEqualTo(ScoreStatus.CRITICAL);
    }

    /** A negative savings rate wins over a higher cap because the lowest applicable cap applies. */
    @Test
    void lowestApplicableCapWins() {
        DriverMetrics base = new DriverMetrics(d("-0.05"), d("0.45"), d("0.25"), false, d("2.0"), null,
            new DebtMetrics(true, null, null, false, false), d("0"), null, true, false, 0, 200, Set.of());

        HealthScoreResult r = calculator.calculate(base, AS_OF);

        assertThat(r.cap().reason()).isEqualTo(CapReason.CAP_NEGATIVE_SAVINGS);
        assertThat(r.overall()).isEqualByComparingTo("40.0");
        assertThat(r.band()).isEqualTo(ScoreBand.FAIR);
    }

    /** A cap above the computed score changes nothing and is not reported. */
    @Test
    void capThatDoesNotLowerTheScoreIsNotReported() {
        DriverMetrics m = new DriverMetrics(d("0.10"), d("0.75"), d("0.55"), false, d("3.0"), null,
            new DebtMetrics(false, d("0.5"), d("0.9"), true, true), d("60"), null, true, false, 0, 200, Set.of());

        HealthScoreResult r = calculator.calculate(m, AS_OF);

        assertThat(r.overall().doubleValue()).isLessThan(55.0);
        assertThat(r.cap()).isNull();
        assertThat(r.band()).isEqualTo(ScoreBand.NEEDS_ATTENTION);
    }

    /** Savings maps 10%..30% onto 0..100 and its status follows target and minimum. */
    @Test
    void savingsRampAndStatus() {
        DriverMetrics m = withSavings(workedExample(), "0.20");
        HealthScoreResult.SubScore s = calculator.calculate(m, AS_OF).subScores().get(SubScoreKey.SAVINGS);
        assertThat(s.value()).isEqualByComparingTo("50.0");
        assertThat(s.status()).isEqualTo(ScoreStatus.OK);

        s = calculator.calculate(withSavings(workedExample(), "0.15"), AS_OF).subScores().get(SubScoreKey.SAVINGS);
        assertThat(s.value()).isEqualByComparingTo("25.0");
        assertThat(s.status()).isEqualTo(ScoreStatus.WARN);

        s = calculator.calculate(withSavings(workedExample(), "0.05"), AS_OF).subScores().get(SubScoreKey.SAVINGS);
        assertThat(s.value()).isEqualByComparingTo("0.0");
        assertThat(s.status()).isEqualTo(ScoreStatus.CRITICAL);
    }

    /** Lifestyle creep deducts points and a high essential ratio is critical. */
    @Test
    void spendingControlPenaltyAndStatus() {
        DriverMetrics creep = new DriverMetrics(d("0.30"), d("0.45"), d("0.25"), true, d("6.0"), null,
            new DebtMetrics(true, null, null, false, false), d("0"), null, false, false, 0, 200, Set.of());
        assertThat(calculator.calculate(creep, AS_OF).subScores().get(SubScoreKey.SPENDING_CONTROL).value())
            .isEqualByComparingTo("90.0");

        DriverMetrics heavy = new DriverMetrics(d("0.30"), d("0.72"), d("0.25"), false, d("6.0"), null,
            new DebtMetrics(true, null, null, false, false), d("0"), null, false, false, 0, 200, Set.of());
        HealthScoreResult.SubScore s = calculator.calculate(heavy, AS_OF).subScores().get(SubScoreKey.SPENDING_CONTROL);
        assertThat(s.status()).isEqualTo(ScoreStatus.CRITICAL);
        assertThat(s.value()).isEqualByComparingTo("56.0");
    }

    /** Debt penalties add up, floor at zero and set the status from the total. */
    @Test
    void debtPenaltiesAccumulate() {
        DriverMetrics m = new DriverMetrics(d("0.30"), d("0.45"), d("0.25"), false, d("6.0"), null,
            new DebtMetrics(false, d("0.40"), d("0.60"), true, false), d("0"), null, false, false, 0, 200, Set.of());

        HealthScoreResult.SubScore debt = calculator.calculate(m, AS_OF).subScores().get(SubScoreKey.DEBT);

        // dti 0.40 -> -15, utilisation 0.60 -> -25, high-interest -> -30 = 70 penalty
        assertThat(debt.value()).isEqualByComparingTo("30.0");
        assertThat(debt.status()).isEqualTo(ScoreStatus.CRITICAL);
    }

    /** Stale linked data lowers confidence on a ramp from fresh (7 days) to stale (45 days). */
    @Test
    void staleDataReducesConfidence() {
        DriverMetrics stale = new DriverMetrics(d("0.30"), d("0.45"), d("0.25"), false, d("6.0"), null,
            new DebtMetrics(true, null, null, false, false), d("20"), null, false, false, 45, 200, Set.of());

        HealthScoreResult r = calculator.calculate(stale, AS_OF);

        // 0.4*0.95 + 0.4*0.3 + 0.2*1.0
        assertThat(r.confidence()).isEqualByComparingTo("0.700");
        assertThat(calculator.confidenceLevel(r.confidence())).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    /** The level buckets follow the spec's thresholds, with exactly 0.8 still medium. */
    @Test
    void confidenceLevelBoundaries() {
        assertThat(calculator.confidenceLevel(d("0.499"))).isEqualTo(ConfidenceLevel.LOW);
        assertThat(calculator.confidenceLevel(d("0.5"))).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(calculator.confidenceLevel(d("0.8"))).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(calculator.confidenceLevel(d("0.801"))).isEqualTo(ConfidenceLevel.HIGH);
    }

    /** The hash ignores number formatting but changes with a driver or a version. */
    @Test
    void inputsHashIsStableForEqualMetricsAndChangesWithDrivers() {
        String a = calculator.calculate(workedExample(), AS_OF).inputsHash();
        String sameButFormattedDifferently = calculator.calculate(
            withSavings(workedExample(), "0.3000"), AS_OF.plusSeconds(60)).inputsHash();
        String changed = calculator.calculate(withSavings(workedExample(), "0.25"), AS_OF).inputsHash();

        assertThat(a).startsWith("sha256:").isEqualTo(sameButFormattedDifferently).isNotEqualTo(changed);
    }

    /** With nothing computed yet the placeholder is insufficient, zero-confidence and lists the gates. */
    @Test
    void noScoreYetListsEveryGateButGoals() {
        HealthScoreResult r = calculator.noScoreYet();

        assertThat(r.asOf()).isNull();
        assertThat(r.band()).isEqualTo(ScoreBand.INSUFFICIENT_DATA);
        assertThat(r.confidence()).isEqualByComparingTo("0");
        assertThat(r.missingData()).doesNotContain(MissingData.GOALS).contains(MissingData.INCOME_ENTRY,
            MissingData.HOLDINGS);
        assertThat(r.subScores().values()).noneMatch(HealthScoreResult.SubScore::evaluated);
    }
}
