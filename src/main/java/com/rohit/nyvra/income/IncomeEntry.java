package com.rohit.nyvra.income;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
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
 * One received payment for an {@link IncomeSource}, covering {@code [periodStart, periodEnd]}. Periods of
 * the same source never overlap (DB exclusion constraint {@code ex_income_entry_no_overlap}).
 *
 * <p>{@code linkedTransactionId} is the id of an Accounts transaction — a plain reference, no foreign
 * key, since modules never reach into each other's tables.
 */
@Entity
@Table(name = "income_entry")
public class IncomeEntry extends AbstractEntity {

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "linked_transaction_id")
    private UUID linkedTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private IncomeOrigin origin;

    protected IncomeEntry() {
        // for JPA
    }

    public IncomeEntry(UUID sourceId, UUID userId, LocalDate periodStart, LocalDate periodEnd,
                       Money grossAmount, Money netAmount, LocalDate receivedOn,
                       UUID linkedTransactionId, IncomeOrigin origin) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd is before periodStart");
        }
        if (netAmount.isGreaterThan(grossAmount)) {
            throw new IllegalArgumentException("Net amount exceeds gross amount");
        }
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.grossAmount = grossAmount.amount();
        this.netAmount = netAmount.amount();
        this.currency = grossAmount.currency();
        this.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
        this.linkedTransactionId = linkedTransactionId;
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /** Replaces the editable fields; callers validate the combination first. */
    public void revise(LocalDate periodStart, LocalDate periodEnd, Money grossAmount, Money netAmount,
                       LocalDate receivedOn) {
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd is before periodStart");
        }
        if (netAmount.isGreaterThan(grossAmount)) {
            throw new IllegalArgumentException("Net amount exceeds gross amount");
        }
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.grossAmount = grossAmount.amount();
        this.netAmount = netAmount.amount();
        this.currency = grossAmount.currency();
        this.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
    }

    public void linkTransaction(UUID transactionId) {
        this.linkedTransactionId = transactionId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public Money getGrossAmount() {
        return Money.of(grossAmount, currency);
    }

    public Money getNetAmount() {
        return Money.of(netAmount, currency);
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public UUID getLinkedTransactionId() {
        return linkedTransactionId;
    }

    public IncomeOrigin getOrigin() {
        return origin;
    }
}
