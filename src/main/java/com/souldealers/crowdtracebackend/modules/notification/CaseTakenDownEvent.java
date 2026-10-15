package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;

/** Id-only notification that a case was taken down, published inside the lifecycle transaction. */
public record CaseTakenDownEvent(String eventId, Long caseId, Long reporterId, LocalDateTime occurredAt) {

    public static CaseTakenDownEvent of(Long auditId, Long caseId, Long reporterId, LocalDateTime occurredAt) {
        return new CaseTakenDownEvent("case-takedown:" + auditId, caseId, reporterId, occurredAt);
    }
}
