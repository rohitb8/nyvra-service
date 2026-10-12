package com.rohit.nyvra.portfolio;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for {@link PriceQuote}. Quotes are shared reference data and never user-scoped. */
public interface PriceQuoteRepository extends JpaRepository<PriceQuote, PriceQuoteId> {

    /**
     * Loads the most recent quote of each given instrument in one query. Instruments with no quote are
     * simply absent from the result.
     *
     * @param instrumentIds the instruments to price, not empty
     * @return at most one quote per instrument, the one with the latest {@code asOf}
     */
    @Query(value = """
        SELECT DISTINCT ON (instrument_id) instrument_id, as_of, price, source
        FROM price_quote
        WHERE instrument_id IN (:instrumentIds)
        ORDER BY instrument_id, as_of DESC
        """, nativeQuery = true)
    List<PriceQuote> findLatestByInstrumentIds(@Param("instrumentIds") Collection<java.util.UUID> instrumentIds);

    /**
     * Loads, per instrument, the latest quote strictly before a moment: the price in force when a window starts.
     *
     * @param instrumentIds the instruments to price, not empty
     * @param before        exclusive upper bound on {@code as_of}
     * @return at most one quote per instrument
     */
    @Query(value = """
        SELECT DISTINCT ON (instrument_id) instrument_id, as_of, price, source
        FROM price_quote
        WHERE instrument_id IN (:instrumentIds) AND as_of < :before
        ORDER BY instrument_id, as_of DESC
        """, nativeQuery = true)
    List<PriceQuote> findLatestBefore(@Param("instrumentIds") Collection<java.util.UUID> instrumentIds,
                                      @Param("before") java.time.Instant before);

    /**
     * Loads every quote of the given instruments inside a window, oldest first.
     *
     * @param instrumentIds the instruments to price
     * @param from          inclusive lower bound on {@code asOf}
     * @param to            inclusive upper bound on {@code asOf}
     * @return the quotes in the window
     */
    List<PriceQuote> findByInstrumentIdInAndAsOfBetweenOrderByAsOfAsc(Collection<java.util.UUID> instrumentIds,
                                                                        java.time.Instant from,
                                                                        java.time.Instant to);
}
