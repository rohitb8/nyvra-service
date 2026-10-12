package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Extended internal rate of return: the annual rate that makes the net present value of dated cash flows zero,
 * with each flow discounted over {@code days / 365}. Pure calculation, kept free of persistence so it can be
 * unit tested on its own. Intermediate maths uses {@code double}; only the final rate is converted back to a
 * {@link BigDecimal} percentage, which is not a money value.
 */
public final class Xirr {

    /** Seconds in a day, to turn instants into fractional days. */
    private static final double SECONDS_PER_DAY = 86_400d;

    /** Days per year used by the XIRR convention. */
    private static final double DAYS_PER_YEAR = 365d;

    /** Convergence tolerance on the net present value, in currency units. */
    private static final double TOLERANCE = 1e-7;

    /** Iteration cap for both the Newton and the bisection phase. */
    private static final int MAX_ITERATIONS = 200;

    /** Lowest rate searched, just above -100% (total loss). */
    private static final double MIN_RATE = -0.999999;

    /** Highest rate searched (one million percent a year). */
    private static final double MAX_RATE = 10_000d;

    /** Scale of the returned percentage. */
    private static final int PERCENT_SCALE = 2;

    /** Not instantiable. */
    private Xirr() {
    }

    /**
     * One dated cash flow.
     *
     * @param at     when the money moved
     * @param amount signed amount: negative is money paid in (an investment), positive is money received
     */
    public record CashFlow(Instant at, BigDecimal amount) {
    }

    /**
     * Computes the annualised return of a series of cash flows.
     *
     * @param flows the cash flows, in any order
     * @return the rate in percent points (for example {@code 12.34}), or empty when it is undefined: fewer
     *         than two flows, no flow of each sign, or no root in the searched range
     */
    public static Optional<BigDecimal> compute(List<CashFlow> flows) {
        if (flows == null || flows.size() < 2) {
            return Optional.empty();
        }
        boolean negative = flows.stream().anyMatch(f -> f.amount().signum() < 0);
        boolean positive = flows.stream().anyMatch(f -> f.amount().signum() > 0);
        if (!negative || !positive) {
            return Optional.empty();
        }
        Instant start = flows.stream().map(CashFlow::at).min(Instant::compareTo).orElseThrow();
        double[] years = new double[flows.size()];
        double[] amounts = new double[flows.size()];
        for (int i = 0; i < flows.size(); i++) {
            double days = (flows.get(i).at().getEpochSecond() - start.getEpochSecond()) / SECONDS_PER_DAY;
            years[i] = days / DAYS_PER_YEAR;
            amounts[i] = flows.get(i).amount().doubleValue();
        }
        double rate = solve(years, amounts);
        if (Double.isNaN(rate)) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(rate * 100).setScale(PERCENT_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Finds the root, trying Newton's method first and falling back to bisection when it diverges.
     *
     * @param years   each flow's offset from the first flow, in years
     * @param amounts each flow's signed amount
     * @return the rate as a ratio, or {@code NaN} when none is found
     */
    private static double solve(double[] years, double[] amounts) {
        double rate = 0.1;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double value = npv(rate, years, amounts);
            if (Math.abs(value) < TOLERANCE) {
                return rate;
            }
            double slope = derivative(rate, years, amounts);
            if (slope == 0 || Double.isNaN(slope)) {
                break;
            }
            double next = rate - value / slope;
            if (Double.isNaN(next) || next <= MIN_RATE || next > MAX_RATE) {
                break;
            }
            if (Math.abs(next - rate) < 1e-12) {
                return next;
            }
            rate = next;
        }
        return bisect(years, amounts);
    }

    /**
     * Bisects between the rate bounds; the NPV falls as the rate rises, so a sign change brackets the root.
     *
     * @param years   each flow's offset in years
     * @param amounts each flow's signed amount
     * @return the rate as a ratio, or {@code NaN} when the bounds do not bracket a root
     */
    private static double bisect(double[] years, double[] amounts) {
        double low = MIN_RATE;
        double high = MAX_RATE;
        double fLow = npv(low, years, amounts);
        double fHigh = npv(high, years, amounts);
        if (Double.isNaN(fLow) || Double.isNaN(fHigh) || Math.signum(fLow) == Math.signum(fHigh)) {
            return Double.NaN;
        }
        for (int i = 0; i < MAX_ITERATIONS * 2; i++) {
            double mid = (low + high) / 2;
            double fMid = npv(mid, years, amounts);
            if (Math.abs(fMid) < TOLERANCE || (high - low) < 1e-12) {
                return mid;
            }
            if (Math.signum(fMid) == Math.signum(fLow)) {
                low = mid;
                fLow = fMid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    /**
     * Net present value at a rate.
     *
     * @param rate    the annual rate as a ratio
     * @param years   each flow's offset in years
     * @param amounts each flow's signed amount
     * @return the sum of discounted flows
     */
    private static double npv(double rate, double[] years, double[] amounts) {
        double sum = 0;
        for (int i = 0; i < amounts.length; i++) {
            sum += amounts[i] / Math.pow(1 + rate, years[i]);
        }
        return sum;
    }

    /**
     * Derivative of the net present value with respect to the rate.
     *
     * @param rate    the annual rate as a ratio
     * @param years   each flow's offset in years
     * @param amounts each flow's signed amount
     * @return the slope at that rate
     */
    private static double derivative(double rate, double[] years, double[] amounts) {
        double sum = 0;
        for (int i = 0; i < amounts.length; i++) {
            sum -= years[i] * amounts[i] / Math.pow(1 + rate, years[i] + 1);
        }
        return sum;
    }
}
