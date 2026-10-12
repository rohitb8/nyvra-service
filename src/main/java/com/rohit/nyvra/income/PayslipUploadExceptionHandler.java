package com.rohit.nyvra.income;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.rohit.nyvra.common.exception.ApiError;

/**
 * Answers 413 and 415 for payslip uploads. It sits next to the income module and runs ahead of the global
 * handler (whose catch-all would otherwise turn these into a 500) so the shared web layer stays untouched.
 */
@RestControllerAdvice(assignableTypes = IncomeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PayslipUploadExceptionHandler {

    /**
     * Handles a payslip rejected by the service.
     *
     * @param ex  the rejection
     * @param req the request
     * @return the error response with the exception's status and code
     */
    @ExceptionHandler(PayslipUploadException.class)
    ResponseEntity<ApiError> handleRejected(PayslipUploadException ex, HttpServletRequest req) {
        return build(ex.status(), ex.getMessage(), ex.code(), req);
    }

    /**
     * Handles a file larger than the multipart limit, which Spring rejects before the service runs.
     *
     * @param ex  the size error
     * @param req the request
     * @return a 413 with code {@code PAYSLIP_TOO_LARGE}
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Payslips can be at most 5 MB", "PAYSLIP_TOO_LARGE", req);
    }

    /**
     * Builds the standard error envelope.
     *
     * @param status  HTTP status
     * @param message human-readable reason
     * @param code    stable machine-readable code
     * @param req     the request, for its path
     * @return the response
     */
    private static ResponseEntity<ApiError> build(HttpStatus status, String message, String code,
                                                  HttpServletRequest req) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, code,
            req.getRequestURI(), null, MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }
}
