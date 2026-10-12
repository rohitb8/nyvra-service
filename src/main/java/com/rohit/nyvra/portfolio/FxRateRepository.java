package com.rohit.nyvra.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link FxRate}. Rates are shared reference data and never user-scoped. */
public interface FxRateRepository extends JpaRepository<FxRate, FxRateId> {

    /**
     * Finds the most recent rate of a currency on or before a day.
     *
     * @param currency ISO 4217 code
     * @param date     the latest day to consider
     * @return the rate, or empty when none exists that early
     */
    Optional<FxRate> findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate date);

    /**
     * Lists a currency's rates in a date range, oldest first.
     *
     * @param currency ISO 4217 code
     * @param from     first day, inclusive
     * @param to       last day, inclusive
     * @return the rates in range
     */
    List<FxRate> findByCurrencyAndRateDateBetweenOrderByRateDateAsc(String currency, LocalDate from, LocalDate to);
}
