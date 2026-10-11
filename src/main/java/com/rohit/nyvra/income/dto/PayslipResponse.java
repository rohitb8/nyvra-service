package com.rohit.nyvra.income.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.PayslipDocument;
import com.rohit.nyvra.income.PayslipParseStatus;

/**
 * API view of an uploaded payslip: file metadata plus, once parsing succeeded, the extracted fields. The file
 * itself is not served back in v1.
 *
 * @param id            payslip id
 * @param incomeEntryId the entry it belongs to
 * @param fileName      the uploaded file's name
 * @param contentType   {@code application/pdf}, {@code image/png} or {@code image/jpeg}
 * @param sizeBytes     file size in bytes
 * @param uploadedAt    upload time
 * @param parseStatus   where parsing stands
 * @param parsedFields  extracted fields; omitted from the JSON until {@code parseStatus} is {@code PARSED}
 */
public record PayslipResponse(
    UUID id,
    UUID incomeEntryId,
    String fileName,
    String contentType,
    long sizeBytes,
    Instant uploadedAt,
    PayslipParseStatus parseStatus,
    @JsonInclude(JsonInclude.Include.NON_NULL) ParsedFields parsedFields) {

    /**
     * Fields read from the payslip; any the parser could not read are {@code null} and omitted from the JSON.
     *
     * @param employer       employer name
     * @param payPeriodStart first day of the pay period
     * @param payPeriodEnd   last day of the pay period
     * @param grossAmount    gross pay
     * @param netAmount      net pay
     * @param deductions     itemised deductions
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParsedFields(
        String employer,
        LocalDate payPeriodStart,
        LocalDate payPeriodEnd,
        MoneyDto grossAmount,
        MoneyDto netAmount,
        List<Deduction> deductions) {
    }

    /**
     * One deduction line on the payslip.
     *
     * @param label  what was deducted, for example "Provident fund"
     * @param amount the amount deducted
     */
    public record Deduction(String label, MoneyDto amount) {
    }

    /**
     * Maps a stored payslip to its response.
     *
     * @param document     the payslip
     * @param parsedFields the decoded parsed fields, or {@code null} when none are available
     * @return the response
     */
    public static PayslipResponse from(PayslipDocument document, ParsedFields parsedFields) {
        return new PayslipResponse(document.getId(), document.getIncomeEntryId(), document.getFileName(),
            document.getContentType(), document.getSizeBytes(), document.getUploadedAt(),
            document.getParseStatus(), parsedFields);
    }
}
