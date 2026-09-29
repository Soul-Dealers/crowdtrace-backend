package com.souldealers.crowdtracebackend.shared.ratelimit;

/**
 * Charges and inspects rate-limit buckets.
 *
 * <p>Every method returns or completes normally; none throws on denial. The
 * implementation runs in its own transaction, and throwing inside it would mark
 * that transaction rollback-only and undo the increment it just recorded.
 * Deciding what a denial means — 429, 503, or proceed — belongs to the caller.
 *
 * <p>Store failures DO propagate as {@link org.springframework.dao.DataAccessException},
 * because fail-open and fail-closed are caller decisions: the IP layer continues,
 * the identity layer refuses.
 */
public interface RateLimiter {

    /** Increments the bucket and reports whether this request is within the limit. */
    RateLimitDecision record(RateLimitScope scope, String policyName, String subject);

    /** Reports the bucket's current state without charging it. */
    RateLimitDecision peek(RateLimitScope scope, String policyName, String subject);

    /** Clears the bucket, refunding everything charged in the current window. */
    void reset(RateLimitScope scope, String policyName, String subject);
}
