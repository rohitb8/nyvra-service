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

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private IncomeType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false)
    private IncomeCadence cadence;

    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected IncomeSource() {
        // for JPA
    }

    /** @param expectedAmount nullable only when {@code cadence} is {@code IRREGULAR} */
    public IncomeSource(UUID userId, String name, IncomeType type, String currency,
                        IncomeCadence cadence, Money expectedAmount) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.active = true;
        changeExpectation(cadence, expectedAmount);
    }

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

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public IncomeType getType() {
        return type;
    }

    public IncomeCadence getCadence() {
        return cadence;
    }

    public Money getExpectedAmount() {
        return expectedAmount == null ? null : Money.of(expectedAmount, currency);
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }
}
