package com.rohit.nyvra.portfolio;

import java.util.Objects;

import com.rohit.nyvra.common.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tradable or trackable security (a share, fund, bond…). Reference data shared by all users, so it has no
 * owner; the ISIN is unique when present.
 */
@Entity
@Table(name = "instrument")
public class Instrument extends AbstractEntity {

    /** 12-character ISIN, or {@code null} for instruments that have none; unique across the table. */
    @Column(name = "isin", length = 12, unique = true)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String isin;

    /** Exchange ticker or scheme code, when known. */
    @Column(name = "symbol")
    private String symbol;

    /** Display name, when known. */
    @Column(name = "name")
    private String name;

    /** Kind of investment this instrument is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false)
    private AssetClass assetClass;

    /** ISO 4217 code of the currency the instrument is priced in. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** ISO 3166 alpha-2 country code, when known. */
    @Column(name = "country", length = 2)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String country;

    /** Required by JPA; not for application use. */
    protected Instrument() {
        // for JPA
    }

    /**
     * Creates an instrument.
     *
     * @param isin       ISIN, or {@code null}
     * @param symbol     ticker or scheme code, or {@code null}
     * @param name       display name, or {@code null}
     * @param assetClass kind of investment, required
     * @param currency   ISO 4217 pricing currency, required
     * @param country    ISO 3166 alpha-2 country, or {@code null}
     */
    public Instrument(String isin, String symbol, String name, AssetClass assetClass, String currency,
                      String country) {
        this.isin = isin;
        this.symbol = symbol;
        this.name = name;
        this.assetClass = Objects.requireNonNull(assetClass, "assetClass");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.country = country;
    }

    /** @return the ISIN, or {@code null} */
    public String getIsin() {
        return isin;
    }

    /** @return the ticker or scheme code, or {@code null} */
    public String getSymbol() {
        return symbol;
    }

    /** @return the display name, or {@code null} */
    public String getName() {
        return name;
    }

    /** @return the kind of investment */
    public AssetClass getAssetClass() {
        return assetClass;
    }

    /** @return the ISO 4217 pricing currency */
    public String getCurrency() {
        return currency;
    }

    /** @return the ISO 3166 alpha-2 country, or {@code null} */
    public String getCountry() {
        return country;
    }
}
