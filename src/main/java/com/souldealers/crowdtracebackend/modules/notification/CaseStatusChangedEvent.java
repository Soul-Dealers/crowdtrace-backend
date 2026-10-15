package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;

/** Id-only status change notification published inside the lifecycle transaction. */
public record CaseStatusChangedEvent(String eventId, Long caseId, Long reporterId,
                                     NotifiedCaseStatus fromStatus, NotifiedCaseStatus toStatus,
                                     ActorKind actorKind, LocalDateTime occurredAt) {

    public enum ActorKind { REPORTER, ADMIN }

    public static CaseStatusChangedEvent of(Long auditId, Long caseId, Long reporterId,
                                            NotifiedCaseStatus fromStatus, NotifiedCaseStatus toStatus,
                                            ActorKind actorKind, LocalDateTime occurredAt) {
        return new CaseStatusChangedEvent("case-status:" + auditId, caseId, reporterId, fromStatus, toStatus,
                actorKind, occurredAt);
    }
}
