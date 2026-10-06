package com.rohit.nyvra.accounts;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

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
 * Display details of a card on a {@code CREDIT_CARD} account: last 4 digits, network, label. There is
 * deliberately no PAN, CVV or expiry field — those are never collected (PROJECT_OVERVIEW §4.1).
 */
@Entity
@Table(name = "card_detail")
public class CardDetail extends AbstractEntity {

    private static final Pattern LAST4 = Pattern.compile("^[0-9]{4}$");

    @Column(name = "financial_account_id", nullable = false, updatable = false)
    private UUID financialAccountId;

    @Column(name = "last4", nullable = false, length = 4)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String last4;

    @Enumerated(EnumType.STRING)
    @Column(name = "network", nullable = false)
    private CardNetwork network;

    @Column(name = "label")
    private String label;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    protected CardDetail() {
        // for JPA
    }

    /** @param creditLimit nullable; {@code currency} is the card's currency either way */
    public CardDetail(UUID financialAccountId, String last4, CardNetwork network, String label,
                      String currency, Money creditLimit) {
        if (last4 == null || !LAST4.matcher(last4).matches()) {
            throw new IllegalArgumentException("last4 must be exactly 4 digits");
        }
        this.financialAccountId = Objects.requireNonNull(financialAccountId, "financialAccountId");
        this.last4 = last4;
        this.network = Objects.requireNonNull(network, "network");
        this.label = label;
        this.currency = Objects.requireNonNull(currency, "currency");
        changeCreditLimit(creditLimit);
    }

    public void changeCreditLimit(Money creditLimit) {
        if (creditLimit != null && !creditLimit.currency().equals(currency)) {
            throw new IllegalArgumentException("Credit limit currency must be " + currency);
        }
        this.creditLimit = creditLimit == null ? null : creditLimit.amount();
    }

    public void rename(String label) {
        this.label = label;
    }

    public UUID getFinancialAccountId() {
        return financialAccountId;
    }

    public String getLast4() {
        return last4;
    }

    public CardNetwork getNetwork() {
        return network;
    }

    public String getLabel() {
        return label;
    }

    public String getCurrency() {
        return currency;
    }

    public Money getCreditLimit() {
        return creditLimit == null ? null : Money.of(creditLimit, currency);
    }
}
