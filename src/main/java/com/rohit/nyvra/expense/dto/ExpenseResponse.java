package com.rohit.nyvra.expense.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.ExpenseOrigin;
import com.rohit.nyvra.expense.Necessity;

/** {@code splits} is present only when the expense has been split; {@code transactionId} only for AA-derived ones. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseResponse(
    UUID id,
    LocalDate date,
    MoneyDto amount,
    CategoryRef category,
    CategoryRef subcategory,
    String merchant,
    Necessity necessity,
    ExpenseOrigin origin,
    boolean excludedFromHabits,
    UUID transactionId,
    List<ExpenseSplitResponse> splits) {
}
