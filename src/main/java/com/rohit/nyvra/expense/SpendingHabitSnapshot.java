package com.rohit.nyvra.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A user's spending breakdown for one calendar month, recomputed whenever an expense in that month
 * changes. Stores results only — the percentages are computed by the Expenses service, never here.
 */
@Entity
@Table(name = "spending_habit_snapshot")
public class SpendingHabitSnapshot extends AbstractEntity {

    /** Owner of the snapshot. Immutable. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** First day of the month the snapshot covers. Immutable. */
    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    /** Category id → percentage of total spend. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "by_category_pct")
    private Map<UUID, BigDecimal> byCategoryPct;

    /** Essential spend as a percentage of total spend (scale 2); null until computed. */
    @Column(name = "essential_pct", precision = 5, scale = 2)
    private BigDecimal essentialPct;

    /** Discretionary spend as a percentage of total spend (scale 2); null until computed. */
    @Column(name = "discretionary_pct", precision = 5, scale = 2)
    private BigDecimal discretionaryPct;

    /** Total spend for the month in {@link #currency}, scale 2; null until computed. */
    @Column(name = "total_spend", precision = 19, scale = 2)
    private BigDecimal totalSpend;

    /** ISO 4217 currency of {@link #totalSpend}. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** When the figures were last computed (UTC). */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    /** Required by JPA; not for application use. */
    protected SpendingHabitSnapshot() {
        // for JPA
    }

    /**
     * Creates an empty snapshot for a month; call {@link #recompute} to fill it.
     *
     * @param userId   the owner, not null
     * @param period   the month covered
     * @param currency the ISO 4217 currency, not null
     * @throws NullPointerException if {@code userId} or {@code currency} is null
     */
    public SpendingHabitSnapshot(UUID userId, YearMonth period, String currency) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.periodMonth = period.atDay(1);
        this.currency = Objects.requireNonNull(currency, "currency");
        this.computedAt = Instant.now();
    }

    /**
     * Replaces the computed figures.
     *
     * @param byCategoryPct    category id to percentage of total spend
     * @param essentialPct     essential share of spend, in percent
     * @param discretionaryPct discretionary share of spend, in percent
     * @param totalSpend       total spend, in the snapshot's currency
     * @param computedAt       when the figures were computed, not null
     * @throws IllegalArgumentException if {@code totalSpend} is in a different currency
     * @throws NullPointerException     if {@code computedAt} is null
     */
    public void recompute(Map<UUID, BigDecimal> byCategoryPct, BigDecimal essentialPct,
                          BigDecimal discretionaryPct, Money totalSpend, Instant computedAt) {
        if (!totalSpend.currency().equals(currency)) {
            throw new IllegalArgumentException("Total spend currency must be " + currency);
        }
        this.byCategoryPct = Map.copyOf(byCategoryPct);
        this.essentialPct = essentialPct;
        this.discretionaryPct = discretionaryPct;
        this.totalSpend = totalSpend.amount();
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt");
    }

    /** @return the owner */
    public UUID getUserId() {
        return userId;
    }

    /** @return the month covered */
    public YearMonth getPeriod() {
        return YearMonth.from(periodMonth);
    }

    /** @return category id to percentage of total spend; null until computed */
    public Map<UUID, BigDecimal> getByCategoryPct() {
        return byCategoryPct;
    }

    /** @return essential share in percent, or null until computed */
    public BigDecimal getEssentialPct() {
        return essentialPct;
    }

    /** @return discretionary share in percent, or null until computed */
    public BigDecimal getDiscretionaryPct() {
        return discretionaryPct;
    }

    /** @return total spend for the month, or null until computed */
    public Money getTotalSpend() {
        return totalSpend == null ? null : Money.of(totalSpend, currency);
    }

    /** @return the ISO 4217 currency code */
    public String getCurrency() {
        return currency;
    }

    /** @return when the figures were last computed (UTC) */
    public Instant getComputedAt() {
        return computedAt;
    }
}
