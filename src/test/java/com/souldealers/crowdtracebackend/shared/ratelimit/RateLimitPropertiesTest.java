package com.souldealers.crowdtracebackend.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    private static final String VALID_SECRET =
            "dGVzdC1yYXRlLWxpbWl0LWhtYWMtc2VjcmV0LWZvci10ZXN0cy0xMjM0";

    private RateLimitProperties configured() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setHmacSecret(VALID_SECRET);
        properties.setPolicies(Map.of(
                "identity-login", new RateLimitProperties.Policy(5, Duration.ofMinutes(5))));
        return properties;
    }

    @Test
    void rateLimitingIsEnabledUnlessExplicitlyDisabled() {
        assertThat(configured().isEnabled()).isTrue();
    }

    @Test
    void missingHmacSecretFailsStartup() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(Map.of(
                "identity-login", new RateLimitProperties.Policy(5, Duration.ofMinutes(5))));

        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate-limit.hmac-secret");
    }

    @Test
    void aShortHmacSecretIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("c2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void aNonBase64HmacSecretIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("not valid base64 !!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void anUnknownPolicyNameFailsFastRatherThanSilentlyAllowing() {
        assertThatThrownBy(() -> configured().policy("does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void aConfiguredPolicyIsReturned() {
        RateLimitProperties.Policy policy = configured().policy("identity-login");

        assertThat(policy.limit()).isEqualTo(5);
        assertThat(policy.window()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theHmacKeyIsDefensivelyCopied() {
        RateLimitProperties properties = configured();

        byte[] first = properties.hmacKey();
        first[0] = (byte) (first[0] + 1);

        assertThat(properties.hmacKey()).isNotEqualTo(first);
    }
}
