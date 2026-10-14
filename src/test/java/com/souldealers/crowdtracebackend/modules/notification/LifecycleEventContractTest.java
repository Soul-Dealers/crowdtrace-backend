package com.souldealers.crowdtracebackend.modules.notification;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleEventContractTest {
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 14, 12, 30);

    @Test
    void eventRecordsHaveExactlyTheSpecifiedComponents() {
        assertComponents(CaseStatusChangedEvent.class,
                "eventId", "caseId", "reporterId", "fromStatus", "toStatus", "actorKind", "occurredAt");
        assertComponents(CasePhotosAddedEvent.class, "eventId", "caseId", "fileIds", "occurredAt");
        assertComponents(CaseTakenDownEvent.class, "eventId", "caseId", "reporterId", "occurredAt");
        assertComponents(CaseRestoredEvent.class, "eventId", "caseId", "reporterId", "occurredAt");
    }

    @Test
    void factoriesUseStableEventIdsDerivedFromAuditIds() {
        assertThat(CaseStatusChangedEvent.of(3L, 2L, 4L, NotifiedCaseStatus.MISSING,
                NotifiedCaseStatus.FOUND_SAFE, CaseStatusChangedEvent.ActorKind.REPORTER, OCCURRED_AT)
                .eventId()).isEqualTo("case-status:3");
        assertThat(CasePhotosAddedEvent.of(1L, 2L, List.of(4L, 5L), OCCURRED_AT).eventId())
                .isEqualTo("case-photos:1");
        assertThat(CaseTakenDownEvent.of(1L, 2L, 3L, OCCURRED_AT).eventId())
                .isEqualTo("case-takedown:1");
        assertThat(CaseRestoredEvent.of(1L, 2L, 3L, OCCURRED_AT).eventId())
                .isEqualTo("case-restore:1");
    }

    @Test
    void photoIdsAreCopiedIntoAnImmutableList() {
        var fileIds = new java.util.ArrayList<>(List.of(4L, 5L));

        var event = CasePhotosAddedEvent.of(1L, 2L, fileIds, OCCURRED_AT);
        fileIds.add(6L);

        assertThat(event.fileIds()).containsExactly(4L, 5L);
        assertThatThrownBy(() -> event.fileIds().add(7L)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertComponents(Class<?> eventType, String... expected) {
        assertThat(Arrays.stream(eventType.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly(expected);
    }
}
