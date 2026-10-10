package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseSplitResponse(
    UUID id,
    MoneyDto amount,
    CategoryRef category,
    CategoryRef subcategory,
    Necessity necessity,
    String note) {
}
