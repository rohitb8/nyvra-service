package com.rohit.nyvra.portfolio.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.portfolio.AssetClass;
import com.rohit.nyvra.portfolio.Instrument;

/**
 * API view of an instrument. Absent optional fields are omitted from the JSON.
 *
 * @param id         instrument id
 * @param isin       ISIN, if the instrument has one
 * @param symbol     ticker or scheme code, if known
 * @param name       display name, if known
 * @param assetClass kind of investment
 * @param currency   ISO 4217 pricing currency
 * @param country    ISO 3166 alpha-2 country, if known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstrumentResponse(
    UUID id, String isin, String symbol, String name, AssetClass assetClass, String currency, String country) {

    /**
     * Maps an instrument to its response.
     *
     * @param i the instrument
     * @return the response
     */
    public static InstrumentResponse from(Instrument i) {
        return new InstrumentResponse(i.getId(), i.getIsin(), i.getSymbol(), i.getName(), i.getAssetClass(),
            i.getCurrency(), i.getCountry());
    }
}
