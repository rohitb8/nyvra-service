package com.rohit.nyvra.expense;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * Maps a merchant/narration pattern or MCC to a category. {@code userId == null} is a system rule; user
 * rules override system rules, and the higher {@code priority} wins.
 */
@Entity
@Table(name = "categorisation_rule")
public class CategorisationRule extends AbstractEntity {

    /** Owner; null for a system rule. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /** What {@link #matcherValue} is matched against. */
    @Enumerated(EnumType.STRING)
    @Column(name = "matcher_type", nullable = false)
    private MatcherType matcherType;

    /** A regex for the {@code *_REGEX} types, a 4-digit merchant category code for {@code MCC}. */
    @Column(name = "matcher_value", nullable = false)
    private String matcherValue;

    /** The category a match is assigned to. */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** The necessity a match is assigned. */
    @Enumerated(EnumType.STRING)
    @Column(name = "necessity", nullable = false)
    private Necessity necessity;

    /** Higher wins. */
    @Column(name = "priority", nullable = false)
    private int priority;

    /** For JPA. */
    protected CategorisationRule() {
        // for JPA
    }

    /**
     * Creates a rule.
     *
     * @param userId       the owner, or null for a system rule
     * @param matcherType  what the value is matched against
     * @param matcherValue the regex or MCC
     * @param categoryId   the category a match is assigned to
     * @param necessity    the necessity a match is assigned
     * @param priority     higher wins
     */
    public CategorisationRule(UUID userId, MatcherType matcherType, String matcherValue, UUID categoryId,
                              Necessity necessity, int priority) {
        this.userId = userId;
        this.matcherType = Objects.requireNonNull(matcherType, "matcherType");
        this.matcherValue = Objects.requireNonNull(matcherValue, "matcherValue");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.necessity = Objects.requireNonNull(necessity, "necessity");
        this.priority = priority;
    }

    /**
     * Changes what the rule matches.
     *
     * @param matcherType  the new matcher type
     * @param matcherValue the new regex or MCC
     */
    public void rematch(MatcherType matcherType, String matcherValue) {
        this.matcherType = Objects.requireNonNull(matcherType, "matcherType");
        this.matcherValue = Objects.requireNonNull(matcherValue, "matcherValue");
    }

    /**
     * Changes the category and necessity a match is assigned.
     *
     * @param categoryId the new category
     * @param necessity  the new necessity
     */
    public void retarget(UUID categoryId, Necessity necessity) {
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.necessity = Objects.requireNonNull(necessity, "necessity");
    }

    /**
     * Changes the priority.
     *
     * @param priority the new priority; higher wins
     */
    public void changePriority(int priority) {
        this.priority = priority;
    }

    /** @return the owner's id, or null for a system rule */
    public UUID getUserId() {
        return userId;
    }

    /** @return what the matcher value is matched against */
    public MatcherType getMatcherType() {
        return matcherType;
    }

    /** @return the regex or MCC */
    public String getMatcherValue() {
        return matcherValue;
    }

    /** @return the category a match is assigned to */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the necessity a match is assigned */
    public Necessity getNecessity() {
        return necessity;
    }

    /** @return the priority; higher wins */
    public int getPriority() {
        return priority;
    }

    /** @return true for a system rule */
    public boolean isSystemRule() {
        return userId == null;
    }
}
