package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseFileTest {
    @Test
    void attachmentCannotBeChangedThroughPublicSetters() {
        assertThat(CaseFile.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("setCaseId", "setAttachedAt");
    }

    @Test
    void attachingSetsTheCaseAndAttachmentTime() {
        CaseFile file = CaseFile.builder().build();
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0);
        file.attachTo(42L, at);
        assertThat(file.getCaseId()).isEqualTo(42L);
        assertThat(file.getAttachedAt()).isEqualTo(at);
    }

    @Test
    void attachingTwiceIsRejected() {
        CaseFile file = CaseFile.builder().build();
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0);
        file.attachTo(42L, at);
        assertThatThrownBy(() -> file.attachTo(43L, at)).isInstanceOf(IllegalStateException.class);
        assertThat(file.getCaseId()).isEqualTo(42L);
        assertThat(file.getAttachedAt()).isEqualTo(at);
    }

    @Test
    void attachingWithoutACaseIsRejected() {
        CaseFile file = CaseFile.builder().build();
        assertThatThrownBy(() -> file.attachTo(null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(file.getCaseId()).isNull();
        assertThat(file.getAttachedAt()).isNull();
    }

    @Test
    void attachingWithoutATimeIsRejected() {
        CaseFile file = CaseFile.builder().build();
        assertThatThrownBy(() -> file.attachTo(42L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(file.getCaseId()).isNull();
        assertThat(file.getAttachedAt()).isNull();
    }

    @Test
    void attachingADeletedFileIsRejected() {
        CaseFile file = CaseFile.builder().deletedAt(LocalDateTime.now()).build();
        assertThatThrownBy(() -> file.attachTo(42L, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(file.getCaseId()).isNull();
        assertThat(file.getAttachedAt()).isNull();
    }
}
