package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit.IdentityAction;
import com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit.IdentityRateLimitGuard;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the asymmetry: the coarse guard degrades, the security control refuses.
 * Getting these backwards is silent — either authentication dies during a
 * limiter incident, or the control quietly disappears during one.
 */
class RateLimitStoreFailurePolicyTest {

    static class Handler {
        @RateLimit("ip-login")
        public void limited() {}
    }

    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private HandlerMethod handler() throws Exception {
        Method method = Handler.class.getMethod("limited");
        return new HandlerMethod(new Handler(), method);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void theIpLayerDegradesRatherThanBlockingEveryone() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IpRateLimitInterceptor interceptor =
                new IpRateLimitInterceptor(
                        rateLimiter, new ClientAddressResolver(), new SimpleMeterRegistry());

        assertThatCode(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handler()))
                .doesNotThrowAnyException();
    }

    @Test
    void theIdentityLayerRefusesRatherThanProceedingUnprotected() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IdentityRateLimitGuard guard =
                new IdentityRateLimitGuard(rateLimiter, new SimpleMeterRegistry());

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void theIdentityLayerNeverFallsBackToAnInProcessCount() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IdentityRateLimitGuard guard =
                new IdentityRateLimitGuard(rateLimiter, new SimpleMeterRegistry());

        // Under multi-instance deployment an in-process fallback silently turns one
        // distributed cap into one cap per replica — precisely when the system is
        // already degraded. Every call must refuse, not the first N.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                    .isInstanceOf(RateLimitUnavailableException.class);
        }
    }
}
