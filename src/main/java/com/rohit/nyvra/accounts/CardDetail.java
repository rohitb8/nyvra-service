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

    /** Exactly four digits. */
    private static final Pattern LAST4 = Pattern.compile("^[0-9]{4}$");

    /** Id of the owning credit-card account; immutable. */
    @Column(name = "financial_account_id", nullable = false, updatable = false)
    private UUID financialAccountId;

    /** Last four digits of the card number. */
    @Column(name = "last4", nullable = false, length = 4)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String last4;

    /** Card network. */
    @Enumerated(EnumType.STRING)
    @Column(name = "network", nullable = false)
    private CardNetwork network;

    /** User-facing card name; nullable. */
    @Column(name = "label")
    private String label;

    /** Credit limit in {@link #currency}, scale 2; nullable. */
    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /** ISO 4217 code of the card, three characters. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Constructor required by JPA. */
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

    /**
     * Sets or clears the credit limit.
     *
     * @param creditLimit new limit, or null to clear
     * @throws IllegalArgumentException if its currency differs from the card's
     */
    public void changeCreditLimit(Money creditLimit) {
        if (creditLimit != null && !creditLimit.currency().equals(currency)) {
            throw new IllegalArgumentException("Credit limit currency must be " + currency);
        }
        this.creditLimit = creditLimit == null ? null : creditLimit.amount();
    }

    /**
     * Changes the card label.
     *
     * @param label new label, nullable
     */
    public void rename(String label) {
        this.label = label;
    }

    /** @return owning account id */
    public UUID getFinancialAccountId() {
        return financialAccountId;
    }

    /** @return last four digits */
    public String getLast4() {
        return last4;
    }

    /** @return card network */
    public CardNetwork getNetwork() {
        return network;
    }

    /** @return card label, or null */
    public String getLabel() {
        return label;
    }

    /** @return ISO 4217 currency code */
    public String getCurrency() {
        return currency;
    }

    /** @return credit limit, or null if none */
    public Money getCreditLimit() {
        return creditLimit == null ? null : Money.of(creditLimit, currency);
    }
}
