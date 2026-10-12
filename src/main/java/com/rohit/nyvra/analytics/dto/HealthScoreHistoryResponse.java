package com.rohit.nyvra.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.rohit.nyvra.analytics.HealthScoreService;
import com.rohit.nyvra.analytics.ScoreBand;

/**
 * The {@code GET /health-score/history} body.
 *
 * @param granularity bucket size used
 * @param from        first day covered, inclusive
 * @param to          last day covered, inclusive
 * @param points      the last score in each non-empty bucket, oldest first
 */
public record HealthScoreHistoryResponse(HealthScoreService.Granularity granularity, LocalDate from, LocalDate to,
                                         List<Point> points) {

    /**
     * One bucket's score.
     *
     * @param asOf    when the score was computed
     * @param overall 0-100, one decimal
     * @param band    the band
     */
    public record Point(Instant asOf, BigDecimal overall, ScoreBand band) {
    }
}
