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

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "matcher_type", nullable = false)
    private MatcherType matcherType;

    @Column(name = "matcher_value", nullable = false)
    private String matcherValue;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "necessity", nullable = false)
    private Necessity necessity;

    @Column(name = "priority", nullable = false)
    private int priority;

    protected CategorisationRule() {
        // for JPA
    }

    /** @param userId null for a system rule */
    public CategorisationRule(UUID userId, MatcherType matcherType, String matcherValue, UUID categoryId,
                              Necessity necessity, int priority) {
        this.userId = userId;
        this.matcherType = Objects.requireNonNull(matcherType, "matcherType");
        this.matcherValue = Objects.requireNonNull(matcherValue, "matcherValue");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.necessity = Objects.requireNonNull(necessity, "necessity");
        this.priority = priority;
    }

    public void retarget(UUID categoryId, Necessity necessity) {
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.necessity = Objects.requireNonNull(necessity, "necessity");
    }

    public void changePriority(int priority) {
        this.priority = priority;
    }

    public UUID getUserId() {
        return userId;
    }

    public MatcherType getMatcherType() {
        return matcherType;
    }

    public String getMatcherValue() {
        return matcherValue;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public Necessity getNecessity() {
        return necessity;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isSystemRule() {
        return userId == null;
    }
}
