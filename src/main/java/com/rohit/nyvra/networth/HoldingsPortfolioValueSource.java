package com.rohit.nyvra.networth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.portfolio.PortfolioHolding;
import com.rohit.nyvra.portfolio.PortfolioHoldingRepository;
import com.rohit.nyvra.portfolio.PortfolioValuation;
import com.rohit.nyvra.portfolio.PriceQuote;
import com.rohit.nyvra.portfolio.PriceQuoteRepository;

/**
 * The real {@link PortfolioValueSource}: values a user's open INR holdings at the latest recorded price per
 * instrument. It reads the portfolio module's repositories and valuation arithmetic, so the figure matches the
 * portfolio summary and the portfolio module itself needs no knowledge of net worth.
 *
 * <p>A holding with no price yet is carried at its cost so that a freshly added position does not vanish from
 * net worth until someone records a quote; a holding with neither price nor cost contributes nothing. Holdings
 * in other currencies are skipped because there is no FX source yet, the same rule the portfolio summary uses.
 */
@Component
public class HoldingsPortfolioValueSource implements PortfolioValueSource {

    /** Open holdings of a user. */
    private final PortfolioHoldingRepository holdings;
    /** Latest price per instrument. */
    private final PriceQuoteRepository quotes;

    /**
     * Creates the source.
     *
     * @param holdings the holding repository
     * @param quotes   the price quote repository
     */
    public HoldingsPortfolioValueSource(PortfolioHoldingRepository holdings, PriceQuoteRepository quotes) {
        this.holdings = holdings;
        this.quotes = quotes;
    }

    /**
     * Values the user's open INR holdings at the latest known prices, falling back to cost for unpriced ones.
     *
     * @param userId the owner of the holdings
     * @return the total value in INR, zero when the user holds nothing
     */
    @Override
    @Transactional(readOnly = true)
    public Money currentValue(UUID userId) {
        List<PortfolioHolding> inr = holdings.findByUserIdAndClosedAtIsNull(userId).stream()
            .filter(h -> Money.INR.equals(h.getCurrency()))
            .toList();
        Money total = Money.zero(Money.INR);
        if (inr.isEmpty()) {
            return total;
        }
        Map<UUID, PriceQuote> latest = quotes
            .findLatestByInstrumentIds(inr.stream().map(PortfolioHolding::getInstrumentId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(PriceQuote::getInstrumentId, Function.identity(), (a, b) -> a));
        for (PortfolioHolding h : inr) {
            PriceQuote quote = latest.get(h.getInstrumentId());
            Money value = quote == null ? null
                : PortfolioValuation.current(h.getQuantity(), quote.getPrice(), h.getCurrency());
            if (value == null) {
                value = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), h.getCurrency());
            }
            if (value != null) {
                total = total.plus(value);
            }
        }
        return total;
    }
}
