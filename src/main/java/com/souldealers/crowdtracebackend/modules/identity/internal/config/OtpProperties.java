package com.souldealers.crowdtracebackend.modules.identity.internal.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@ConfigurationProperties(prefix = "otp")
public class OtpProperties {

    private static final int MIN_KEY_BYTES = 32;

    private byte[] hmacKey;

    public void setHmacSecret(String encodedSecret) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "otp.hmac-secret must be valid Base64", exception);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "otp.hmac-secret must decode to at least 32 bytes");
        }

        this.hmacKey = decoded.clone();
    }

    @PostConstruct
    void requireConfigured() {
        if (hmacKey == null) {
            throw new IllegalStateException("otp.hmac-secret must be configured");
        }
    }

    public byte[] hmacKey() {
        if (hmacKey == null) {
            throw new IllegalStateException("otp.hmac-secret must be configured");
        }
        return hmacKey.clone();
    }
}
