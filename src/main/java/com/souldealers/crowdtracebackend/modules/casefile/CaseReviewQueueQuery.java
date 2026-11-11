package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.shared.ValidationException;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Review queue filters; every field is optional. {@code status} narrows the queue to one pending state (both by
 * default), and the submission window is half-open, {@code [submittedFrom, submittedTo)}, in UTC.
 */
public record CaseReviewQueueQuery(ReviewStatus status, Boolean priorityMinor, Boolean duplicateFlag,
                                   LocalDateTime submittedFrom, LocalDateTime submittedTo) {
    private static final Set<ReviewStatus> PENDING = EnumSet.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW);

    public CaseReviewQueueQuery {
        if (status != null && !PENDING.contains(status)) {
            throw new ValidationException("status must be SUBMITTED or UNDER_REVIEW");
        }
        if (submittedFrom != null && submittedTo != null && !submittedFrom.isBefore(submittedTo)) {
            throw new ValidationException("submittedFrom must be before submittedTo");
        }
    }

    public static CaseReviewQueueQuery allPending() {
        return new CaseReviewQueueQuery(null, null, null, null, null);
    }

    /** The review states this query matches. */
    public Set<ReviewStatus> statuses() {
        return status == null ? EnumSet.copyOf(PENDING) : EnumSet.of(status);
    }
}
