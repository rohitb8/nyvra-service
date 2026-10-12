package com.rohit.nyvra.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.analytics.dto.HealthScoreHistoryResponse;
import com.rohit.nyvra.analytics.dto.HealthScoreResponse;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for the Financial Health Score: reading the latest score and its trend for the signed-in
 * user, and {@link #record recording} a newly computed score. Nothing here defines the formula; that is
 * {@link HealthScoreCalculator}.
 */
@Service
public class HealthScoreService {

    /** Days of history returned when the caller gives no {@code from}. */
    private static final int DEFAULT_HISTORY_DAYS = 180;
    /** The user's display time zone, which decides what "today" is and where trend buckets start. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Stored scores. */
    private final HealthScoreRepository scores;
    /** The scoring formula. */
    private final HealthScoreCalculator calculator;
    /** Supplies the version stamps for rows stored before they were recorded. */
    private final FinancialRules rules;
    /** Resolves the signed-in user every read is scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param scores      the score repository
     * @param calculator  the score calculator
     * @param rules       the financial rule parameters
     * @param currentUser the signed-in user resolver
     */
    public HealthScoreService(HealthScoreRepository scores, HealthScoreCalculator calculator,
                              FinancialRules rules, CurrentUserService currentUser) {
        this.scores = scores;
        this.calculator = calculator;
        this.rules = rules;
        this.currentUser = currentUser;
    }

    /** Granularity of the trend; one point per bucket. */
    public enum Granularity {
        /** One point per day. */
        DAY("day"),
        /** One point per week, starting Monday. */
        WEEK("week"),
        /** One point per calendar month. */
        MONTH("month");

        /** The matching {@code date_trunc} unit. */
        private final String unit;

        /**
         * Creates a granularity.
         *
         * @param unit its {@code date_trunc} unit
         */
        Granularity(String unit) {
            this.unit = unit;
        }

        /** @return the {@code date_trunc} unit for this granularity */
        String unit() {
            return unit;
        }
    }

    /**
     * Returns the caller's latest score. Always succeeds: with nothing stored yet the answer is an
     * {@code INSUFFICIENT_DATA} score listing what to connect.
     *
     * @return the latest score, or the "no score yet" placeholder
     */
    @Transactional(readOnly = true)
    public HealthScoreResponse latest() {
        HealthScoreResult result = scores.findLatest(currentUser.currentUser().getId())
            .orElseGet(calculator::noScoreYet);
        return HealthScoreResponse.from(result, calculator.confidenceLevel(result.confidence()),
            rules.rulesetVersion(), rules.scoreSpecVersion());
    }

    /**
     * Returns the caller's score trend: the last score in each bucket, oldest first.
     *
     * @param from        inclusive start; defaults to 180 days before {@code to}
     * @param to          inclusive end; defaults to today (Asia/Kolkata)
     * @param granularity bucket size
     * @return the trend
     * @throws BadRequestException if {@code from} is after {@code to}
     */
    @Transactional(readOnly = true)
    public HealthScoreHistoryResponse history(LocalDate from, LocalDate to, Granularity granularity) {
        LocalDate end = to != null ? to : LocalDate.now(DISPLAY_ZONE);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        Instant fromInstant = start.atStartOfDay(DISPLAY_ZONE).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant();
        List<HealthScoreRepository.HistoryPoint> points = scores.history(
            currentUser.currentUser().getId(), fromInstant, toExclusive, granularity.unit(), DISPLAY_ZONE.getId());
        return new HealthScoreHistoryResponse(granularity, start, end, points.stream()
            .map(p -> new HealthScoreHistoryResponse.Point(p.asOf(), p.overall(), p.band())).toList());
    }

    /**
     * Computes and stores a user's score from fresh driver metrics. Nothing is written when the result
     * is {@code INSUFFICIENT_DATA} (it has no overall to store) or when the inputs hash matches the
     * latest stored row (HEALTH_SCORE_SPEC §5). This is the entry point the recompute triggers call once
     * the source modules can supply {@link DriverMetrics}.
     *
     * @param userId  the user to score
     * @param metrics the current drivers
     * @param asOf    the computation time
     * @return the computed result, whether or not it was stored
     */
    @Transactional
    public HealthScoreResult record(UUID userId, DriverMetrics metrics, Instant asOf) {
        HealthScoreResult result = calculator.calculate(metrics, asOf);
        if (result.band() == ScoreBand.INSUFFICIENT_DATA) {
            return result;
        }
        boolean unchanged = scores.findLatest(userId)
            .map(latest -> result.inputsHash().equals(latest.inputsHash()))
            .orElse(false);
        if (!unchanged) {
            scores.insert(userId, result);
        }
        return result;
    }
}
