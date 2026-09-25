package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private static final int MIN_KEY_BYTES = 32;

    /**
     * Test-profile affordance only. The test suite runs on H2, which cannot execute
     * the PostgreSQL upsert this limiter is built on, so the whole mechanism is
     * disabled there and the rate-limit tests opt back in against Testcontainers.
     * Never set false in dev or prod.
     */
    private boolean enabled = true;

    private byte[] hmacKey;

    private Map<String, Policy> policies = Map.of();

    /** A named limit. {@code limit} requests per {@code window}. */
    public record Policy(int limit, Duration window) {}

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = Map.copyOf(policies);
    }

    public void setHmacSecret(String encodedSecret) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "rate-limit.hmac-secret must be valid Base64", exception);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "rate-limit.hmac-secret must decode to at least 32 bytes");
        }

        this.hmacKey = decoded.clone();
    }

    @PostConstruct
    void requireConfigured() {
        if (hmacKey == null) {
            throw new IllegalStateException("rate-limit.hmac-secret must be configured");
        }
        if (policies.isEmpty()) {
            throw new IllegalStateException("rate-limit.policies must not be empty");
        }
    }

    public byte[] hmacKey() {
        if (hmacKey == null) {
            throw new IllegalStateException("rate-limit.hmac-secret must be configured");
        }
        return hmacKey.clone();
    }

    /**
     * Fails fast rather than defaulting. A typo in a policy name must not
     * silently turn a security control into "no limit".
     */
    public Policy policy(String name) {
        Policy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalStateException("No rate-limit policy configured named: " + name);
        }
        return policy;
    }
}
