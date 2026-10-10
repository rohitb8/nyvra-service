package com.rohit.nyvra.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.rohit.nyvra.common.money.MoneyDto;

/**
 * One month's spending breakdown. Spending totals leave out {@code SAVINGS_TRANSFER} and
 * excluded-from-habits expenses; savings transfers are reported on their own. Percentages are percent points of
 * {@code totalSpend}, rounded to one decimal, so the three need not add up to exactly 100.
 *
 * @param month            the month, {@code yyyy-MM}
 * @param totalSpend       total spending
 * @param essentialPct     share that was essential
 * @param discretionaryPct share that was discretionary
 * @param debtRepaymentPct share that was debt repayment
 * @param savingsTransfers money moved to savings, tracked apart from spending
 * @param byCategory       top-level categories, largest amount first
 * @param computedAt       when the breakdown was computed
 */
public record SpendingHabitsResponse(
    String month,
    MoneyDto totalSpend,
    BigDecimal essentialPct,
    BigDecimal discretionaryPct,
    BigDecimal debtRepaymentPct,
    MoneyDto savingsTransfers,
    List<CategorySpend> byCategory,
    Instant computedAt) {

    /**
     * Spend in one top-level category.
     *
     * @param category the category
     * @param amount   what was spent in it
     * @param pct      its share of {@code totalSpend}, percent points to one decimal
     */
    public record CategorySpend(CategoryRef category, MoneyDto amount, BigDecimal pct) {
    }
}
