package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.rohit.nyvra.common.persistence.RecordSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The rupee price of one unit of a foreign currency on a date. Shared reference data, one row per currency
 * and day; a later write for the same day replaces the earlier rate.
 */
@Entity
@Table(name = "fx_rate")
@IdClass(FxRateId.class)
public class FxRate {

    /** ISO 4217 code of the foreign currency; part of the primary key, never INR. */
    @Id
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** The day the rate applies to; part of the primary key. */
    @Id
    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    /** Rupees per one unit of the currency, scale 6, above zero. */
    @Column(name = "inr_per_unit", nullable = false, precision = 19, scale = 6)
    private BigDecimal inrPerUnit;

    /** Where the rate came from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    /** Required by JPA; not for application use. */
    protected FxRate() {
        // for JPA
    }

    /**
     * Creates a rate.
     *
     * @param currency   the foreign currency, required and not INR
     * @param rateDate   the day the rate applies to, required
     * @param inrPerUnit rupees per unit, required and above zero
     * @param source     where the rate came from, required
     * @throws IllegalArgumentException if the currency is INR or the rate is not above zero
     */
    public FxRate(String currency, LocalDate rateDate, BigDecimal inrPerUnit, RecordSource source) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.rateDate = Objects.requireNonNull(rateDate, "rateDate");
        this.inrPerUnit = Objects.requireNonNull(inrPerUnit, "inrPerUnit");
        this.source = Objects.requireNonNull(source, "source");
        if ("INR".equals(currency)) {
            throw new IllegalArgumentException("INR has no rate against itself");
        }
        if (inrPerUnit.signum() <= 0) {
            throw new IllegalArgumentException("rate must be above zero");
        }
    }

    /**
     * Replaces the rate and its source, for a re-write of the same day.
     *
     * @param newRate   rupees per unit, above zero
     * @param newSource where the new rate came from
     * @throws IllegalArgumentException if the rate is not above zero
     */
    public void replace(BigDecimal newRate, RecordSource newSource) {
        if (newRate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be above zero");
        }
        this.inrPerUnit = newRate;
        this.source = Objects.requireNonNull(newSource, "source");
    }

    /** @return the ISO 4217 code */
    public String getCurrency() {
        return currency;
    }

    /** @return the day the rate applies to */
    public LocalDate getRateDate() {
        return rateDate;
    }

    /** @return rupees per one unit, scale 6 */
    public BigDecimal getInrPerUnit() {
        return inrPerUnit;
    }

    /** @return where the rate came from */
    public RecordSource getSource() {
        return source;
    }
}
