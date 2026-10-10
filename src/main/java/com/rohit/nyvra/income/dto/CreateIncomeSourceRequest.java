package com.rohit.nyvra.income.dto;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncomeSourceRequest(
    @NotBlank @Size(max = 80) String name,
    @NotNull IncomeType type,
    @NotNull IncomeCadence cadence,
    @Valid MoneyDto expectedAmount) {
}
