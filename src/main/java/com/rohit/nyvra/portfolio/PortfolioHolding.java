package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.rohit.nyvra.common.persistence.AbstractEntity;
import com.rohit.nyvra.common.persistence.RecordSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A user's position in one instrument. A holding with quantity zero is closed and kept for XIRR history;
 * {@code closedAt} is set exactly then, mirrored by {@code chk_portfolio_holding_closed}.
 */
@Entity
@Table(name = "portfolio_holding")
public class PortfolioHolding extends AbstractEntity {

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The held instrument; fixed at creation. */
    @Column(name = "instrument_id", nullable = false, updatable = false)
    private UUID instrumentId;

    /** Asset class, copied from the instrument at creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, updatable = false)
    private AssetClass assetClass;

    /** Units held, scale 6, never negative; zero means closed. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /** Average cost per unit, scale 6; {@code null} when unknown. */
    @Column(name = "avg_cost", precision = 19, scale = 6)
    private BigDecimal avgCost;

    /** ISO 4217 code of the cost and valuation currency; fixed at creation. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Linked account id, when the position is held in a tracked account; not exposed by the API yet. */
    @Column(name = "account_id")
    private UUID accountId;

    /** Where the position came from; only {@code MANUAL} holdings can be changed through the API. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    /** When the position was opened. */
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** When the position was closed, or {@code null} while open. */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** Required by JPA; not for application use. */
    protected PortfolioHolding() {
        // for JPA
    }

    /**
     * Opens a position.
     *
     * @param userId     owning user, required
     * @param instrument the held instrument, required; its id, asset class and currency are copied
     * @param quantity   units held, required and greater than zero
     * @param avgCost    average cost per unit, or {@code null} when unknown; not negative
     * @param source     where the position came from, required
     * @param openedAt   when it was opened, required
     * @throws IllegalArgumentException if the quantity is not positive or the cost is negative
     */
    public PortfolioHolding(UUID userId, Instrument instrument, BigDecimal quantity, BigDecimal avgCost,
                            RecordSource source, Instant openedAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(instrument, "instrument");
        this.instrumentId = instrument.getId();
        this.assetClass = instrument.getAssetClass();
        this.currency = instrument.getCurrency();
        this.source = Objects.requireNonNull(source, "source");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        if (Objects.requireNonNull(quantity, "quantity").signum() <= 0) {
            throw new IllegalArgumentException("A new holding needs a quantity above zero");
        }
        requireNonNegative(avgCost);
        this.quantity = quantity;
        this.avgCost = avgCost;
    }

    /**
     * Changes the quantity and/or average cost. Reaching quantity zero closes the position at {@code now};
     * a positive quantity on a closed position reopens it.
     *
     * @param newQuantity the new quantity, or {@code null} to keep the current one; not negative
     * @param newAvgCost  the new average cost, or {@code null} to keep the current one; not negative
     * @param now         the time to record as the closing time when the position closes
     * @throws IllegalArgumentException if a value is negative
     */
    public void adjust(BigDecimal newQuantity, BigDecimal newAvgCost, Instant now) {
        if (newQuantity != null) {
            if (newQuantity.signum() < 0) {
                throw new IllegalArgumentException("quantity must not be negative");
            }
            this.quantity = newQuantity;
            this.closedAt = newQuantity.signum() == 0 ? (closedAt != null ? closedAt : now) : null;
        }
        if (newAvgCost != null) {
            requireNonNegative(newAvgCost);
            this.avgCost = newAvgCost;
        }
    }

    /**
     * Rejects a negative cost.
     *
     * @param cost the cost to check; {@code null} is allowed
     */
    private static void requireNonNegative(BigDecimal cost) {
        if (cost != null && cost.signum() < 0) {
            throw new IllegalArgumentException("avgCost must not be negative");
        }
    }

    /** @return {@code true} while the position is open */
    public boolean isOpen() {
        return closedAt == null;
    }

    /** @return the owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the held instrument id */
    public UUID getInstrumentId() {
        return instrumentId;
    }

    /** @return the asset class */
    public AssetClass getAssetClass() {
        return assetClass;
    }

    /** @return units held, scale 6 */
    public BigDecimal getQuantity() {
        return quantity;
    }

    /** @return the average cost per unit, or {@code null} when unknown */
    public BigDecimal getAvgCost() {
        return avgCost;
    }

    /** @return the ISO 4217 currency */
    public String getCurrency() {
        return currency;
    }

    /** @return the linked account id, or {@code null} */
    public UUID getAccountId() {
        return accountId;
    }

    /** @return where the position came from */
    public RecordSource getSource() {
        return source;
    }

    /** @return when the position was opened */
    public Instant getOpenedAt() {
        return openedAt;
    }

    /** @return when the position was closed, or {@code null} while open */
    public Instant getClosedAt() {
        return closedAt;
    }
}
