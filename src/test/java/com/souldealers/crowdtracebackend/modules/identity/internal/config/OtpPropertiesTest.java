package com.souldealers.crowdtracebackend.modules.identity.internal.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpPropertiesTest {

    @Test
    void rejectsMissingKeyAtStartupValidation() {
        OtpProperties properties = new OtpProperties();

        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp.hmac-secret");
    }

    @Test
    void rejectsMalformedBase64() {
        OtpProperties properties = new OtpProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("not base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void rejectsKeysShorterThanThirtyTwoBytes() {
        OtpProperties properties = new OtpProperties();
        String shortKey = Base64.getEncoder().encodeToString(
                "too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> properties.setHmacSecret(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void returnsADefensiveCopyOfAValidKey() {
        OtpProperties properties = new OtpProperties();
        String encoded = Base64.getEncoder().encodeToString(new byte[32]);
        properties.setHmacSecret(encoded);

        byte[] first = properties.hmacKey();
        first[0] = 1;

        assertThat(properties.hmacKey()[0]).isZero();
    }
}
