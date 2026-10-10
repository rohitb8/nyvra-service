package com.rohit.nyvra.portfolio.dto;

import com.rohit.nyvra.portfolio.AssetClass;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding an instrument to the shared catalogue.
 *
 * @param isin       12-character ISIN; optional, but at least one of {@code isin} and {@code symbol} is required
 * @param symbol     ticker or scheme code, at most 40 characters
 * @param name       display name, at most 120 characters
 * @param assetClass kind of investment
 * @param currency   ISO 4217 pricing currency; defaults to INR when omitted
 * @param country    ISO 3166 alpha-2 country; optional
 */
public record CreateInstrumentRequest(
    @Pattern(regexp = "^[A-Z]{2}[A-Z0-9]{9}[0-9]$", message = "must be a 12-character ISIN") String isin,
    @Size(min = 1, max = 40) String symbol,
    @Size(min = 1, max = 120) String name,
    @NotNull AssetClass assetClass,
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String currency,
    @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO-3166 alpha-2 code") String country) {
}
