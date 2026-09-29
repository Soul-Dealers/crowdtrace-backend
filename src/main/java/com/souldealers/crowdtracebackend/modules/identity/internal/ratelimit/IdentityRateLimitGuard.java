package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitScope;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** The identity-keyed control. Store failures fail closed with 503. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityRateLimitGuard {

    private final RateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public void check(IdentityAction action, String email) {
        enforce(action, subject(email, null));
    }

    public void check(IdentityAction action, OtpType purpose, String email) {
        enforce(action, subject(email, purpose));
    }

    public void refund(IdentityAction action, String email) {
        release(action, subject(email, null));
    }

    public void refund(IdentityAction action, OtpType purpose, String email) {
        release(action, subject(email, purpose));
    }

    public boolean isBlocked(IdentityAction action, OtpType purpose, String email) {
        try {
            return rateLimiter
                    .peek(RateLimitScope.IDENTITY, action.policyName(), subject(email, purpose))
                    .denied();
        } catch (DataAccessException exception) {
            throw unavailable(action, exception);
        }
    }

    private void enforce(IdentityAction action, String subject) {
        final RateLimitDecision decision;
        try {
            decision = rateLimiter.record(RateLimitScope.IDENTITY, action.policyName(), subject);
        } catch (DataAccessException exception) {
            throw unavailable(action, exception);
        }

        count(action, decision.allowed() ? "allowed" : "denied");
        if (decision.denied()) {
            throw new RateLimitExceededException(decision);
        }
    }

    /** A successful request must not fail merely because refunding the counter failed. */
    private void release(IdentityAction action, String subject) {
        try {
            rateLimiter.reset(RateLimitScope.IDENTITY, action.policyName(), subject);
        } catch (DataAccessException exception) {
            log.warn("Could not refund rate limit (policy={}, exceptionType={})",
                    action.policyName(), exception.getClass().getName());
        }
    }

    private RateLimitUnavailableException unavailable(
            IdentityAction action, DataAccessException exception) {
        count(action, "store_error");
        log.error("Identity rate limit unavailable, refusing request "
                        + "(policy={}, exceptionType={})",
                action.policyName(), exception.getClass().getName());

        return new RateLimitUnavailableException(
                "Rate limit store unavailable for " + action.policyName(), exception);
    }

    private void count(IdentityAction action, String outcome) {
        meterRegistry.counter("ratelimit.decision",
                "layer", "identity", "policy", action.policyName(), "outcome", outcome)
                .increment();
    }

    /** Never log this value: it is the raw email. */
    private String subject(String email, OtpType purpose) {
        return purpose == null ? email : email + "|" + purpose.name();
    }
}
