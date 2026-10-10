package com.rohit.nyvra.income;

import java.math.BigDecimal;
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
 * A stream of income (an employer, a rental, interest…). Every cadence except {@code IRREGULAR} must
 * carry an expected amount — mirrored by {@code chk_income_source_expected_amount}.
 */
@Entity
@Table(name = "income_source")
public class IncomeSource extends AbstractEntity {

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** User-facing label, for example the employer's name. */
    @Column(name = "name", nullable = false)
    private String name;

    /** Kind of income, such as {@code SALARY} or {@code RENTAL}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private IncomeType type;

    /** How often income is expected; {@code IRREGULAR} means no expected amount. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false)
    private IncomeCadence cadence;

    /** Expected amount per cadence period, scale 2; {@code null} only for {@code IRREGULAR}. */
    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    /** ISO 4217 code of the source and of every entry recorded against it; fixed at creation. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Whether the source is in use; deactivated sources keep their history. */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** Required by JPA; not for application use. */
    protected IncomeSource() {
        // for JPA
    }

    /**
     * Creates an active source.
     *
     * @param userId         owning user, required
     * @param name           label, required
     * @param type           kind of income, required
     * @param currency       ISO code, required
     * @param cadence        expected frequency, required
     * @param expectedAmount expected amount; nullable only when {@code cadence} is {@code IRREGULAR}
     * @throws IllegalArgumentException if the amount is missing for a non-irregular cadence or its currency differs
     */
    public IncomeSource(UUID userId, String name, IncomeType type, String currency,
                        IncomeCadence cadence, Money expectedAmount) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.active = true;
        changeExpectation(cadence, expectedAmount);
    }

    /**
     * Sets the cadence and expected amount together so the pair is always consistent.
     *
     * @param cadence        new cadence, required
     * @param expectedAmount new amount, or {@code null} to clear it (only for {@code IRREGULAR})
      * @throws IllegalArgumentException if a non-irregular cadence has no amount, or the currency differs from the
      *     source
     */
    public void changeExpectation(IncomeCadence cadence, Money expectedAmount) {
        Objects.requireNonNull(cadence, "cadence");
        if (cadence != IncomeCadence.IRREGULAR && expectedAmount == null) {
            throw new IllegalArgumentException("A " + cadence + " income source needs an expected amount");
        }
        if (expectedAmount != null && !expectedAmount.currency().equals(currency)) {
            throw new IllegalArgumentException("Expected amount currency must be " + currency);
        }
        this.cadence = cadence;
        this.expectedAmount = expectedAmount == null ? null : expectedAmount.amount();
    }

    /**
     * Changes the label.
     *
     * @param name the new label, required
     */
    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Marks the source inactive; used instead of deleting a source that has entries. */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Activates or deactivates the source.
     *
     * @param active the new state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Changes the kind of income.
     *
     * @param type the new type, required
     */
    public void changeType(IncomeType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** @return the owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the label */
    public String getName() {
        return name;
    }

    /** @return the kind of income */
    public IncomeType getType() {
        return type;
    }

    /** @return the expected frequency */
    public IncomeCadence getCadence() {
        return cadence;
    }

    /** @return the expected amount in the source currency, or {@code null} when none is set */
    public Money getExpectedAmount() {
        return expectedAmount == null ? null : Money.of(expectedAmount, currency);
    }

    /** @return the ISO 4217 currency code */
    public String getCurrency() {
        return currency;
    }

    /** @return {@code true} while the source is in use */
    public boolean isActive() {
        return active;
    }
}
