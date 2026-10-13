package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;

import java.time.LocalDateTime;

/** One past decision for moderators. Notes are admin-only and never leave admin responses; times are UTC. */
public record CaseReviewHistoryResponse(Long reviewId, ReviewStatus decision, Long reviewerId, String reviewSource,
                                        String reviewNotes, LocalDateTime decidedAt) {}
