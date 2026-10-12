package com.rohit.nyvra.analytics;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A finding the rules engine raised for a user, such as a low emergency fund. A changed finding
 * supersedes the previous one for the same rule instead of duplicating it, so at most one is active per
 * user and rule (enforced by a partial unique index). The {@code insight} table has no
 * {@code created_at}/{@code updated_at}, so this does not extend {@code AbstractEntity}.
 */
@Entity
@Table(name = "insight")
public class Insight implements Persistable<UUID> {

    /** App-generated UUID v7 primary key. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Owner. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Identifier of the rule that raised this insight, e.g. {@code LOW_EMERGENCY_FUND}. */
    @Column(name = "rule_id", nullable = false, updatable = false)
    private String ruleId;

    /** How serious it is. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private InsightSeverity severity;

    /** Human-readable text shown to the user. */
    @Column(nullable = false, updatable = false)
    private String message;

    /** The numbers behind the finding, as free-form JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> evidence;

    /** When it was raised. */
    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    /** When the user dismissed it, if they did. */
    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    /** When a newer insight replaced it or the condition cleared, if that happened. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** The {@code ruleset_version} in force when it was raised. */
    @Column(name = "ruleset_version", updatable = false)
    private String rulesetVersion;

    /** True until the entity is persisted or loaded, so Spring Data issues a plain insert. */
    @Transient
    private boolean isNew = true;

    /** For JPA. */
    protected Insight() {
        // for JPA
    }

    /**
     * Raises a new, active insight.
     *
     * @param userId         the owner
     * @param ruleId         the rule that fired
     * @param severity       how serious it is
     * @param message        text shown to the user
     * @param evidence       the numbers behind it; may be {@code null}
     * @param rulesetVersion the ruleset in force
     * @param raisedAt       when it was raised
     */
    public Insight(UUID userId, String ruleId, InsightSeverity severity, String message,
                   Map<String, Object> evidence, String rulesetVersion, Instant raisedAt) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.message = Objects.requireNonNull(message, "message");
        this.evidence = evidence;
        this.rulesetVersion = rulesetVersion;
        this.raisedAt = Objects.requireNonNull(raisedAt, "raisedAt");
    }

    /** Marks the entity as no longer new once it is stored or loaded. */
    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /**
     * Derives the lifecycle state.
     *
     * @return {@code SUPERSEDED} if replaced, else {@code DISMISSED} if dismissed, else {@code ACTIVE}
     */
    public InsightStatus status() {
        if (supersededAt != null) {
            return InsightStatus.SUPERSEDED;
        }
        return dismissedAt != null ? InsightStatus.DISMISSED : InsightStatus.ACTIVE;
    }

    /**
     * Hides the insight from the user; a no-op if already dismissed.
     *
     * @param at when it was dismissed
     */
    public void dismiss(Instant at) {
        if (dismissedAt == null) {
            this.dismissedAt = at;
        }
    }

    /**
     * Retires the insight because a newer one replaced it or its condition cleared.
     *
     * @param at when it was superseded
     */
    public void supersede(Instant at) {
        if (supersededAt == null) {
            this.supersededAt = at;
        }
    }

    /**
     * Whether a freshly computed finding says the same thing as this one.
     *
     * @param newSeverity the new severity
     * @param newMessage  the new message
     * @param newEvidence the new evidence, compared after JSON normalisation by the caller
     * @return {@code true} if severity, message and evidence all match
     */
    public boolean sameContent(InsightSeverity newSeverity, String newMessage, Map<String, Object> newEvidence) {
        return severity == newSeverity && message.equals(newMessage) && Objects.equals(evidence, newEvidence);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** @return the owner */
    public UUID getUserId() {
        return userId;
    }

    /** @return the rule that raised it */
    public String getRuleId() {
        return ruleId;
    }

    /** @return how serious it is */
    public InsightSeverity getSeverity() {
        return severity;
    }

    /** @return the text shown to the user */
    public String getMessage() {
        return message;
    }

    /** @return the numbers behind it, or {@code null} */
    public Map<String, Object> getEvidence() {
        return evidence;
    }

    /** @return when it was raised */
    public Instant getRaisedAt() {
        return raisedAt;
    }

    /** @return when it was dismissed, or {@code null} */
    public Instant getDismissedAt() {
        return dismissedAt;
    }

    /** @return when it was superseded, or {@code null} */
    public Instant getSupersededAt() {
        return supersededAt;
    }

    /** @return the ruleset in force when it was raised, or {@code null} for older rows */
    public String getRulesetVersion() {
        return rulesetVersion;
    }
}
