package com.rohit.nyvra.analytics;

/** A data-sufficiency gate (FINANCIAL_RULES §6) that failed, as a "what to connect" checklist item. */
public enum MissingData {
    /** At least one income entry. */
    INCOME_ENTRY,
    /** Two full months of categorised transactions. */
    CATEGORISED_TRANSACTIONS_2_MONTHS,
    /** Known liquid assets. */
    LIQUID_ASSETS,
    /** Three months of essential-spend history. */
    ESSENTIAL_SPEND_3_MONTHS,
    /** A linked liability account, or a "no debt" confirmation. */
    LIABILITY_ACCOUNT_OR_NO_DEBT_CONFIRMATION,
    /** A portfolio with enough holdings. */
    HOLDINGS,
    /** At least one goal. */
    GOALS
}
