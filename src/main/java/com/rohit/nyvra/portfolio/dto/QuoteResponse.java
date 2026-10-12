package com.rohit.nyvra.portfolio.dto;

import java.time.Instant;
import java.util.UUID;

import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.PriceQuote;

/**
 * API view of a price quote.
 *
 * @param instrumentId the priced instrument
 * @param asOf         the moment the price applies to (UTC)
 * @param price        price per unit as a decimal string, scale 6
 * @param source       where the price came from
 */
public record QuoteResponse(UUID instrumentId, Instant asOf, String price, RecordSource source) {

    /**
     * Maps a quote to its response.
     *
     * @param q the quote
     * @return the response
     */
    public static QuoteResponse from(PriceQuote q) {
        return new QuoteResponse(q.getInstrumentId(), q.getAsOf(), q.getPrice().setScale(6).toPlainString(), q.getSource());
    }
}
