package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IpRateLimitInterceptorTest {

    static class AnnotatedHandler {
        @RateLimit("ip-login")
        public void limited() {}

        public void unlimited() {}
    }

    private HandlerMethod handlerFor(String methodName) throws Exception {
        Method method = AnnotatedHandler.class.getMethod(methodName);
        return new HandlerMethod(new AnnotatedHandler(), method);
    }

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final IpRateLimitInterceptor interceptor =
            new IpRateLimitInterceptor(
                    rateLimiter, new ClientAddressResolver(), new SimpleMeterRegistry());

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void anUnannotatedHandlerIsNotCharged() throws Exception {
        interceptor.preHandle(request(), new MockHttpServletResponse(), handlerFor("unlimited"));

        verify(rateLimiter, never()).record(any(), anyString(), anyString());
    }

    @Test
    void aNonHandlerMethodIsNotCharged() throws Exception {
        assertThat(interceptor.preHandle(
                request(), new MockHttpServletResponse(), new Object())).isTrue();

        verify(rateLimiter, never()).record(any(), anyString(), anyString());
    }

    @Test
    void anAllowedRequestProceeds() throws Exception {
        when(rateLimiter.record(RateLimitScope.IP, "ip-login", "203.0.113.7"))
                .thenReturn(RateLimitDecision.allow("ip-login"));

        assertThat(interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited"))).isTrue();
    }

    @Test
    void aDeniedRequestThrows() throws Exception {
        when(rateLimiter.record(RateLimitScope.IP, "ip-login", "203.0.113.7"))
                .thenReturn(RateLimitDecision.deny(30, "ip-login"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void anUnreachableStoreLetsTheRequestThrough() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        assertThatCode(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .doesNotThrowAnyException();
    }

    @Test
    void aProgrammingErrorIsNotSwallowed() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("No rate-limit policy configured named: typo"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .isInstanceOf(IllegalStateException.class);
    }
}
