package com.rohit.nyvra.income.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;

/**
 * Request body for a partial update of an income entry; omitted fields are unchanged.
 *
 * @param periodStart new first covered day
 * @param periodEnd   new last covered day
 * @param grossAmount new gross amount, positive
 * @param netAmount   new net amount, positive
 * @param receivedOn  new received-on date
 */
public record UpdateIncomeEntryRequest(
    LocalDate periodStart,
    LocalDate periodEnd,
    @Valid MoneyDto grossAmount,
    @Valid MoneyDto netAmount,
    LocalDate receivedOn) {
}
