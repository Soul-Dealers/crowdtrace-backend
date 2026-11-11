package com.souldealers.crowdtracebackend.shared.audit.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** JSON encoding and Java-side size check for audit metadata. */
public final class AuditMetadataSerializer {
    public static final int MAX_UTF8_BYTES = 4096;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AuditMetadataSerializer() { }

    public static String serialize(Map<String, ?> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Audit metadata could not be serialized", exception);
        }
    }

    public static int serializedUtf8Length(Map<String, ?> metadata) {
        return serialize(metadata).getBytes(StandardCharsets.UTF_8).length;
    }

    public static void validateSize(Map<String, ?> metadata) {
        if (serializedUtf8Length(metadata) > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Serialized audit metadata exceeds 4096 UTF-8 bytes");
        }
    }
}
