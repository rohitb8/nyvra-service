package com.rohit.nyvra.ingestion;

/** Financial-information (FI) type of a raw record, mirroring the Account Aggregator FI types. */
public enum PayloadType {

    /** Savings or current account. */
    DEPOSIT,

    /** Fixed deposit. */
    TERM_DEPOSIT,

    /** Recurring deposit. */
    RECURRING_DEPOSIT,

    /** Mutual fund folio. */
    MUTUAL_FUND,

    /** Demat equity holdings. */
    EQUITIES,

    /** National Pension System account. */
    NPS,

    /** Employees' Provident Fund account. */
    EPF,

    /** Loan account. */
    LOAN,

    /** Credit card account. */
    CREDIT_CARD
}
