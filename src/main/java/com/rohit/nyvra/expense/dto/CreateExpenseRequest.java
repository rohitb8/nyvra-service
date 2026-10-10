package com.rohit.nyvra.expense.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code necessity} defaults to the category's default; {@code excludedFromHabits} to {@code false}. */
public record CreateExpenseRequest(
    @NotNull LocalDate date,
    @NotNull @Valid MoneyDto amount,
    @NotNull UUID categoryId,
    UUID subcategoryId,
    @Size(max = 120) String merchant,
    Necessity necessity,
    Boolean excludedFromHabits) {
}
