package com.rohit.nyvra.income.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for recording an income entry manually.
 *
 * @param sourceId    the caller's income source the payment belongs to
 * @param periodStart first day the payment covers, inclusive
 * @param periodEnd   last day the payment covers, inclusive; not before {@code periodStart}
 * @param grossAmount amount before deductions, positive, in the source's currency
 * @param netAmount   amount received, positive and not above {@code grossAmount}
 * @param receivedOn  date the money arrived
 */
public record CreateIncomeEntryRequest(
    @NotNull UUID sourceId,
    @NotNull LocalDate periodStart,
    @NotNull LocalDate periodEnd,
    @NotNull @Valid MoneyDto grossAmount,
    @NotNull @Valid MoneyDto netAmount,
    @NotNull LocalDate receivedOn) {
}
