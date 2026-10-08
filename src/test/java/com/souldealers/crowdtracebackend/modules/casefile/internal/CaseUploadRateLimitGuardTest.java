package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaseUploadRateLimitGuardTest {
    private final RateLimiter limiter = mock(RateLimiter.class);
    private final CaseUploadRateLimitGuard guard = new CaseUploadRateLimitGuard(limiter);
    private final Instant window = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    void returnsTheChargedWindowForAnAllowedUpload() {
        RateLimitDecision reservation = new RateLimitDecision(true, 0, "user-case-upload", window);
        when(limiter.record(RateLimitScope.USER, "user-case-upload", "42")).thenReturn(reservation);
        assertThat(guard.check(42L)).isEqualTo(reservation);
    }

    @Test
    void denialRefundsItsChargeAndReturns429() {
        RateLimitDecision denied = new RateLimitDecision(false, 60, "user-case-upload", window);
        when(limiter.record(RateLimitScope.USER, "user-case-upload", "42")).thenReturn(denied);
        assertThatThrownBy(() -> guard.check(42L)).isInstanceOf(RateLimitExceededException.class);
        verify(limiter, times(1)).release(RateLimitScope.USER, "user-case-upload", "42", window);
    }

    @Test
    void anUnavailableStoreReturns503() {
        when(limiter.record(RateLimitScope.USER, "user-case-upload", "42"))
                .thenThrow(new DataAccessResourceFailureException("store down"));
        assertThatThrownBy(() -> guard.check(42L)).isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void refundReleasesOnlyTheOriginalReservation() {
        guard.refund(42L, new RateLimitDecision(true, 0, "user-case-upload", window));
        verify(limiter, times(1)).release(RateLimitScope.USER, "user-case-upload", "42", window);
        verify(limiter, never()).reset(any(), anyString(), anyString());
    }

    @Test
    void aRefundStoreFailureReturns503() {
        doThrow(new DataAccessResourceFailureException("store down")).when(limiter)
                .release(RateLimitScope.USER, "user-case-upload", "42", window);
        assertThatThrownBy(() -> guard.refund(42L,
                new RateLimitDecision(true, 0, "user-case-upload", window)))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void aDenialRefundFailureReturns503() {
        when(limiter.record(RateLimitScope.USER, "user-case-upload", "42"))
                .thenReturn(new RateLimitDecision(false, 60, "user-case-upload", window));
        doThrow(new DataAccessResourceFailureException("store down")).when(limiter)
                .release(RateLimitScope.USER, "user-case-upload", "42", window);
        assertThatThrownBy(() -> guard.check(42L)).isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void programmingErrorsAreNotDisguisedAsStoreFailures() {
        when(limiter.record(RateLimitScope.USER, "user-case-upload", "42"))
                .thenThrow(new IllegalStateException("missing policy"));
        assertThatThrownBy(() -> guard.check(42L)).isInstanceOf(IllegalStateException.class);
    }
}
