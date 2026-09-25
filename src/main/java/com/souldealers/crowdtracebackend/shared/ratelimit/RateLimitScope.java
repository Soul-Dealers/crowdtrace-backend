package com.souldealers.crowdtracebackend.shared.ratelimit;

/** Which keying layer a bucket belongs to. Persisted as the {@code scope} column. */
public enum RateLimitScope {
    /** Coarse abuse guard, keyed on client address. Fails open. */
    IP,
    /** The security control, keyed on the normalised identifier. Fails closed. */
    IDENTITY
}
