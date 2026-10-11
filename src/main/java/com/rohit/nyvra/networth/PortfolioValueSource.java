package com.rohit.nyvra.networth;

import java.util.UUID;

import com.rohit.nyvra.common.money.Money;

/**
 * Seam through which the portfolio module contributes its market value to net worth. The portfolio module
 * implements this as a Spring bean; until it does, net worth treats the portfolio as empty. Keeping it an
 * interface here means {@code networth} never depends on {@code portfolio}.
 */
public interface PortfolioValueSource {

    /**
     * Values the user's holdings at the latest known prices.
     *
     * @param userId the owner of the holdings
     * @return the total value in INR, never negative; zero when the user holds nothing
     */
    Money currentValue(UUID userId);
}
