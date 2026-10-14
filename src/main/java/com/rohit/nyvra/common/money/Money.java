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
 *
 * @param amount the amount, normalised to scale 2; never {@code null}
 * @param currency upper-case ISO-4217 code, for example {@code INR}; never {@code null}
 */
public record Money(BigDecimal amount, String currency) {

    /** Default currency code (Indian rupee). */
    public static final String INR = "INR";
    /** Decimal scale of every amount. */
    public static final int SCALE = 2;

    /** Three upper-case letters, the shape of an ISO-4217 code. */
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    /**
     * Validates and normalises the components.
     *
     * @throws NullPointerException if amount or currency is {@code null}
     * @throws IllegalArgumentException if the currency is not three upper-case letters
     * @throws ArithmeticException if the amount has more than 2 decimals (no silent rounding)
     */
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

    /**
     * Creates money from a decimal amount.
     *
     * @param amount the amount, at most 2 decimals
     * @param currency ISO-4217 code
     * @return the money value
     */
    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    /**
     * Creates money from a decimal string.
     *
     * @param amount decimal text with at most 2 decimals, for example {@code "1234.50"}
     * @param currency ISO-4217 code
     * @return the money value
     * @throws NumberFormatException if the text is not a decimal
     */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    /**
     * Creates an INR amount from a decimal string.
     *
     * @param amount decimal text with at most 2 decimals
     * @return the amount in {@code INR}
     */
    public static Money inr(String amount) {
        return of(amount, INR);
    }

    /**
     * Creates a zero amount.
     *
     * @param currency ISO-4217 code
     * @return zero in that currency
     */
    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Adds another amount.
     *
     * @param other amount in the same currency
     * @return the sum
     * @throws IllegalArgumentException if the currencies differ
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Subtracts another amount.
     *
     * @param other amount in the same currency
     * @return the difference
     * @throws IllegalArgumentException if the currencies differ
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Returns the same amount with the opposite sign. */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Whether the amount is strictly below zero. */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Whether this amount is strictly greater than another.
     *
     * @param other amount in the same currency
     * @return {@code true} if this is greater
     * @throws IllegalArgumentException if the currencies differ
     */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    /**
     * Guards currency-safe arithmetic.
     *
     * @param other the amount to compare with
     * @throws IllegalArgumentException if the currencies differ
     */
    public void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
