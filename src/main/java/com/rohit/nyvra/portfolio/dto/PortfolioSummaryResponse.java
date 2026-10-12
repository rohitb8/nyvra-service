package com.rohit.nyvra.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.portfolio.AssetClass;

/**
 * Totals and asset-class allocation across the caller's open INR holdings. The client displays these as
 * returned and does no arithmetic of its own.
 *
 * <p>{@code currentValue} sums only holdings that have a price, and {@code unrealisedGain} sums only
 * holdings that have both a price and a cost, so the counts below say how complete each figure is.
 *
 * @param currency             currency of every amount (INR)
 * @param investedValue        total cost basis of holdings whose cost is known
 * @param currentValue         total market value of holdings that have a price
 * @param unrealisedGain       gain over holdings that have both a price and a cost; omitted when none do
 * @param unrealisedGainPct    that gain as percent points of those holdings' cost; omitted when undefined
 * @param holdingCount         open INR holdings counted
 * @param unpricedHoldingCount open holdings with no price yet
 * @param excludedHoldingCount open holdings in other currencies, left out until FX is supported
 * @param pricesAsOf           the latest quote time used; omitted when no holding is priced
 * @param allocation           value per asset class, largest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortfolioSummaryResponse(
    String currency,
    MoneyDto investedValue,
    MoneyDto currentValue,
    MoneyDto unrealisedGain,
    BigDecimal unrealisedGainPct,
    int holdingCount,
    int unpricedHoldingCount,
    int excludedHoldingCount,
    Instant pricesAsOf,
    List<AllocationSlice> allocation) {

    /**
     * One asset class's share of the portfolio. A holding contributes its current value, or its cost basis
     * when it has no price; holdings with neither are not counted.
     *
     * @param assetClass   the asset class
     * @param value        value held in the class
     * @param percent      share of the total allocated value, in percent points
     * @param holdingCount holdings in the class that contribute
     */
    public record AllocationSlice(AssetClass assetClass, MoneyDto value, BigDecimal percent, int holdingCount) {
    }
}
