package com.rohit.nyvra.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.analytics.dto.InsightResponse;
import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for insights: listing and dismissing them for the signed-in user, and {@link #raise raising}
 * or {@link #clear clearing} them on behalf of the rules engine. A rule never has two active insights:
 * a changed finding supersedes the old one.
 */
@Service
public class InsightService {

    /** Insight persistence. */
    private final InsightRepository insights;
    /** Supplies the ruleset version stamped on new insights. */
    private final FinancialRules rules;
    /** Resolves the signed-in user every read and dismissal is scoped to. */
    private final CurrentUserService currentUser;
    /** Normalises evidence so equal findings compare equal regardless of number types. */
    private final ObjectMapper json;

    /**
     * Creates the service.
     *
     * @param insights    the insight repository
     * @param rules       the financial rule parameters
     * @param currentUser the signed-in user resolver
     * @param json        the shared JSON mapper
     */
    public InsightService(InsightRepository insights, FinancialRules rules, CurrentUserService currentUser,
                          ObjectMapper json) {
        this.insights = insights;
        this.rules = rules;
        this.currentUser = currentUser;
        this.json = json;
    }

    /**
     * Lists the caller's insights, most serious first, newest first within a severity.
     *
     * @param status     lifecycle state to list
     * @param severities severities to include; {@code null} or empty means all
     * @param page       zero-based page index
     * @param size       page size
     * @return one page of insights
     */
    @Transactional(readOnly = true)
    public PageResponse<InsightResponse> list(InsightStatus status, List<InsightSeverity> severities,
                                              int page, int size) {
        List<InsightSeverity> filter = severities == null || severities.isEmpty()
            ? List.of(InsightSeverity.values()) : severities;
        return PageResponse.of(insights.search(currentUser.currentUser().getId(), status.name(), filter,
            PageRequest.of(page, size)), InsightResponse::from);
    }

    /**
     * Dismisses one of the caller's insights. Dismissing an already dismissed insight is a no-op.
     *
     * @param id the insight id
     * @return the insight after dismissal
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws ConflictException         if it was already superseded (code {@code INSIGHT_SUPERSEDED})
     */
    @Transactional
    public InsightResponse dismiss(UUID id) {
        Insight insight = insights.findByIdAndUserId(id, currentUser.currentUser().getId())
            .orElseThrow(() -> ResourceNotFoundException.of("Insight", id));
        if (insight.status() == InsightStatus.SUPERSEDED) {
            throw new ConflictException("INSIGHT_SUPERSEDED", "This insight has been replaced and can't be dismissed.");
        }
        insight.dismiss(Instant.now());
        return InsightResponse.from(insights.save(insight));
    }

    /**
     * Raises a finding for a user. If the rule's latest live insight already says the same thing (even if
     * the user dismissed it) nothing changes; if it says something different, the old one is superseded
     * and a new active insight takes its place. Called by the rules engine, not by an endpoint.
     *
     * @param userId   the user the finding is about
     * @param ruleId   the rule that fired
     * @param severity how serious it is
     * @param message  text to show the user
     * @param evidence the numbers behind it; may be {@code null}
     * @return the insight now current for this rule
     */
    @Transactional
    public InsightResponse raise(UUID userId, String ruleId, InsightSeverity severity, String message,
                                 Map<String, Object> evidence) {
        Map<String, Object> normalised = normalise(evidence);
        Optional<Insight> live = insights
            .findFirstByUserIdAndRuleIdAndSupersededAtIsNullOrderByRaisedAtDesc(userId, ruleId);
        if (live.isPresent() && live.get().sameContent(severity, message, normalised)) {
            return InsightResponse.from(live.get());
        }
        Instant now = Instant.now();
        if (live.isPresent()) {
            live.get().supersede(now);
            // Flush the supersede first: the partial unique index allows one active insight per rule.
            insights.saveAndFlush(live.get());
        }
        return InsightResponse.from(insights.save(
            new Insight(userId, ruleId, severity, message, normalised, rules.rulesetVersion(), now)));
    }

    /**
     * Retires the rule's live insight because its condition no longer holds. A no-op if there is none.
     *
     * @param userId the user the finding was about
     * @param ruleId the rule that no longer fires
     */
    @Transactional
    public void clear(UUID userId, String ruleId) {
        insights.findFirstByUserIdAndRuleIdAndSupersededAtIsNullOrderByRaisedAtDesc(userId, ruleId)
            .ifPresent(insight -> {
                insight.supersede(Instant.now());
                insights.save(insight);
            });
    }

    /**
     * Round-trips evidence through JSON so what is compared equals what is stored and read back.
     *
     * @param evidence the evidence, possibly {@code null}
     * @return the normalised evidence, or {@code null}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalise(Map<String, Object> evidence) {
        if (evidence == null) {
            return null;
        }
        JsonNode tree = json.valueToTree(evidence);
        return json.convertValue(tree, Map.class);
    }
}
