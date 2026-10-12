package com.rohit.nyvra.portfolio;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Composite primary key of {@link FxRate}.
 *
 * @param currency ISO 4217 code of the foreign currency
 * @param rateDate the day the rate applies to
 */
public record FxRateId(String currency, LocalDate rateDate) implements Serializable {
}
