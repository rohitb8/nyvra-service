package com.rohit.nyvra.analytics;

import com.fasterxml.jackson.annotation.JsonValue;

/** The six health-score components, in the spec's reporting order. */
public enum SubScoreKey {

    /** Savings rate (spec §3.1). */
    SAVINGS("savings"),
    /** Essential and discretionary spend ratios (spec §3.2). */
    SPENDING_CONTROL("spendingControl"),
    /** Months of essential burn covered by liquid assets (spec §3.3). */
    EMERGENCY_FUND("emergencyFund"),
    /** Debt load and quality (spec §3.4). */
    DEBT("debt"),
    /** Allocation and concentration (spec §3.5). */
    DIVERSIFICATION("diversification"),
    /** Progress on user-defined goals (spec §3.6). */
    GOAL_PROGRESS("goalProgress");

    /** Name used in JSON and in {@code topDrivers}. */
    private final String json;

    /**
     * Creates a key.
     *
     * @param json its JSON name
     */
    SubScoreKey(String json) {
        this.json = json;
    }

    /** @return the camelCase JSON name of this sub-score */
    @JsonValue
    public String json() {
        return json;
    }

    /**
     * Resolves a key from its JSON name.
     *
     * @param json the name as stored or serialised
     * @return the matching key
     * @throws IllegalArgumentException if no key has that name
     */
    public static SubScoreKey fromJson(String json) {
        for (SubScoreKey key : values()) {
            if (key.json.equals(json)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown sub-score: " + json);
    }
}
