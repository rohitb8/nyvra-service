package com.rohit.nyvra.common.money;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Money on the wire: a decimal string (never a JSON number) plus its currency. */
public record MoneyDto(
    @NotNull @Pattern(regexp = "^-?\\d{1,17}(\\.\\d{1,2})?$", message = "must be a decimal string with at most 2 decimals")
    String amount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code")
    String currency) {

    public static MoneyDto from(Money money) {
        return money == null ? null : new MoneyDto(money.amount().toPlainString(), money.currency());
    }

    public Money toMoney() {
        return Money.of(new BigDecimal(amount), currency);
    }
}
