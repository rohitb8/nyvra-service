package com.rohit.nyvra.networth;

/** Bucket size for trend queries; the last snapshot in each bucket represents it. */
public enum Granularity {
    /** One point per day. */
    DAY("day"),
    /** One point per ISO week. */
    WEEK("week"),
    /** One point per calendar month. */
    MONTH("month");

    /** The matching {@code date_trunc} unit. */
    private final String sqlUnit;

    /**
     * Creates a granularity.
     *
     * @param sqlUnit the {@code date_trunc} unit
     */
    Granularity(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    /** @return the {@code date_trunc} unit, safe to inline in SQL */
    String sqlUnit() {
        return sqlUnit;
    }
}
