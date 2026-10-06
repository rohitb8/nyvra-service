package com.rohit.nyvra.accounts;

/** Stored as {@code TEXT + CHECK} (V2__accounts.sql). */
public enum AccountType {
    SAVINGS,
    CURRENT,
    LOAN,
    CREDIT_CARD,
    TERM_DEPOSIT,
    RECURRING_DEPOSIT,
    EPF,
    NPS
}
