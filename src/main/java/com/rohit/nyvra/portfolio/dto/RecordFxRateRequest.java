package com.rohit.nyvra.portfolio.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for setting a day's FX rate. Administrators only.
 *
 * @param currency   ISO 4217 code of the foreign currency; INR is rejected
 * @param rateDate   the day the rate applies to; defaults to today and must not be in the future
 * @param inrPerUnit rupees per one unit of the currency, a decimal string with at most 6 decimals, above zero
 */
public record RecordFxRateRequest(
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String currency,
    LocalDate rateDate,
    @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a decimal string with at most 6 decimals") String inrPerUnit) {
}
