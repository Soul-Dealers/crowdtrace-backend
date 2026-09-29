package com.souldealers.crowdtracebackend.shared.exception;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/reset-password");
        request.setMethod("POST");
        return request;
    }

    @Test
    void anExceededLimitBecomes429WithRetryAfter() {
        RateLimitDecision decision = RateLimitDecision.deny(42, "identity-otp-attempt");

        ResponseEntity<ProblemDetail> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(decision), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
    }

    @Test
    void the429BodyIsAProblemDetailWithAStableCode() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(RateLimitDecision.deny(42, "identity-otp-attempt")),
                request());

        ProblemDetail body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("code", "RATE_LIMIT_EXCEEDED");
        assertThat(body.getTitle()).isEqualTo("Too many requests");
        assertThat(body.getType().toString())
                .isEqualTo("urn:crowdtrace:problem:rate-limit-exceeded");
    }

    @Test
    void the429RevealsNeitherThePolicyNorItsNumbers() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(RateLimitDecision.deny(42, "identity-otp-attempt")),
                request());

        // RateLimit-Policy: 5;w=600 is the attempt cap and 3;w=3600 the send bucket,
        // so publishing them tells an attacker which control tripped.
        assertThat(response.getHeaders().headerNames())
                .noneMatch(name -> name.toLowerCase().startsWith("ratelimit-"));
        assertThat(response.getBody().getDetail()).doesNotContain("identity-otp-attempt");
    }

    @Test
    void anUnavailableStoreBecomes503NotA429() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitUnavailable(
                new RateLimitUnavailableException("store unreachable"), request());

        // The client did nothing wrong; 429 would be a lie that also trains
        // clients to back off for the wrong reason.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "RATE_LIMIT_UNAVAILABLE");
    }

    @Test
    void neitherResponseEchoesTheUnderlyingCause() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitUnavailable(
                new RateLimitUnavailableException("Connection refused to db-primary:5432"), request());

        assertThat(response.getBody().getDetail()).doesNotContain("db-primary");
    }
}
