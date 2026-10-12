package com.rohit.nyvra.networth;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * An asset or liability the user maintains by hand (property, vehicle, private loan…). It goes stale once its
 * revaluation cadence has elapsed since the value was last confirmed.
 */
@Entity
@Table(name = "manual_asset_liability")
public class ManualAssetLiability extends AbstractEntity {

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Whether the entry is an asset or a liability; fixed at creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ManualItemKind kind;

    /** Category of the entry. */
    @Enumerated(EnumType.STRING)
    @Column(name = "class", nullable = false)
    private ManualItemClass itemClass;

    /** Optional user-facing name, for example "Flat in Pune". */
    @Column(name = "label")
    private String label;

    /** Current value, scale 2 and never negative. */
    @Column(name = "value", nullable = false, precision = 19, scale = 2)
    private BigDecimal value;

    /** ISO 4217 code of {@link #value}; fixed at creation. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** The day the value was last confirmed. */
    @Column(name = "value_as_of", nullable = false)
    private LocalDate valueAsOf;

    /** How long a value stays fresh; {@code null} means it never goes stale. */
    @Column(name = "revaluation_cadence")
    @JdbcTypeCode(SqlTypes.INTERVAL_SECOND)
    private Duration revaluationCadence;

    /** When the entry was soft-deleted, or {@code null} while live. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Required by JPA; not for application use. */
    protected ManualAssetLiability() {
        // for JPA
    }

    /**
     * Creates a live entry.
     *
     * @param userId             the owner
     * @param kind               asset or liability
     * @param itemClass          the category
     * @param label              optional name, may be {@code null}
     * @param value              the current value, not negative
     * @param valueAsOf          the day the value was confirmed
     * @param revaluationCadence how long the value stays fresh, or {@code null} for never stale
     */
    public ManualAssetLiability(UUID userId, ManualItemKind kind, ManualItemClass itemClass, String label,
                                Money value, LocalDate valueAsOf, Duration revaluationCadence) {
        this.userId = userId;
        this.kind = kind;
        this.itemClass = itemClass;
        this.label = label;
        this.value = value.amount();
        this.currency = value.currency();
        this.valueAsOf = valueAsOf;
        this.revaluationCadence = revaluationCadence;
    }

    /**
     * Changes the category.
     *
     * @param itemClass the new category
     */
    public void changeClass(ManualItemClass itemClass) {
        this.itemClass = itemClass;
    }

    /**
     * Renames the entry.
     *
     * @param label the new name; {@code null} clears it
     */
    public void relabel(String label) {
        this.label = label;
    }

    /**
     * Records a fresh valuation.
     *
     * @param value   the new value, same currency as before
     * @param asOf    the day the value was confirmed
     */
    public void revalue(Money value, LocalDate asOf) {
        this.value = value.amount();
        this.valueAsOf = asOf;
    }

    /**
     * Changes how long a value stays fresh.
     *
     * @param cadence the new cadence, or {@code null} for never stale
     */
    public void changeCadence(Duration cadence) {
        this.revaluationCadence = cadence;
    }

    /**
     * Soft-deletes the entry.
     *
     * @param at the deletion time
     */
    public void softDelete(Instant at) {
        this.deletedAt = at;
    }

    /**
     * Tells whether the value is older than its cadence allows.
     *
     * @param today the day to judge against
     * @return {@code true} when a cadence is set and {@code valueAsOf + cadence} is before {@code today}
     */
    public boolean isStale(LocalDate today) {
        return revaluationCadence != null && valueAsOf.plusDays(revaluationCadence.toDays()).isBefore(today);
    }

    /** @return the owning user's id */
    public UUID getUserId() {
        return userId;
    }

    /** @return asset or liability */
    public ManualItemKind getKind() {
        return kind;
    }

    /** @return the category */
    public ManualItemClass getItemClass() {
        return itemClass;
    }

    /** @return the name, or {@code null} */
    public String getLabel() {
        return label;
    }

    /** @return the current value */
    public Money getValue() {
        return Money.of(value, currency);
    }

    /** @return the day the value was last confirmed */
    public LocalDate getValueAsOf() {
        return valueAsOf;
    }

    /** @return how long a value stays fresh, or {@code null} */
    public Duration getRevaluationCadence() {
        return revaluationCadence;
    }

    /** @return the soft-delete time, or {@code null} while live */
    public Instant getDeletedAt() {
        return deletedAt;
    }
}
