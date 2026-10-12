package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.rohit.nyvra.income.dto.PayslipResponse;

/** Port for reading the salary breakup out of an uploaded payslip file. */
public interface PayslipParser {

    /**
     * Reads the fields out of a payslip.
     *
     * @param content     the file bytes; the caller closes the stream
     * @param contentType the file's media type
     * @return the extracted fields, or empty when this parser cannot read that kind of file or finds no pay
     *         figures in it
     * @throws IOException if the file cannot be read or is corrupt
     */
    Optional<PayslipResponse.ParsedFields> parse(InputStream content, String contentType) throws IOException;
}
