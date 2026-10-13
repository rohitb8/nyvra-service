package com.rohit.nyvra.accounts;

/** Stored as {@code TEXT + CHECK} (V2__accounts.sql). */
public enum TransactionDirection {
    /** Money out; the amount is negative. */
    DEBIT,
    /** Money in; the amount is positive. */
    CREDIT
}
