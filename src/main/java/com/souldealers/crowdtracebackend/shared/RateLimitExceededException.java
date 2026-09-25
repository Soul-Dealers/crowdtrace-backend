package com.souldealers.crowdtracebackend.shared;

import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;

/** Thrown when a caller exceeded a configured limit. Rendered as 429. */
public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(CustomMessages.RATE_LIMIT_EXCEEDED_MSG);
        this.decision = decision;
    }

    public long retryAfterSeconds() {
        return decision.retryAfterSeconds();
    }

    /** Safe to log, never to return: it identifies which control tripped. */
    public String policyName() {
        return decision.policyName();
    }
}
