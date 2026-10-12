package com.rohit.nyvra.portfolio.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for recording a manual price for an instrument.
 *
 * @param price price per unit as a decimal string with at most 6 decimals, not negative
 * @param asOf  the moment the price applies to; defaults to now and must not be in the future
 */
public record RecordQuoteRequest(
    @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a non-negative decimal string with at most 6 decimals") String price,
    Instant asOf) {
}
