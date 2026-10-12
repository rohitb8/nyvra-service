package com.rohit.nyvra.ingestion;

import java.math.BigDecimal;

/**
 * One security or fund holding in normalised form.
 *
 * @param isin  ISIN of the security or scheme
 * @param name  the provider's name for it
 * @param units quantity held, positive, scale 6
 * @param price latest NAV or traded price per unit, scale 6, or {@code null} if not reported
 * @param value {@code units × price} rounded to scale 2, or {@code null} when there is no price
 */
public record IngestedHolding(String isin, String name, BigDecimal units, BigDecimal price, BigDecimal value) {
}
