package com.rohit.nyvra.common.money;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Money on the wire: a decimal string (never a JSON number) plus its currency.
 *
 *
 * @param amount decimal string with up to 17 integer digits and at most 2 decimals; may be negative
 * @param currency upper-case ISO-4217 code
 */
public record MoneyDto(
    @NotNull @Pattern(regexp = "^-?\\d{1,17}(\\.\\d{1,2})?$", message = "must be a decimal string with at most 2 decimals")
    String amount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code")
    String currency) {

    /**
     * Converts a domain value to its wire form.
     *
     * @param money the value, may be {@code null}
     * @return the DTO, or {@code null} when {@code money} is {@code null}
     */
    public static MoneyDto from(Money money) {
        return money == null ? null : new MoneyDto(money.amount().toPlainString(), money.currency());
    }

    /**
     * Converts the wire form to a domain value.
     *
     * @return the money value
     * @throws NumberFormatException if the amount is not a decimal
     * @throws ArithmeticException if the amount has more than 2 decimals
     */
    public Money toMoney() {
        return Money.of(new BigDecimal(amount), currency);
    }
}
