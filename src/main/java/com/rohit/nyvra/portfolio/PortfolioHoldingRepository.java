package com.rohit.nyvra.portfolio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence access for {@link PortfolioHolding}. Reads that serve the API take the user id so another
 * user's rows are never visible; the {@code Specification} support powers the filtered, sorted list.
 */
public interface PortfolioHoldingRepository
    extends JpaRepository<PortfolioHolding, UUID>, JpaSpecificationExecutor<PortfolioHolding> {

    /**
     * Finds a holding owned by the given user.
     *
     * @param id the holding id
     * @param userId the owning user
     * @return the holding, or empty when it does not exist or belongs to someone else
     */
    Optional<PortfolioHolding> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Tells whether the user already has an open position in an instrument.
     *
     * @param userId the owning user
     * @param instrumentId the instrument
     * @return {@code true} if an open holding exists
     */
    boolean existsByUserIdAndInstrumentIdAndClosedAtIsNull(UUID userId, UUID instrumentId);

    /**
     * Lists all of a user's open positions, for the portfolio summary.
     *
     * @param userId the owning user
     * @return the open holdings
     */
    List<PortfolioHolding> findByUserIdAndClosedAtIsNull(UUID userId);
}
