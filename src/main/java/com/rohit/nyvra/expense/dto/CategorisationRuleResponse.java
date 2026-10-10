package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.CategorisationRule;
import com.rohit.nyvra.expense.MatcherType;
import com.rohit.nyvra.expense.Necessity;

/**
 * A categorisation rule on the wire.
 *
 * @param id           the rule id
 * @param matcherType  what the matcher value is matched against
 * @param matcherValue the regex or MCC
 * @param category     the category a match is assigned to
 * @param necessity    the necessity a match is assigned
 * @param priority     higher wins
 */
public record CategorisationRuleResponse(
    UUID id,
    MatcherType matcherType,
    String matcherValue,
    CategoryRef category,
    Necessity necessity,
    int priority) {

    /**
     * Maps a rule.
     *
     * @param rule     the rule
     * @param category the rule's category, already mapped
     * @return its response
     */
    public static CategorisationRuleResponse from(CategorisationRule rule, CategoryRef category) {
        return new CategorisationRuleResponse(rule.getId(), rule.getMatcherType(), rule.getMatcherValue(), category,
            rule.getNecessity(), rule.getPriority());
    }
}
