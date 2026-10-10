package com.rohit.nyvra.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.rohit.nyvra.common.money.Money;
import org.junit.jupiter.api.Test;

/** Unit tests of the pure valuation arithmetic: rounding, missing inputs and percentage handling. */
class PortfolioValuationTest {

    /** Cost basis is quantity times cost, rounded half-up to two decimals. */
    @Test
    void investedMultipliesAndRoundsHalfUp() {
        Money invested = PortfolioValuation.invested(new BigDecimal("3.333333"), new BigDecimal("1.500000"), "INR");
        assertThat(invested).isEqualTo(Money.inr("5.00"));
        assertThat(PortfolioValuation.invested(new BigDecimal("1"), new BigDecimal("0.005000"), "INR"))
            .isEqualTo(Money.inr("0.01"));
    }

    /** An unknown cost or price yields no amount rather than zero. */
    @Test
    void missingInputsGiveNull() {
        assertThat(PortfolioValuation.invested(BigDecimal.TEN, null, "INR")).isNull();
        assertThat(PortfolioValuation.current(BigDecimal.TEN, null, "INR")).isNull();
        assertThat(PortfolioValuation.gain(null, Money.inr("1.00"))).isNull();
        assertThat(PortfolioValuation.gain(Money.inr("1.00"), null)).isNull();
        assertThat(PortfolioValuation.gainPercent(null, Money.inr("1.00"))).isNull();
    }

    /** A gain is current minus invested and is negative for a loss. */
    @Test
    void gainIsCurrentMinusInvested() {
        assertThat(PortfolioValuation.gain(Money.inr("100.00"), Money.inr("125.50"))).isEqualTo(Money.inr("25.50"));
        assertThat(PortfolioValuation.gain(Money.inr("100.00"), Money.inr("80.00"))).isEqualTo(Money.inr("-20.00"));
    }

    /** The gain percentage is in percent points to two decimals and undefined when nothing was invested. */
    @Test
    void gainPercentIsInPercentPointsAndGuardsZeroCost() {
        assertThat(PortfolioValuation.gainPercent(Money.inr("25.50"), Money.inr("100.00")))
            .isEqualByComparingTo("25.50");
        assertThat(PortfolioValuation.gainPercent(Money.inr("1.00"), Money.inr("3.00")))
            .isEqualByComparingTo("33.33");
        assertThat(PortfolioValuation.gainPercent(Money.inr("1.00"), Money.zero("INR"))).isNull();
    }
}
