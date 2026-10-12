package com.rohit.nyvra.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests of {@link Xirr}, checked against rates that can be worked out by hand. */
class XirrTest {

    /** A fixed start so flows land on exact day offsets. */
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

    /**
     * Builds a flow a number of days after the start.
     *
     * @param days   days after {@link #START}
     * @param amount signed amount
     * @return the flow
     */
    private static Xirr.CashFlow flow(long days, String amount) {
        return new Xirr.CashFlow(START.plusSeconds(days * 86_400), new BigDecimal(amount));
    }

    /** Doubling money over exactly one year is a 100% return. */
    @Test
    void doublingInOneYearIsOneHundredPercent() {
        assertThat(Xirr.compute(List.of(flow(0, "-1000"), flow(365, "2000")))).contains(new BigDecimal("100.00"));
    }

    /** Ten percent growth over a year is a 10% return, regardless of flow order. */
    @Test
    void growthOverAYearMatchesTheRate() {
        assertThat(Xirr.compute(List.of(flow(365, "1100"), flow(0, "-1000")))).contains(new BigDecimal("10.00"));
    }

    /** A loss gives a negative rate. */
    @Test
    void lossIsNegative() {
        assertThat(Xirr.compute(List.of(flow(0, "-1000"), flow(365, "900")))).contains(new BigDecimal("-10.00"));
    }

    /** Several investments are blended into one rate. */
    @Test
    void blendsMultipleFlows() {
        var rate = Xirr.compute(List.of(flow(0, "-1000"), flow(182, "-1000"), flow(365, "2300")));
        assertThat(rate).isPresent();
        assertThat(rate.get()).isBetween(new BigDecimal("10"), new BigDecimal("25"));
    }

    /** A huge gain in a short time still converges through the bisection fallback. */
    @Test
    void convergesOnExtremeReturns() {
        assertThat(Xirr.compute(List.of(flow(0, "-100"), flow(100, "1000")))).isPresent();
    }

    /** The rate is undefined without both an outflow and an inflow, or with fewer than two flows. */
    @Test
    void isUndefinedWithoutBothSigns() {
        assertThat(Xirr.compute(List.of(flow(0, "-100"), flow(30, "-50")))).isEmpty();
        assertThat(Xirr.compute(List.of(flow(0, "-100")))).isEmpty();
        assertThat(Xirr.compute(List.of())).isEmpty();
        assertThat(Xirr.compute(null)).isEmpty();
    }

    /** Flows on the same instant have no time to grow, so there is no meaningful rate. */
    @Test
    void isUndefinedWhenAllFlowsShareAMoment() {
        assertThat(Xirr.compute(List.of(flow(0, "-100"), flow(0, "150")))).isEmpty();
    }
}
