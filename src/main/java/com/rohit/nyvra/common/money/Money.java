package com.rohit.nyvra.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money in one currency, always at scale 2 — the one place amount scale and
 * currency-mismatch rules live ({@code database/decisions.md} §4).
 *
 * <p>Entities persist money as {@code NUMERIC(19,2)} column(s) plus one {@code CHAR(3)} currency column
 * per row, and expose those columns only as {@code Money}. Prices/NAV/FX/quantities (scale 6) are not
 * {@code Money}.
 */
public record Money(BigDecimal amount, String currency) {

    public static final String INR = "INR";
    public static final int SCALE = 2;

    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!CURRENCY_CODE.matcher(currency).matches()) {
            throw new IllegalArgumentException("Not an ISO-4217 currency code: " + currency);
        }
        // Inputs with more than 2 decimals are rejected rather than silently rounded — any rounding is a
        // FINANCIAL_RULES decision the caller has to make explicitly.
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money inr(String amount) {
        return of(amount, INR);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
