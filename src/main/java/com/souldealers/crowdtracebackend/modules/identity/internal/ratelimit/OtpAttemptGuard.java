package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Applies the identity-keyed OTP guess cap and burns codes after lockout. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpAttemptGuard {

    private final IdentityRateLimitGuard identityRateLimitGuard;
    private final OtpRepository otpRepository;

    public void beforeAttempt(OtpType purpose, String email) {
        try {
            identityRateLimitGuard.check(IdentityAction.OTP_ATTEMPT, purpose, email);
        } catch (RateLimitExceededException exception) {
            burnOutstandingCode(purpose, email);
            throw exception;
        }
    }

    public void afterSuccess(OtpType purpose, String email) {
        identityRateLimitGuard.refund(IdentityAction.OTP_ATTEMPT, purpose, email);
    }

    public void requireNotBlocked(OtpType purpose, String email) {
        if (identityRateLimitGuard.isBlocked(IdentityAction.OTP_ATTEMPT, purpose, email)) {
            identityRateLimitGuard.check(IdentityAction.OTP_ATTEMPT, purpose, email);
        }
    }

    /** Runs independently because the caller is about to throw and roll back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void burnOutstandingCode(OtpType purpose, String email) {
        int burned = otpRepository.expireActive(email, purpose, LocalDateTime.now());
        if (burned > 0) {
            log.warn("OTP attempt cap exceeded; invalidated {} outstanding code(s) (purpose={})",
                    burned, purpose);
        }
    }
}
