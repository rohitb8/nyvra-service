package com.rohit.nyvra.income.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;

/** Omitted fields are unchanged. */
public record UpdateIncomeEntryRequest(
    LocalDate periodStart,
    LocalDate periodEnd,
    @Valid MoneyDto grossAmount,
    @Valid MoneyDto netAmount,
    LocalDate receivedOn) {
}
