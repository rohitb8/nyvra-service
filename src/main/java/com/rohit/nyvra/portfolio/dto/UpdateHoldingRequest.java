package com.rohit.nyvra.portfolio.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Request body for a partial update of a holding. Omitted fields are unchanged. A quantity of zero closes
 * the position; a positive quantity on a closed position reopens it.
 *
 * @param quantity new units held as a decimal string with at most 6 decimals; optional
 * @param avgCost  new average cost per unit as a decimal string with at most 6 decimals; optional
 */
public record UpdateHoldingRequest(
    @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a non-negative decimal string with at most 6 decimals") String quantity,
    @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,6})?$",
        message = "must be a non-negative decimal string with at most 6 decimals") String avgCost) {
}
