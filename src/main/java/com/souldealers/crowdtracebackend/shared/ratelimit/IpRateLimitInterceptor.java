package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** The coarse abuse guard. Store-connectivity failures fail open. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ClientAddressResolver clientAddressResolver;
    private final MeterRegistry meterRegistry;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        String clientAddress = clientAddressResolver.resolve(request);

        final RateLimitDecision decision;
        try {
            decision = rateLimiter.record(RateLimitScope.IP, rateLimit.value(), clientAddress);
        } catch (DataAccessException exception) {
            count(rateLimit.value(), "store_error");
            log.warn("IP rate limit unavailable, allowing request (policy={}, exceptionType={})",
                    rateLimit.value(), exception.getClass().getName());
            return true;
        }

        count(rateLimit.value(), decision.allowed() ? "allowed" : "denied");
        if (decision.denied()) {
            throw new RateLimitExceededException(decision);
        }

        return true;
    }

    private void count(String policyName, String outcome) {
        meterRegistry.counter("ratelimit.decision",
                "layer", "ip", "policy", policyName, "outcome", outcome).increment();
    }
}
