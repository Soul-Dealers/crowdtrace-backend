package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;
import java.util.List;

/** Id-only notification for live case photos, published inside the attachment transaction. */
public record CasePhotosAddedEvent(String eventId, Long caseId, List<Long> fileIds, LocalDateTime occurredAt) {

    public CasePhotosAddedEvent {
        fileIds = List.copyOf(fileIds);
    }

    public static CasePhotosAddedEvent of(Long firstPhotoAuditId, Long caseId, List<Long> fileIds,
                                          LocalDateTime occurredAt) {
        return new CasePhotosAddedEvent("case-photos:" + firstPhotoAuditId, caseId, fileIds, occurredAt);
    }
}
