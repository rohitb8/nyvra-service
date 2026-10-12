package com.rohit.nyvra.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.AssetClass;
import com.rohit.nyvra.portfolio.PortfolioHolding;
import com.rohit.nyvra.portfolio.PortfolioValuation;
import com.rohit.nyvra.portfolio.PriceQuote;
import com.rohit.nyvra.portfolio.Instrument;
import com.rohit.nyvra.common.money.Money;

/**
 * API view of a holding with its derived valuation. Fields that cannot be derived (no cost, or no price
 * yet) are omitted from the JSON rather than sent as zero.
 *
 * @param id                holding id
 * @param instrument        the held instrument
 * @param assetClass        asset class
 * @param quantity          units held, decimal string, scale 6
 * @param avgCost           average cost per unit, decimal string, scale 6
 * @param currency          ISO 4217 code of cost and valuation
 * @param investedValue     quantity times average cost
 * @param latestPrice       most recent price per unit, decimal string, scale 6
 * @param priceAsOf         when the latest price applies
 * @param currentValue      quantity times latest price
 * @param unrealisedGain    current value minus invested value
 * @param unrealisedGainPct unrealised gain as percent points of the invested value
 * @param source            where the position came from
 * @param openedAt          when the position was opened
 * @param closedAt          when the position was closed; omitted while open
 * @param createdAt         creation time (UTC)
 * @param updatedAt         last modification time (UTC)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldingResponse(
    UUID id,
    InstrumentResponse instrument,
    AssetClass assetClass,
    String quantity,
    String avgCost,
    String currency,
    MoneyDto investedValue,
    String latestPrice,
    Instant priceAsOf,
    MoneyDto currentValue,
    MoneyDto unrealisedGain,
    BigDecimal unrealisedGainPct,
    RecordSource source,
    Instant openedAt,
    Instant closedAt,
    Instant createdAt,
    Instant updatedAt) {

    /**
     * Maps a holding and its context to a response, deriving the valuation fields.
     *
     * @param h     the holding
     * @param i     the held instrument
     * @param quote the instrument's latest quote, or {@code null} if it has none
     * @return the response
     */
    public static HoldingResponse from(PortfolioHolding h, Instrument i, PriceQuote quote) {
        Money invested = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), h.getCurrency());
        Money current = quote == null ? null
            : PortfolioValuation.current(h.getQuantity(), quote.getPrice(), h.getCurrency());
        Money gain = PortfolioValuation.gain(invested, current);
        return new HoldingResponse(h.getId(), InstrumentResponse.from(i), h.getAssetClass(),
            h.getQuantity().setScale(6).toPlainString(),
            h.getAvgCost() == null ? null : h.getAvgCost().setScale(6).toPlainString(),
            h.getCurrency(), MoneyDto.from(invested),
            quote == null ? null : quote.getPrice().setScale(6).toPlainString(),
            quote == null ? null : quote.getAsOf(),
            MoneyDto.from(current), MoneyDto.from(gain), PortfolioValuation.gainPercent(gain, invested),
            h.getSource(), h.getOpenedAt(), h.getClosedAt(), h.getCreatedAt(), h.getUpdatedAt());
    }
}
