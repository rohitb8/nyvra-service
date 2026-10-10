package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.rohit.nyvra.common.money.Money;

/**
 * Pure valuation arithmetic for a single holding, kept free of persistence so it can be unit tested on its
 * own. Quantities, costs and prices are scale 6; every derived amount is rounded {@code HALF_UP} to scale 2
 * and percentages to two decimals, at full precision until that final step.
 */
public final class PortfolioValuation {

    /** Scale of derived percentages. */
    private static final int PERCENT_SCALE = 2;

    /** One hundred, for converting a ratio to percent points. */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Not instantiable. */
    private PortfolioValuation() {
    }

    /**
     * Cost basis of a position: quantity times average cost.
     *
     * @param quantity units held
     * @param avgCost  average cost per unit, or {@code null} when unknown
     * @param currency ISO 4217 code of the cost
     * @return the invested amount, or {@code null} when the cost is unknown
     */
    public static Money invested(BigDecimal quantity, BigDecimal avgCost, String currency) {
        return avgCost == null ? null : Money.of(round(quantity.multiply(avgCost)), currency);
    }

    /**
     * Market value of a position: quantity times the latest price.
     *
     * @param quantity units held
     * @param price    latest price per unit, or {@code null} when no quote exists
     * @param currency ISO 4217 code of the price
     * @return the current value, or {@code null} when there is no price
     */
    public static Money current(BigDecimal quantity, BigDecimal price, String currency) {
        return price == null ? null : Money.of(round(quantity.multiply(price)), currency);
    }

    /**
     * Unrealised gain: current value minus invested amount.
     *
     * @param invested the invested amount, or {@code null}
     * @param current  the current value, or {@code null}
     * @return the gain (negative for a loss), or {@code null} when either input is missing
     */
    public static Money gain(Money invested, Money current) {
        return invested == null || current == null ? null : current.minus(invested);
    }

    /**
     * Gain as a percentage of what was invested, in percent points.
     *
     * @param gain     the unrealised gain, or {@code null}
     * @param invested the invested amount, or {@code null}
     * @return the percentage, or {@code null} when an input is missing or nothing was invested
     */
    public static BigDecimal gainPercent(Money gain, Money invested) {
        if (gain == null || invested == null || invested.amount().signum() == 0) {
            return null;
        }
        return percent(gain.amount(), invested.amount());
    }

    /**
     * Expresses a part as a percentage of a whole.
     *
     * @param part  the numerator
     * @param whole the denominator, not zero
     * @return {@code part / whole * 100}, rounded {@code HALF_UP} to two decimals
     */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Rounds a derived amount to money scale.
     *
     * @param value the unrounded amount
     * @return the amount at scale 2, {@code HALF_UP}
     */
    private static BigDecimal round(BigDecimal value) {
        return value.setScale(Money.SCALE, RoundingMode.HALF_UP);
    }
}
