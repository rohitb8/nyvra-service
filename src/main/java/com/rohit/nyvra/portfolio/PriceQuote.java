package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.rohit.nyvra.common.persistence.RecordSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One observed price of an instrument at a moment. Rows of the {@code price_quote} hypertable are
 * append-only: there is no id or audit timestamp, and the latest {@code asOf} drives current valuation.
 */
@Entity
@Table(name = "price_quote")
@IdClass(PriceQuoteId.class)
public class PriceQuote {

    /** The priced instrument; part of the primary key. */
    @Id
    @Column(name = "instrument_id", nullable = false, updatable = false)
    private UUID instrumentId;

    /** The moment the price applies to; part of the primary key and the hypertable's time column. */
    @Id
    @Column(name = "as_of", nullable = false, updatable = false)
    private Instant asOf;

    /** Price per unit in the instrument's currency, scale 6, never negative. */
    @Column(name = "price", nullable = false, precision = 19, scale = 6)
    private BigDecimal price;

    /** Where the price came from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    /** Required by JPA; not for application use. */
    protected PriceQuote() {
        // for JPA
    }

    /**
     * Creates a quote.
     *
     * @param instrumentId the priced instrument, required
     * @param asOf         the moment the price applies to, required
     * @param price        price per unit, required and not negative
     * @param source       where the price came from, required
     * @throws IllegalArgumentException if the price is negative
     */
    public PriceQuote(UUID instrumentId, Instant asOf, BigDecimal price, RecordSource source) {
        this.instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.price = Objects.requireNonNull(price, "price");
        this.source = Objects.requireNonNull(source, "source");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
    }

    /** @return the priced instrument id */
    public UUID getInstrumentId() {
        return instrumentId;
    }

    /** @return the moment the price applies to */
    public Instant getAsOf() {
        return asOf;
    }

    /** @return the price per unit, scale 6 */
    public BigDecimal getPrice() {
        return price;
    }

    /** @return where the price came from */
    public RecordSource getSource() {
        return source;
    }
}
