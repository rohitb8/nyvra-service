package com.rohit.nyvra.income.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateIncomeEntryRequest(
    @NotNull UUID sourceId,
    @NotNull LocalDate periodStart,
    @NotNull LocalDate periodEnd,
    @NotNull @Valid MoneyDto grossAmount,
    @NotNull @Valid MoneyDto netAmount,
    @NotNull LocalDate receivedOn) {
}
