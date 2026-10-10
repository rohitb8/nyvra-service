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

    /** Id of the owning {@link IncomeSource}; fixed at creation. */
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** First day covered by this payment, inclusive. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** Last day covered by this payment, inclusive; never before {@code periodStart}. */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** Amount before deductions, scale 2, in {@code currency}. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    /** Amount actually received, scale 2, in {@code currency}; never above the gross amount. */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    /** ISO 4217 code shared by the gross and net amounts. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Accounting date on which the money arrived. */
    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    /** Optional id of the matching Accounts transaction; a plain reference without a foreign key. */
    @Column(name = "linked_transaction_id")
    private UUID linkedTransactionId;

    /** How the entry was created; detected entries are read-only through the API. */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private IncomeOrigin origin;

    /** Required by JPA; not for application use. */
    protected IncomeEntry() {
        // for JPA
    }

    /**
     * Creates an entry, enforcing the invariants the database also checks.
     *
     * @param sourceId            owning source, required
     * @param userId              owning user, required
     * @param periodStart         first covered day
     * @param periodEnd           last covered day, not before {@code periodStart}
     * @param grossAmount         amount before deductions; its currency becomes the entry currency
     * @param netAmount           amount received, not above {@code grossAmount}
     * @param receivedOn          date the money arrived, required
     * @param linkedTransactionId optional Accounts transaction reference
     * @param origin              how the entry was created, required
     * @throws IllegalArgumentException if the period ends before it starts or net exceeds gross
     */
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

    /**
     * Replaces the editable fields; callers validate the combination first (overlap, currency), this method
     * only guards the invariants the entity itself owns.
     *
     * @param periodStart new first covered day
     * @param periodEnd   new last covered day
     * @param grossAmount new gross amount; its currency becomes the entry currency
     * @param netAmount   new net amount
     * @param receivedOn  new received-on date, required
     * @throws IllegalArgumentException if the period ends before it starts or net exceeds gross
     */
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

    /**
     * Links this entry to an Accounts transaction, or clears the link when {@code null}.
     *
     * @param transactionId the transaction id, or {@code null}
     */
    public void linkTransaction(UUID transactionId) {
        this.linkedTransactionId = transactionId;
    }

    /** @return the owning source id */
    public UUID getSourceId() {
        return sourceId;
    }

    /** @return the owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the first covered day, inclusive */
    public LocalDate getPeriodStart() {
        return periodStart;
    }

    /** @return the last covered day, inclusive */
    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    /** @return the gross amount as {@link Money} in the entry currency */
    public Money getGrossAmount() {
        return Money.of(grossAmount, currency);
    }

    /** @return the net amount as {@link Money} in the entry currency */
    public Money getNetAmount() {
        return Money.of(netAmount, currency);
    }

    /** @return the date the money arrived */
    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    /** @return the linked Accounts transaction id, or {@code null} */
    public UUID getLinkedTransactionId() {
        return linkedTransactionId;
    }

    /** @return how the entry was created */
    public IncomeOrigin getOrigin() {
        return origin;
    }
}
