package com.rohit.nyvra.portfolio.dto;

/**
 * Outcome of pulling prices and FX rates from the price feed.
 *
 * @param pricesStored   new instrument quotes saved
 * @param fxRatesStored  FX rates saved or refreshed
 */
public record PriceFeedSyncResponse(int pricesStored, int fxRatesStored) {
}
