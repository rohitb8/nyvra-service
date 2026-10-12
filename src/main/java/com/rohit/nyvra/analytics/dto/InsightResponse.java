package com.rohit.nyvra.analytics.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.analytics.Insight;
import com.rohit.nyvra.analytics.InsightSeverity;
import com.rohit.nyvra.analytics.InsightStatus;

/**
 * An insight as returned by the API.
 *
 * @param id             the insight id
 * @param ruleId         the rule that raised it
 * @param severity       how serious it is
 * @param status         {@code ACTIVE}, {@code DISMISSED} or {@code SUPERSEDED}
 * @param message        text to show the user
 * @param evidence       the numbers behind it; absent when none
 * @param raisedAt       when it was raised
 * @param dismissedAt    when the user dismissed it; absent if not
 * @param rulesetVersion the ruleset in force when raised; absent for older rows
 */
public record InsightResponse(UUID id, String ruleId, InsightSeverity severity, InsightStatus status,
                              String message, Map<String, Object> evidence, Instant raisedAt,
                              Instant dismissedAt, String rulesetVersion) {

    /**
     * Maps an entity to its response.
     *
     * @param insight the insight
     * @return the response
     */
    public static InsightResponse from(Insight insight) {
        return new InsightResponse(insight.getId(), insight.getRuleId(), insight.getSeverity(), insight.status(),
            insight.getMessage(), insight.getEvidence(), insight.getRaisedAt(), insight.getDismissedAt(),
            insight.getRulesetVersion());
    }
}
