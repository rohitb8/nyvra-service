package com.rohit.nyvra.portfolio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence access for {@link Instrument}. Instruments are shared reference data, so none of these
 * lookups is user-scoped. The {@code Specification} support powers the filtered instrument search.
 */
public interface InstrumentRepository
    extends JpaRepository<Instrument, UUID>, JpaSpecificationExecutor<Instrument> {

    /**
     * Finds an instrument by its ISIN.
     *
     * @param isin the 12-character ISIN
     * @return the instrument, or empty when none has that ISIN
     */
    Optional<Instrument> findByIsin(String isin);
}
