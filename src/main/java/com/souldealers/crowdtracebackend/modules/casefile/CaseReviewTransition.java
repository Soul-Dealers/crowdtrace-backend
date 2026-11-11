package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

/**
 * The outcome of a review transition. {@code caseVersion} is the version after the change was flushed;
 * {@code decidedAt} is the single UTC instant used for {@code approved_at} on approval.
 */
public record CaseReviewTransition(Long caseId, Long reporterId, long caseVersion, LocalDateTime decidedAt) {
}
