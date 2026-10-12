package com.rohit.nyvra.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Annualised return (XIRR) of the caller's open INR holdings. Each holding is treated as one outflow of its
 * cost on its opening date and one inflow of its current value now, since only the opening position is recorded.
 * Holdings without a cost or a price cannot be included.
 *
 * @param xirrPct       portfolio XIRR in percent points; omitted when it is undefined (nothing included, or
 *                      every holding opened just now)
 * @param includedCount holdings that went into the figure
 * @param excludedCount open holdings left out for lack of a cost, a price or an INR currency
 * @param asOf          the moment the current values are measured at
 * @param holdings      each included holding's own XIRR
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReturnsResponse(
    BigDecimal xirrPct,
    int includedCount,
    int excludedCount,
    Instant asOf,
    List<HoldingReturn> holdings) {

    /**
     * One holding's annualised return.
     *
     * @param holdingId  the holding
     * @param instrumentId the held instrument
     * @param xirrPct    XIRR in percent points; omitted when undefined, for example a holding opened just now
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HoldingReturn(UUID holdingId, UUID instrumentId, BigDecimal xirrPct) {
    }
}
