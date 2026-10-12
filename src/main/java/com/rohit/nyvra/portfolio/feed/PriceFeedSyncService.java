package com.rohit.nyvra.portfolio.feed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.FxRateService;
import com.rohit.nyvra.portfolio.Instrument;
import com.rohit.nyvra.portfolio.InstrumentRepository;
import com.rohit.nyvra.portfolio.PriceQuote;
import com.rohit.nyvra.portfolio.PriceQuoteId;
import com.rohit.nyvra.portfolio.PriceQuoteRepository;
import com.rohit.nyvra.portfolio.dto.PriceFeedSyncResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pulls prices and FX rates from the {@link PriceFeed} and stores them. Feed prices are saved with source
 * {@code DERIVED}, the closest of the allowed sources, so they stay distinguishable from manual entries.
 */
@Service
public class PriceFeedSyncService {

    /** Tolerance for a feed price dated slightly ahead of this server's clock, in seconds. */
    private static final long FUTURE_SKEW_SECONDS = 60;

    /** The feed to pull from. */
    private final PriceFeed feed;

    /** Instrument storage. */
    private final InstrumentRepository instruments;

    /** Quote storage. */
    private final PriceQuoteRepository quotes;

    /** FX rate storage and rules. */
    private final FxRateService fxRates;

    /**
     * Creates the service.
     *
     * @param feed        the feed to pull from
     * @param instruments instrument storage
     * @param quotes      quote storage
     * @param fxRates     FX rate storage and rules
     */
    public PriceFeedSyncService(PriceFeed feed, InstrumentRepository instruments, PriceQuoteRepository quotes,
                                FxRateService fxRates) {
        this.feed = feed;
        this.instruments = instruments;
        this.quotes = quotes;
        this.fxRates = fxRates;
    }

    /**
     * Fetches current prices for every known instrument and rates for every foreign currency in use, and
     * stores what the feed returns. Quotes already stored for the same instrument and moment are kept.
     *
     * @return how many prices and rates were stored
     */
    @Transactional
    public PriceFeedSyncResponse sync() {
        List<Instrument> all = instruments.findAll();
        Instant limit = Instant.now().plusSeconds(FUTURE_SKEW_SECONDS);
        int prices = 0;
        for (PriceFeed.FeedQuote q : feed.fetchPrices(all)) {
            Instant asOf = q.asOf().truncatedTo(ChronoUnit.MICROS);
            if (q.price().signum() < 0 || asOf.isAfter(limit)
                || quotes.existsById(new PriceQuoteId(q.instrumentId(), asOf))) {
                continue;
            }
            quotes.save(new PriceQuote(q.instrumentId(), asOf, q.price(), RecordSource.DERIVED));
            prices++;
        }

        Set<String> currencies = all.stream().map(Instrument::getCurrency)
            .filter(c -> !Money.INR.equals(c)).collect(Collectors.toSet());
        int rates = 0;
        if (!currencies.isEmpty()) {
            for (PriceFeed.FeedFxRate r : feed.fetchFxRates(currencies)) {
                if (!Money.INR.equals(r.quote()) || r.rate().signum() <= 0) {
                    continue;
                }
                LocalDate day = r.asOf().atZone(ZoneOffset.UTC).toLocalDate();
                try {
                    fxRates.store(r.base(), day, r.rate(), RecordSource.DERIVED);
                    rates++;
                } catch (UnprocessableEntityException skipped) {
                    // a rate dated ahead of today is ignored rather than failing the whole sync
                }
            }
        }
        return new PriceFeedSyncResponse(prices, rates);
    }
}
