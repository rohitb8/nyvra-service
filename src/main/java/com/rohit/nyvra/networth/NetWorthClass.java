package com.rohit.nyvra.networth;

/**
 * The buckets a net-worth breakdown is reported in. Each class belongs to exactly one side of the balance
 * sheet, so a breakdown line needs no separate asset/liability flag to be interpreted.
 */
public enum NetWorthClass {
    /** Savings and current accounts. */
    CASH(false),
    /** Term and recurring deposits. */
    DEPOSITS(false),
    /** EPF and NPS balances. */
    RETIREMENT(false),
    /** Mutual funds, stocks, bonds and other portfolio holdings. */
    INVESTMENTS(false),
    /** Manually tracked land or buildings. */
    REAL_ESTATE(false),
    /** Manually tracked vehicles. */
    VEHICLE(false),
    /** Manually tracked jewellery. */
    JEWELLERY(false),
    /** Manually tracked unlisted equity. */
    PRIVATE_EQUITY(false),
    /** Manually tracked assets of any other kind. */
    OTHER_ASSET(false),
    /** Outstanding balance on bank loans. */
    LOAN(true),
    /** Outstanding balance on credit cards. */
    CREDIT_CARD(true),
    /** Manually tracked loans from people rather than banks. */
    PERSONAL_LOAN(true),
    /** Manually tracked liabilities of any other kind. */
    OTHER_LIABILITY(true);

    /** {@code true} when the class counts towards liabilities. */
    private final boolean liability;

    /**
     * Creates a class.
     *
     * @param liability whether the class is a liability
     */
    NetWorthClass(boolean liability) {
        this.liability = liability;
    }

    /**
     * Tells which side of the balance sheet this class sits on.
     *
     * @return {@code true} for liabilities, {@code false} for assets
     */
    public boolean isLiability() {
        return liability;
    }

    /**
     * Maps a manual entry to its breakdown class.
     *
     * @param kind the entry's kind
     * @param cls  the entry's class
     * @return the breakdown class; {@code PRIVATE_EQUITY} or {@code PERSONAL_LOAN} on the wrong side fall back to
     *         the side's catch-all
     */
    public static NetWorthClass of(ManualItemKind kind, ManualItemClass cls) {
        if (kind == ManualItemKind.ASSET) {
            return switch (cls) {
                case REAL_ESTATE -> REAL_ESTATE;
                case VEHICLE -> VEHICLE;
                case JEWELLERY -> JEWELLERY;
                case PRIVATE_EQUITY -> PRIVATE_EQUITY;
                case PERSONAL_LOAN, OTHER -> OTHER_ASSET;
            };
        }
        return cls == ManualItemClass.PERSONAL_LOAN ? PERSONAL_LOAN : OTHER_LIABILITY;
    }
}
