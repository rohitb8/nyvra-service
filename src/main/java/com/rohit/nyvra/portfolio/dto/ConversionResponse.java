package com.rohit.nyvra.portfolio.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;

/**
 * Result of converting an amount between currencies through INR.
 *
 * @param from     the amount asked to convert
 * @param to       the converted amount, rounded to 2 decimals
 * @param date     the day the conversion was requested for
 * @param rateDate the day of the older rate used; omitted when no rate was needed (same currency)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversionResponse(MoneyDto from, MoneyDto to, LocalDate date, LocalDate rateDate) {
}
