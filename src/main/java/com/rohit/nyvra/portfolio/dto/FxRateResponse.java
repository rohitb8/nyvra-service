package com.rohit.nyvra.portfolio.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.FxRate;

/**
 * API view of an FX rate.
 *
 * @param currency   ISO 4217 code of the foreign currency
 * @param rateDate   the day the rate applies to
 * @param inrPerUnit rupees per one unit of the currency, as a decimal string with 6 decimals
 * @param source     where the rate came from
 */
public record FxRateResponse(String currency, LocalDate rateDate, String inrPerUnit, RecordSource source) {

    /**
     * Maps a rate to its response.
     *
     * @param rate the rate
     * @return the response
     */
    public static FxRateResponse from(FxRate rate) {
        return new FxRateResponse(rate.getCurrency(), rate.getRateDate(),
            rate.getInrPerUnit().setScale(6).toPlainString(), rate.getSource());
    }
}
