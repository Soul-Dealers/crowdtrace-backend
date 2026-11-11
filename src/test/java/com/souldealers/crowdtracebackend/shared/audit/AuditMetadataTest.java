package com.souldealers.crowdtracebackend.shared.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditMetadataTest {

    @Test
    void acceptsEachDeclaredValueShape() {
        assertThat(AuditMetadata.normalizeValue("id", AuditValueType.ID, 1L)).isEqualTo(1L);
        assertThat(AuditMetadata.normalizeValue("id", AuditValueType.ID, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(AuditMetadata.normalizeValue("count", AuditValueType.COUNT, 0)).isEqualTo(0);
        assertThat(AuditMetadata.normalizeValue("count", AuditValueType.COUNT, 3)).isEqualTo(3);
        assertThat(AuditMetadata.normalizeValue("flag", AuditValueType.FLAG, true)).isEqualTo(true);
        assertThat(AuditMetadata.normalizeValue("flag", AuditValueType.FLAG, false)).isEqualTo(false);
        assertThat(AuditMetadata.normalizeValue("code", AuditValueType.code(AuditVerificationType.class),
                AuditVerificationType.NGO)).isEqualTo("NGO");
    }

    @Test
    void rejectsValuesOutsideEachDeclaredValueShape() {
        assertNormalizationInvalid("id", AuditValueType.ID, null);
        assertNormalizationInvalid("id", AuditValueType.ID, 0L);
        assertNormalizationInvalid("id", AuditValueType.ID, -1L);
        assertNormalizationInvalid("id", AuditValueType.ID, 1);
        assertNormalizationInvalid("id", AuditValueType.ID, 1.0f);
        assertNormalizationInvalid("id", AuditValueType.ID, BigInteger.ONE);
        assertNormalizationInvalid("count", AuditValueType.COUNT, null);
        assertNormalizationInvalid("count", AuditValueType.COUNT, -1);
        assertNormalizationInvalid("count", AuditValueType.COUNT, 1L);
        assertNormalizationInvalid("flag", AuditValueType.FLAG, null);
        assertNormalizationInvalid("flag", AuditValueType.FLAG, "true");
        assertNormalizationInvalid("flag", AuditValueType.FLAG, 1);
        assertNormalizationInvalid("code", AuditValueType.code(AuditVerificationType.class), null);
        assertNormalizationInvalid("code", AuditValueType.code(AuditVerificationType.class), OtherVerificationType.POLICE);
    }

    @Test
    void rejectsInvalidIdAndCountValues() {
        assertInvalid("reviewId", () -> AuditMetadata.of(AuditAction.CASE_APPROVED)
                .put("reviewId", -1L).build());
        assertInvalid("resolvedReports", () -> AuditMetadata.of(AuditAction.COMMENT_REMOVED)
                .put("resolvedReports", -1).build());
        assertInvalid("reviewId", () -> AuditMetadata.of(AuditAction.CASE_APPROVED)
                .put("reviewId", 1).build());
    }

    @Test
    void rejectsWrongEnumClassAndFreeFormValues() {
        assertInvalid("verificationType", () -> verificationMetadata(OtherVerificationType.POLICE).build());
        assertInvalid("verificationType", () -> verificationMetadata("secret-value").build());
        assertThatThrownBy(() -> AuditMetadata.normalizeValue("flag", AuditValueType.FLAG, "secret-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flag")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void rejectsNestedValuesNullsAndUnknownKeys() {
        assertInvalid("verificationType", () -> verificationMetadata(Map.of("nested", "value")).build());
        assertInvalid("verificationType", () -> verificationMetadata(List.of("value")).build());
        assertInvalid("verificationType", () -> verificationMetadata(null).build());
        assertInvalid("freeText", () -> AuditMetadata.of(AuditAction.USER_DEACTIVATED)
                .put("freeText", "secret-value").build());
    }

    @Test
    void requiresEveryRequiredKeyAndAllowsTheDeclaredOptionalKey() {
        assertInvalid("userId", () -> AuditMetadata.of(AuditAction.VERIFICATION_APPROVED)
                .put("verificationType", AuditVerificationType.NGO).build());

        AuditMetadata corrected = AuditMetadata.of(AuditAction.AUDIT_EVENT_CORRECTED)
                .put("reason", AuditCorrectionReason.WRONG_TARGET).build();

        assertThat(corrected.values()).containsEntry("reason", "WRONG_TARGET");
    }

    @Test
    void metadataKeepsItsActionAndCopiesSourceValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("userId", 7L);
        source.put("verificationType", AuditVerificationType.NGO);

        AuditMetadata metadata = AuditMetadata.of(AuditAction.VERIFICATION_APPROVED).putAll(source).build();
        source.put("userId", 99L);
        source.remove("verificationType");

        assertThat(metadata.action()).isEqualTo(AuditAction.VERIFICATION_APPROVED);
        assertThat(metadata.values()).containsEntry("userId", 7L).containsEntry("verificationType", "NGO");
        assertThatThrownBy(() -> metadata.values().put("userId", 10L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiedMetadataDoesNotExposeMutableNestedInput() {
        List<String> values = new ArrayList<>(List.of("secret-value"));
        assertInvalid("verificationType", () -> verificationMetadata(values).build());
    }

    private static AuditMetadata.Builder verificationMetadata(Object value) {
        return AuditMetadata.of(AuditAction.VERIFICATION_APPROVED)
                .put("userId", 7L)
                .put("verificationType", value);
    }

    private static void assertInvalid(String key, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key)
                .hasMessageNotContaining("secret-value");
    }

    private static void assertNormalizationInvalid(String key, AuditValueType type, Object value) {
        assertThatThrownBy(() -> AuditMetadata.normalizeValue(key, type, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    private enum OtherVerificationType {
        POLICE
    }
}
