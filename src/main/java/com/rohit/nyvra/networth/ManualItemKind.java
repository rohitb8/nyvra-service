package com.rohit.nyvra.networth;

/** Whether a hand-maintained entry adds to assets or to liabilities; stored as {@code TEXT + CHECK} (V11). */
public enum ManualItemKind {
    /** Something the user owns, such as a flat or a car. */
    ASSET,
    /** Something the user owes, such as a private loan. */
    LIABILITY
}
