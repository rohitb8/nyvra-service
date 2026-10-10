package com.rohit.nyvra.income;

/** Where parsing of an uploaded payslip stands; stored as {@code TEXT + CHECK} (V17__payslip_metadata.sql). */
public enum PayslipParseStatus {
    /** Uploaded and waiting for the parser; the state every payslip starts in. */
    PENDING,
    /** Parsed; the extracted fields are available. */
    PARSED,
    /** The parser could not read the file. */
    FAILED
}
