package com.rohit.nyvra.expense.dto;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SplitExpenseRequest(@NotNull @Size(min = 2, max = 20) List<@NotNull @Valid Part> parts) {

    public record Part(
        @NotNull @Valid MoneyDto amount,
        @NotNull UUID categoryId,
        UUID subcategoryId,
        Necessity necessity,
        @Size(max = 120) String note) {
    }
}
