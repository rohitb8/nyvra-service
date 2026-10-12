package com.rohit.nyvra.networth;

/** Category of a hand-maintained entry; stored as {@code TEXT + CHECK} in {@code manual_asset_liability.class}. */
public enum ManualItemClass {
    /** Land or buildings. */
    REAL_ESTATE,
    /** Cars, bikes and other vehicles. */
    VEHICLE,
    /** Gold and other jewellery held physically. */
    JEWELLERY,
    /** Unlisted equity, such as a stake in a private company. */
    PRIVATE_EQUITY,
    /** A loan from or to a person rather than a bank. */
    PERSONAL_LOAN,
    /** Anything that fits none of the above. */
    OTHER
}
