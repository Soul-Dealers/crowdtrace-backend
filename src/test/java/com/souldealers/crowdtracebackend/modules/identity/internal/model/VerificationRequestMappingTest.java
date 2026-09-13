package com.souldealers.crowdtracebackend.modules.identity.internal.model;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationRequestMappingTest {

    @Test
    void declaresThePendingQueueIndex() {
        Table table = VerificationRequest.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.indexes())
                .anySatisfy(index -> {
                    assertThat(index.name()).isEqualTo("idx_verification_requests_queue");
                    assertThat(index.columnList()).isEqualTo("status, created_at");
                });
    }
}
