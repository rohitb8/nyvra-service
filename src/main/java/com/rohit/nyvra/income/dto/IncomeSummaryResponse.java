package com.rohit.nyvra.income.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeType;

/**
 * Rolling income summary for the Income screen, computed on the server so the client never derives it.
 *
 * @param windowMonths       trailing window length, 3, 6 or 12
 * @param from               first day of the window, inclusive
 * @param to                 last day of the window (today in Asia/Kolkata), inclusive
 * @param monthlyAverageNet  net income in the window divided by {@code windowMonths}
 * @param monthlyAverageGross gross income in the window divided by {@code windowMonths}
 * @param totalNet           net income received in the window
 * @param byType             net totals per income type, largest first; types with no income are omitted
 * @param computedAt         when the summary was computed
 */
public record IncomeSummaryResponse(
    int windowMonths,
    LocalDate from,
    LocalDate to,
    MoneyDto monthlyAverageNet,
    MoneyDto monthlyAverageGross,
    MoneyDto totalNet,
    List<TypeTotal> byType,
    Instant computedAt) {

    /**
     * Net income of one type in the window.
     *
     * @param type     the income type
     * @param totalNet net income of that type
     * @param sharePct share of the window's net income in percent points, one decimal
     */
    public record TypeTotal(IncomeType type, MoneyDto totalNet, double sharePct) {
    }
}
