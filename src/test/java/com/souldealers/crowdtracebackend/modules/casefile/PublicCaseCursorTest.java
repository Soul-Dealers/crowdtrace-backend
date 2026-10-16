package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicCaseCursorTest {
    @Test
    void roundTripsMicrosecondTimestampAndLongId() {
        PublicCaseCursor cursor = new PublicCaseCursor(LocalDateTime.parse("2026-10-16T12:34:56.123456"),
                9_223_372_036_854_775_000L);

        String token = cursor.encode();

        assertThat(PublicCaseCursor.decode(token)).isEqualTo(cursor);
    }

    @Test
    void roundTripsNullTimestampBoundary() {
        PublicCaseCursor cursor = new PublicCaseCursor(null, 17L);

        assertThat(PublicCaseCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void rejectsBlankMalformedOversizedAndNonCanonicalTokens() {
        for (String token : new String[]{"", " ", "not base64", "A".repeat(129), "djE6fjoxNw==",
                tokenFor("v1|2026-10-16T12:34:56.123456|+1"),
                tokenFor("v1|2026-10-16T12:34:56.123456|0001")}) {
            assertThatThrownBy(() -> PublicCaseCursor.decode(token)).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void rejectsUnsupportedVersionInvalidIdAndInvalidTimestampPrecisionOrYear() {
        for (String payload : new String[]{"v2|2026-10-16T12:34:56.123456|1", "v1|2026-10-16T12:34:56.123456|0",
                "v1|2026-10-16T12:34:56.123456|-1", "v1|2026-10-16T12:34:56.123456789|1",
                "v1|0000-01-01T00:00:00.000000|1", "v1|2026-10-16T12:34:56|1"}) {
            String token = tokenFor(payload);
            assertThatThrownBy(() -> PublicCaseCursor.decode(token))
                    .isInstanceOf(ValidationException.class);
        }
    }

    private static String tokenFor(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
