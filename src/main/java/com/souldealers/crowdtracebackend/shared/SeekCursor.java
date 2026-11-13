package com.souldealers.crowdtracebackend.shared;


import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Base64;
import java.util.Locale;

/** Opaque seek boundary (createdAt, id) for a newest-first list such as comments, follows or notifications. */
public record SeekCursor(LocalDateTime createdAt, long id) {
    public static final int MAX_TOKEN_LENGTH = 128;
    private static final String VERSION = "c1";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    public SeekCursor {
        if (createdAt == null || id <= 0 || createdAt.getYear() < 1 || createdAt.getYear() > 9999
                || createdAt.getNano() % 1_000 != 0) {
            throw invalidCursor();
        }
    }

    public String encode() {
        String payload = VERSION + "|" + TIMESTAMP_FORMAT.format(createdAt) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static SeekCursor decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidCursor();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }
            SeekCursor cursor = new SeekCursor(LocalDateTime.parse(parts[1], TIMESTAMP_FORMAT),
                    Long.parseLong(parts[2]));
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
