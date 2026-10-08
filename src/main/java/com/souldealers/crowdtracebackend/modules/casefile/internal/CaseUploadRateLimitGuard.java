package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitScope;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CaseUploadRateLimitGuard {
    private static final String POLICY = "user-case-upload";
    private final RateLimiter rateLimiter;

    public RateLimitDecision check(Long userId) {
        final RateLimitDecision reservation;
        try {
            reservation = rateLimiter.record(RateLimitScope.USER, POLICY, userId.toString());
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
        if (reservation.denied()) {
            refund(userId, reservation);
            throw new RateLimitExceededException(reservation);
        }
        return reservation;
    }

    public void refund(Long userId, RateLimitDecision reservation) {
        try {
            rateLimiter.release(RateLimitScope.USER, POLICY, userId.toString(), reservation.windowEndsAt());
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    private RateLimitUnavailableException unavailable(DataAccessException failure) {
        return new RateLimitUnavailableException("Rate limit store unavailable for " + POLICY, failure);
    }
}
