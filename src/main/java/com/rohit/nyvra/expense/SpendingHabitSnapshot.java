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

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    /** Category id → percentage of total spend. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "by_category_pct")
    private Map<UUID, BigDecimal> byCategoryPct;

    @Column(name = "essential_pct", precision = 5, scale = 2)
    private BigDecimal essentialPct;

    @Column(name = "discretionary_pct", precision = 5, scale = 2)
    private BigDecimal discretionaryPct;

    @Column(name = "total_spend", precision = 19, scale = 2)
    private BigDecimal totalSpend;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected SpendingHabitSnapshot() {
        // for JPA
    }

    public SpendingHabitSnapshot(UUID userId, YearMonth period, String currency) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.periodMonth = period.atDay(1);
        this.currency = Objects.requireNonNull(currency, "currency");
        this.computedAt = Instant.now();
    }

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

    public UUID getUserId() {
        return userId;
    }

    public YearMonth getPeriod() {
        return YearMonth.from(periodMonth);
    }

    public Map<UUID, BigDecimal> getByCategoryPct() {
        return byCategoryPct;
    }

    public BigDecimal getEssentialPct() {
        return essentialPct;
    }

    public BigDecimal getDiscretionaryPct() {
        return discretionaryPct;
    }

    public Money getTotalSpend() {
        return totalSpend == null ? null : Money.of(totalSpend, currency);
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
