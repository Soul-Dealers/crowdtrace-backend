package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitScope;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityRateLimitGuardTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final IdentityRateLimitGuard guard = new IdentityRateLimitGuard(rateLimiter);

    @Test
    void anAllowedActionProceeds() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.allow("identity-login"));

        assertThatCode(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void aDeniedActionThrows() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.deny(30, "identity-login"));

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void theSubjectSeparatesOtpPurposes() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.allow("identity-otp-send"));

        guard.check(IdentityAction.OTP_SEND, OtpType.RESET, "user@example.com");

        verify(rateLimiter).record(
                RateLimitScope.IDENTITY, "identity-otp-send", "user@example.com|RESET");
    }

    @Test
    void anUnreachableStoreRefusesRatherThanProceeding() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void aProgrammingErrorIsNotConvertedTo503() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("No rate-limit policy configured named: typo"));

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundClearsTheLoginBucket() {
        guard.refund(IdentityAction.LOGIN, "user@example.com");

        verify(rateLimiter).reset(
                RateLimitScope.IDENTITY, "identity-login", "user@example.com");
    }

    @Test
    void aRefundFailureNeverBreaksASuccessfulRequest() {
        doThrow(new DataAccessResourceFailureException("store down"))
                .when(rateLimiter).reset(any(), anyString(), anyString());

        assertThatCode(() -> guard.refund(IdentityAction.LOGIN, "user@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void isBlockedUsesPeekSoItDoesNotConsumeQuota() {
        when(rateLimiter.peek(eq(RateLimitScope.IDENTITY), eq("identity-otp-attempt"), anyString()))
                .thenReturn(RateLimitDecision.deny(30, "identity-otp-attempt"));

        assertThat(guard.isBlocked(
                IdentityAction.OTP_ATTEMPT, OtpType.CREATE, "user@example.com")).isTrue();

        verify(rateLimiter).peek(
                RateLimitScope.IDENTITY, "identity-otp-attempt", "user@example.com|CREATE");
    }
}
