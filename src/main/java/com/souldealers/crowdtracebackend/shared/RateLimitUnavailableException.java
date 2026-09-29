package com.souldealers.crowdtracebackend.shared;

/**
 * Thrown when a fail-closed limiter cannot reach its store. Rendered as 503,
 * deliberately not 429 — the caller did nothing wrong.
 */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String message) {
        super(message);
    }

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
