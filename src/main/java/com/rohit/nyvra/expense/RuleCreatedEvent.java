package com.rohit.nyvra.expense;

import java.util.UUID;

/**
 * Published when a user creates a categorisation rule with {@code applyToExisting=true}. {@link RuleBackfill}
 * handles it after the creating transaction commits.
 *
 * @param userId the rule's owner
 * @param ruleId the new rule
 */
public record RuleCreatedEvent(UUID userId, UUID ruleId) {
}
