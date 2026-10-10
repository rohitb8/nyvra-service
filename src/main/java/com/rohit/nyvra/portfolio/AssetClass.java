package com.rohit.nyvra.portfolio;

/**
 * Kind of investment an instrument or holding belongs to. Stored as {@code TEXT} guarded by the
 * {@code CHECK} constraints in {@code V6__portfolio.sql}; the names must stay in step with them.
 */
public enum AssetClass {
    /** Directly held listed shares. */
    EQUITY,
    /** Mutual fund units. */
    MUTUAL_FUND,
    /** Bonds, debentures and similar fixed-income securities. */
    BOND,
    /** National Pension System holdings. */
    NPS,
    /** Employees' Provident Fund balances. */
    EPF,
    /** Shares listed outside India. */
    FOREIGN_EQUITY,
    /** Cash-like holdings tracked as an investment. */
    CASH,
    /** Gold in any form (physical, ETF, sovereign bonds). */
    GOLD,
    /** Real-estate investment trusts. */
    REIT
}
