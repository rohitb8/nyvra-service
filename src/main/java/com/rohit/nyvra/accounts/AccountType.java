package com.rohit.nyvra.accounts;

/** Stored as {@code TEXT + CHECK} (V2__accounts.sql). */
public enum AccountType {
    /** Savings bank account. */
    SAVINGS,
    /** Current bank account. */
    CURRENT,
    /** Loan (a liability). */
    LOAN,
    /** Credit card (a liability). */
    CREDIT_CARD,
    /** Fixed or term deposit. */
    TERM_DEPOSIT,
    /** Recurring deposit. */
    RECURRING_DEPOSIT,
    /** Employees' Provident Fund account. */
    EPF,
    /** National Pension System account. */
    NPS
}
