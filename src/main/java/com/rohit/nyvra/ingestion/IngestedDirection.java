package com.rohit.nyvra.ingestion;

/** Whether an ingested transaction took money out of the account or put money in. */
public enum IngestedDirection {

    /** Money left the account. */
    DEBIT,

    /** Money entered the account. */
    CREDIT
}
