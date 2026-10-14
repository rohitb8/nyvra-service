package com.rohit.nyvra.expense.dto;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Splits an expense into parts; the part amounts must sum exactly to the expense.
 *
 * @param parts 2 to 20 parts
 */
public record SplitExpenseRequest(@NotNull @Size(min = 2, max = 20) List<@NotNull @Valid Part> parts) {

    /**
     * One part of a split.
     *
     * @param amount        the part's positive amount, in the expense's currency
     * @param categoryId    the part's top-level category
     * @param subcategoryId an optional child of {@code categoryId}
     * @param necessity     the necessity; optional, defaults from the (sub)category
     * @param note          a free-text note, at most 120 characters; optional
     */
    public record Part(
        @NotNull @Valid MoneyDto amount,
        @NotNull UUID categoryId,
        UUID subcategoryId,
        Necessity necessity,
        @Size(max = 120) String note) {
    }
}
