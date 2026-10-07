package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void generatedReportKeyMatchesFormat() {
        assertThat(StorageKey.generate(CaseFilePurpose.REPORT).value())
                .matches("^reports/[0-9a-f-]{36}$");
    }

    @Test
    void generatedPhotoKeyUsesPhotoPrefix() {
        assertThat(StorageKey.generate(CaseFilePurpose.PHOTO).value())
                .matches("^photos/[0-9a-f-]{36}$");
    }

    @Test
    void generatedKeysAreUnique() {
        assertThat(StorageKey.generate(CaseFilePurpose.REPORT))
                .isNotEqualTo(StorageKey.generate(CaseFilePurpose.REPORT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd",
            "reports/../x",
            "/reports/",
            "reports/.pdf",
            "photos//x",
            "reports/",
            "",
            "   "})
    @NullSource
    void constructorRejectsMalformedKeys(String value) {
        assertThatThrownBy(() -> new StorageKey(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsOversizedKey() {
        assertThatThrownBy(() -> new StorageKey("a".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseAcceptsMatchingPurpose() {
        assertThat(StorageKey.parse("reports/" + ID, CaseFilePurpose.REPORT).value())
                .isEqualTo("reports/" + ID);
    }

    @Test
    void parseRejectsPrefixOfAnotherPurpose() {
        assertThatThrownBy(() -> StorageKey.parse("photos/" + ID, CaseFilePurpose.REPORT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringDoesNotLeakTheKey() {
        StorageKey key = StorageKey.generate(CaseFilePurpose.REPORT);
        String id = key.value().substring(key.value().indexOf('/') + 1);
        assertThat(key.toString()).doesNotContain(id);
        assertThat(UUID.fromString(id)).isNotNull();
    }
}
