package com.souldealers.crowdtracebackend.shared.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies the coarse IP-layer limit to a controller method.
 *
 * <p>Carries a policy NAME, not numbers: the limits live in {@code rate-limit.policies}
 * so they can be tuned without a deploy, while the endpoint still shows at a glance
 * that it is protected and by which policy.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** A key in {@code rate-limit.policies}, e.g. {@code "ip-login"}. */
    String value();
}
