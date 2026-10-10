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
}
