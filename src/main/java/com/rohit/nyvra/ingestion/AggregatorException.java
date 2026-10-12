package com.rohit.nyvra.ingestion;

/** Raised by an {@link AggregatorGateway} when the Account Aggregator rejects or fails a call. */
public class AggregatorException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message safe human-readable reason; stored on a failed fetch, so it must hold no personal data
     */
    public AggregatorException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message safe human-readable reason
     * @param cause   the underlying failure
     */
    public AggregatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
