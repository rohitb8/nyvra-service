package com.rohit.nyvra.income;

import org.springframework.http.HttpStatus;

/** Thrown when an uploaded payslip is rejected for its size or type (HTTP 413 or 415). */
public class PayslipUploadException extends RuntimeException {

    /** HTTP status to answer with. */
    private final HttpStatus status;

    /** Stable machine-readable code. */
    private final String code;

    /**
     * Creates the exception.
     *
     * @param status  {@code PAYLOAD_TOO_LARGE} or {@code UNSUPPORTED_MEDIA_TYPE}
     * @param code    stable code, for example {@code PAYSLIP_TOO_LARGE}
     * @param message human-readable reason
     */
    public PayslipUploadException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return the HTTP status to answer with */
    public HttpStatus status() {
        return status;
    }

    /** @return the stable machine-readable code */
    public String code() {
        return code;
    }
}
