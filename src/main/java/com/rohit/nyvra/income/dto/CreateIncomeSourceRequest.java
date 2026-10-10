package com.rohit.nyvra.income.dto;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating an income source.
 *
 * @param name           label, not blank, at most 80 characters
 * @param type           kind of income
 * @param cadence        expected frequency
 * @param expectedAmount expected amount in INR; required unless {@code cadence} is {@code IRREGULAR}
 */
public record CreateIncomeSourceRequest(
    @NotBlank @Size(max = 80) String name,
    @NotNull IncomeType type,
    @NotNull IncomeCadence cadence,
    @Valid MoneyDto expectedAmount) {
}
