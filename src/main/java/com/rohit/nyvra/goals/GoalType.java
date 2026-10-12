package com.rohit.nyvra.goals;

/** What a goal is saving for; purely descriptive, it never changes how progress is measured. */
public enum GoalType {
    /** A cash buffer for emergencies. */
    EMERGENCY_FUND,
    /** A home purchase or down payment. */
    HOME,
    /** A vehicle purchase. */
    VEHICLE,
    /** Education fees. */
    EDUCATION,
    /** Retirement corpus. */
    RETIREMENT,
    /** A trip or holiday. */
    TRAVEL,
    /** A wedding or other celebration. */
    WEDDING,
    /** Anything else. */
    OTHER
}
