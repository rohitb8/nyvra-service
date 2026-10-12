package com.rohit.nyvra.portfolio.feed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.rohit.nyvra.portfolio.Instrument;

/**
 * Source of market prices and FX rates that an administrator can pull on demand. The only implementation
 * today is {@link StubPriceFeed}; a real provider replaces it by registering its own bean.
 */
public interface PriceFeed {

    /**
     * One price from the feed.
     *
     * @param instrumentId the instrument the price is for
     * @param asOf         when the price applies
     * @param price        price per unit in the instrument's currency
     */
    record FeedQuote(java.util.UUID instrumentId, Instant asOf, BigDecimal price) {
    }

    /**
     * One FX rate from the feed.
     *
     * @param base     the currency being priced
     * @param quote    the currency the rate is expressed in
     * @param asOf     when the rate applies
     * @param rate     units of {@code quote} per one unit of {@code base}
     */
    record FeedFxRate(String base, String quote, Instant asOf, BigDecimal rate) {
    }

    /**
     * Fetches the latest price of each instrument the feed knows about.
     *
     * @param instruments the instruments to price
     * @return the prices found; instruments the feed does not cover are simply absent
     */
    List<FeedQuote> fetchPrices(Collection<Instrument> instruments);

    /**
     * Fetches the latest rate of each foreign currency against INR.
     *
     * @param currencies ISO 4217 codes to fetch, never INR
     * @return the rates found; currencies the feed does not cover are simply absent
     */
    List<FeedFxRate> fetchFxRates(Collection<String> currencies);
}
