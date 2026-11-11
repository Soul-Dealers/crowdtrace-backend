package com.souldealers.crowdtracebackend.modules.casefile.internal.files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseFilePropertiesTest {
    @Test
    void defaults() {
        CaseFileProperties properties = new CaseFileProperties();
        assertThat(properties.getMaxSizeBytes()).isEqualTo(10_485_760L);
        assertThat(properties.getMaxPhotosPerCase()).isEqualTo(5);
    }
}
