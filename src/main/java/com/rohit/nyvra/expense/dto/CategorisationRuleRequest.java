package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.MatcherType;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creates a categorisation rule.
 *
 * @param matcherType  what the value is matched against
 * @param matcherValue a regex for the {@code *_REGEX} types (must compile), a 4-digit code for {@code MCC}
 * @param categoryId   the category a match is assigned to
 * @param necessity    the necessity a match is assigned
 * @param priority     0 to 1000, higher wins; defaults to 100 when absent
 */
public record CategorisationRuleRequest(
    @NotNull MatcherType matcherType,
    @NotBlank @Size(max = 200) String matcherValue,
    @NotNull UUID categoryId,
    @NotNull Necessity necessity,
    @Min(0) @Max(1000) Integer priority) {
}
