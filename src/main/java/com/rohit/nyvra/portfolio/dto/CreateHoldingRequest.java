package com.rohit.nyvra.portfolio.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for recording a holding manually.
 *
 * @param instrumentId the held instrument
 * @param quantity     units held as a decimal string with at most 6 decimals; must be above zero
 * @param avgCost      average cost per unit as a decimal string with at most 6 decimals; optional
 * @param openedAt     when the position was opened; defaults to now and must not be in the future
 */
public record CreateHoldingRequest(
    @NotNull UUID instrumentId,
    @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a non-negative decimal string with at most 6 decimals") String quantity,
    @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a non-negative decimal string with at most 6 decimals") String avgCost,
    Instant openedAt) {
}
