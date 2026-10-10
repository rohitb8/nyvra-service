package com.rohit.nyvra.expense;

/** Who chose an expense's category. Stored as {@code TEXT + CHECK} (V15__expense_category_source.sql). */
public enum CategorySource {

    /** Assigned by the system (ingestion or a categorisation rule); a rule may later change it. */
    AUTO,

    /** Chosen by the user; never overwritten by a rule back-fill. */
    USER
}
