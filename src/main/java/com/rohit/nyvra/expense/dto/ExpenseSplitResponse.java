package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;

/**
 * One part of a split expense, as returned under its parent. Null components are omitted from the JSON.
 *
 * @param id          the part's expense id
 * @param amount      the part's amount, in the parent's currency
 * @param category    the part's category
 * @param subcategory the part's subcategory, or null
 * @param necessity   the part's necessity
 * @param note        a free-text note, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpenseSplitResponse(
    UUID id,
    MoneyDto amount,
    CategoryRef category,
    CategoryRef subcategory,
    Necessity necessity,
    String note) {
}
