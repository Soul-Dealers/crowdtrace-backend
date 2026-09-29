package com.souldealers.crowdtracebackend.shared.ratelimit;

/**
 * The outcome of charging a bucket.
 *
 * <p>Deliberately a value, not an exception: the limiter runs in its own
 * transaction, and throwing inside it would mark that transaction rollback-only
 * and undo the very increment being recorded. Callers throw, outside the boundary.
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds, String policyName) {

    public static RateLimitDecision allow(String policyName) {
        return new RateLimitDecision(true, 0, policyName);
    }

    public static RateLimitDecision deny(long retryAfterSeconds, String policyName) {
        return new RateLimitDecision(false, retryAfterSeconds, policyName);
    }

    public boolean denied() {
        return !allowed;
    }
}
