package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;

/** Id-only notification that a case was restored, published inside the lifecycle transaction. */
public record CaseRestoredEvent(String eventId, Long caseId, Long reporterId, LocalDateTime occurredAt) {

    public static CaseRestoredEvent of(Long auditId, Long caseId, Long reporterId, LocalDateTime occurredAt) {
        return new CaseRestoredEvent("case-restore:" + auditId, caseId, reporterId, occurredAt);
    }
}
