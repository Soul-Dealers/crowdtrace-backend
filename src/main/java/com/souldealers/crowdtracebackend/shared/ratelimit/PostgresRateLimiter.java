package com.souldealers.crowdtracebackend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PostgresRateLimiter implements RateLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final RateLimitBucketRepository repository;
    private final RateLimitProperties properties;

    /**
     * REQUIRES_NEW is load-bearing, not stylistic. AuthServiceImpl.verifyOtp,
     * resetPassword and signUp are @Transactional, and a failed OTP guess throws,
     * rolling that transaction back. If the increment joined it, every failed
     * attempt would erase its own evidence and the cap would never fire.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RateLimitDecision record(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow(policyName);
        }

        RateLimitProperties.Policy policy = properties.policy(policyName);

        RateLimitBucketRepository.BucketState state = repository.charge(
                scope.name(), policyName, subjectKey(subject), policy.window().toSeconds());

        return decide(state.requestCount(), state.windowEndsAt(), policy, policyName);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RateLimitDecision peek(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow(policyName);
        }

        RateLimitProperties.Policy policy = properties.policy(policyName);

        Optional<RateLimitBucketRepository.BucketState> state =
                repository.peek(scope.name(), policyName, subjectKey(subject));

        return state
                .map(found -> decide(found.requestCount(), found.windowEndsAt(), policy, policyName))
                .orElseGet(() -> RateLimitDecision.allow(policyName));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.reset(scope.name(), policyName, subjectKey(subject));
    }

    private RateLimitDecision decide(
            int count, Instant windowEndsAt, RateLimitProperties.Policy policy, String policyName) {

        if (count <= policy.limit()) {
            return RateLimitDecision.allow(policyName);
        }

        // An expired window that peek observed before the next charge resets it
        // would otherwise yield a negative delta.
        long seconds = Duration.between(Instant.now(), windowEndsAt).toSeconds();
        return RateLimitDecision.deny(Math.max(1, seconds + 1), policyName);
    }

    /**
     * Buckets are keyed by HMAC, never by the raw address or email, so the table
     * cannot be mined for who uses the service. The key is deliberately separate
     * from otp.hmac-secret: rotating one must not affect the other.
     */
    private byte[] subjectKey(String subject) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.hmacKey(), HMAC_ALGORITHM));
            return mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Rate-limit keying is unavailable", exception);
        }
    }
}
