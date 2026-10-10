package com.souldealers.crowdtracebackend.shared.audit.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditMetadataSerializerTest {

    @Test
    void acceptsAsciiPayloadAtTheUtf8ByteLimit() {
        Map<String, Object> metadata = Map.of("payload", "a".repeat(4082));

        assertThat(AuditMetadataSerializer.serializedUtf8Length(metadata)).isEqualTo(4096);
        AuditMetadataSerializer.validateSize(metadata);
    }

    @Test
    void rejectsAsciiPayloadOneByteOverTheLimit() {
        Map<String, Object> metadata = Map.of("payload", "a".repeat(4083));

        assertThatThrownBy(() -> AuditMetadataSerializer.validateSize(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096 UTF-8 bytes");
    }

    @Test
    void measuresMultibyteCharactersAsUtf8Bytes() {
        Map<String, Object> atLimit = Map.of("payload", "€".repeat(1360));
        Map<String, Object> overLimit = Map.of("payload", "€".repeat(1361));

        assertThat(AuditMetadataSerializer.serializedUtf8Length(atLimit)).isEqualTo(4094);
        AuditMetadataSerializer.validateSize(atLimit);
        assertThatThrownBy(() -> AuditMetadataSerializer.validateSize(overLimit))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
