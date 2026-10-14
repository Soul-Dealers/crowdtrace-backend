package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewQueueQuery;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;

import java.time.LocalDateTime;

/**
 * HTTP queue filters; every field is optional. {@code status} is SUBMITTED or UNDER_REVIEW (both by default) and
 * the submission window is {@code [submittedFrom, submittedTo)} in UTC. Pages are offset-based, so a case decided
 * between two requests can shift what the next page holds.
 */
public record ReviewQueueFilter(ReviewStatus status, Boolean priorityMinor, Boolean duplicateFlag,
                                LocalDateTime submittedFrom, LocalDateTime submittedTo) {

    /** Translates into casefile's own query type, so casefile never depends on governance. */
    public CaseReviewQueueQuery toQuery() {
        return new CaseReviewQueueQuery(status, priorityMinor, duplicateFlag, submittedFrom, submittedTo);
    }
}
