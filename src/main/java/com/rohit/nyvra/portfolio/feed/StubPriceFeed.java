package com.rohit.nyvra.portfolio.feed;

import java.util.Collection;
import java.util.List;

import com.rohit.nyvra.portfolio.Instrument;
import org.springframework.stereotype.Component;

/**
 * Placeholder feed that knows no prices. It lets the sync endpoint run end to end until a real provider is
 * integrated. A real provider is added as another {@link PriceFeed} bean marked {@code @Primary}.
 */
@Component
public class StubPriceFeed implements PriceFeed {

    /** {@inheritDoc} */
    @Override
    public List<FeedQuote> fetchPrices(Collection<Instrument> instruments) {
        return List.of();
    }

    /** {@inheritDoc} */
    @Override
    public List<FeedFxRate> fetchFxRates(Collection<String> currencies) {
        return List.of();
    }
}
