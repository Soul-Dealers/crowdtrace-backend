package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Base64;
import java.util.Locale;

/** Opaque seek boundary for the public approved-case listing. */
public record PublicCaseCursor(LocalDateTime approvedAt, long id) {
    public static final int MAX_TOKEN_LENGTH = 128;
    private static final String VERSION = "v1";
    private static final String NULL_TIMESTAMP = "~";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    public PublicCaseCursor {
        if (id <= 0 || (approvedAt != null && (approvedAt.getYear() < 1 || approvedAt.getYear() > 9999
                || approvedAt.getNano() % 1_000 != 0))) {
            throw invalidCursor();
        }
    }

    public String encode() {
        String timestamp = approvedAt == null ? NULL_TIMESTAMP : TIMESTAMP_FORMAT.format(approvedAt);
        String payload = VERSION + "|" + timestamp + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static PublicCaseCursor decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidCursor();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            String payload = new String(bytes, StandardCharsets.UTF_8);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!canonical.equals(token)) {
                throw invalidCursor();
            }
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }
            LocalDateTime timestamp = NULL_TIMESTAMP.equals(parts[1]) ? null : LocalDateTime.parse(parts[1], TIMESTAMP_FORMAT);
            long id = Long.parseLong(parts[2]);
            PublicCaseCursor cursor = new PublicCaseCursor(timestamp, id);
            if (!cursor.encode().equals(token)) {
                throw invalidCursor();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private static ValidationException invalidCursor() {
        return new ValidationException("cursor is invalid");
    }
}
