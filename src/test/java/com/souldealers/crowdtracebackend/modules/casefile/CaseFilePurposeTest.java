package com.souldealers.crowdtracebackend.modules.casefile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseFilePurposeTest {
    @Test
    void reportIsPrivateAndPhotoIsPublic() {
        assertThat(CaseFilePurpose.REPORT.visibility()).isEqualTo(FileVisibility.PRIVATE);
        assertThat(CaseFilePurpose.PHOTO.visibility()).isEqualTo(FileVisibility.PUBLIC);
    }

    @Test
    void everyPurposeHasVisibilityAndPrefix() {
        for (CaseFilePurpose purpose : CaseFilePurpose.values()) {
            assertThat(purpose.visibility()).isNotNull();
            assertThat(purpose.storagePrefix()).isNotBlank();
        }
    }

    @Test
    void prefixesMatchStorageLayout() {
        assertThat(CaseFilePurpose.REPORT.storagePrefix()).isEqualTo("reports");
        assertThat(CaseFilePurpose.PHOTO.storagePrefix()).isEqualTo("photos");
    }
}
