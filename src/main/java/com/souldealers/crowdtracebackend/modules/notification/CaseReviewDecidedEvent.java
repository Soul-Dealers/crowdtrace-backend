package com.souldealers.crowdtracebackend.modules.notification;

import java.time.LocalDateTime;

/**
 * A moderator decided a reporter's case. Published inside the decision's transaction, so after-commit consumers
 * only see committed decisions. Ids only: no notes, contact details or evidence. {@code eventId} is stable per
 * review for deduplication; {@code occurredAt} is the UTC decision instant.
 */
public record CaseReviewDecidedEvent(String eventId, Long recipientId, Long caseId, Long reviewId,
                                     Decision decision, LocalDateTime occurredAt) {

    public enum Decision { APPROVED, REJECTED }

    public static CaseReviewDecidedEvent of(Long recipientId, Long caseId, Long reviewId, Decision decision,
                                            LocalDateTime occurredAt) {
        return new CaseReviewDecidedEvent("case-review:" + reviewId, recipientId, caseId, reviewId, decision,
                occurredAt);
    }
}
