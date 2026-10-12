package com.rohit.nyvra.networth;

/** The module a slice of net worth comes from. */
public enum NetWorthSource {
    /** Bank, deposit, retirement, loan and card accounts. */
    ACCOUNTS,
    /** Market value of portfolio holdings. */
    PORTFOLIO,
    /** Assets and liabilities the user maintains by hand. */
    MANUAL
}
