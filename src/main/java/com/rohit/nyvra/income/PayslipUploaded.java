package com.rohit.nyvra.income;

import java.util.UUID;

/**
 * Published when a payslip upload has been recorded; once the upload's transaction commits, the payslip is
 * parsed in the background.
 *
 * @param payslipId id of the {@code PENDING} payslip to parse
 */
public record PayslipUploaded(UUID payslipId) {
}
