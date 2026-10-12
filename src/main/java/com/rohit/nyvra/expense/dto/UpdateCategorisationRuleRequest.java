package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.MatcherType;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a categorisation rule; omitted fields are unchanged.
 *
 * @param matcherType  the new matcher type
 * @param matcherValue the new regex or MCC
 * @param categoryId   the new category
 * @param necessity    the new necessity
 * @param priority     the new priority, 0 to 1000
 */
public record UpdateCategorisationRuleRequest(
    MatcherType matcherType,
    @Size(min = 1, max = 200) String matcherValue,
    UUID categoryId,
    Necessity necessity,
    @Min(0) @Max(1000) Integer priority) {
}
