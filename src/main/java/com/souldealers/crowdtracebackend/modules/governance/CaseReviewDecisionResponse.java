package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;

import java.time.LocalDateTime;

/** The recorded decision. {@code caseVersion} is the case version after the decision; times are UTC. */
public record CaseReviewDecisionResponse(Long reviewId, Long caseId, ReviewStatus reviewStatus, long caseVersion,
                                         Long reviewerId, String reviewNotes, LocalDateTime decidedAt) {
}
