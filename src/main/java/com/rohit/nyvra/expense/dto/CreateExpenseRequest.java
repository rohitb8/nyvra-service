package com.rohit.nyvra.expense.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Records a manual expense. {@code necessity} defaults to the category's default; {@code excludedFromHabits} to
 * {@code false}.
 *
 * @param date               the expense date, required
 * @param amount             the amount, required; positive INR
 * @param categoryId         the top-level category, required
 * @param subcategoryId      an optional child of {@code categoryId}
 * @param merchant           the merchant, at most 120 characters; optional
 * @param necessity          the necessity; optional, defaults from the (sub)category
 * @param excludedFromHabits whether to leave it out of spending habits; optional, defaults to false
 */
public record CreateExpenseRequest(
    @NotNull LocalDate date,
    @NotNull @Valid MoneyDto amount,
    @NotNull UUID categoryId,
    UUID subcategoryId,
    @Size(max = 120) String merchant,
    Necessity necessity,
    Boolean excludedFromHabits) {
}
