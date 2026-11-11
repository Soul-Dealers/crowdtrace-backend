package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

/** Moderator review queue, detail and decisions. {@code actorEmail} must come from the authenticated principal. */
public interface ReviewService {

    /**
     * Pending cases, minors first, then oldest submission, then id. Client sorting is ignored; size defaults to 20
     * and is capped at 50. A null filter means no filter.
     *
     * @throws com.souldealers.crowdtracebackend.shared.ValidationException a decided status or an empty window
     */
    @RequiresModerator
    PagedResponse<AdminCaseSummaryResponse> listQueue(ReviewQueueFilter filter, Pageable pageable);

    /**
     * The admin projection, version, evidence metadata, duplicate matches and review history, all read in one
     * REPEATABLE READ snapshot so a concurrent decision never shows half-applied.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown case
     */
    @RequiresModerator
    ReviewCaseDetailResponse getCaseDetail(Long caseId);

    /**
     * Approves a pending case, appends its review, audits it and notifies the reporter, all in one transaction.
     *
     * @throws com.souldealers.crowdtracebackend.shared.ValidationException invalid request
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown case
     * @throws org.springframework.security.access.AccessDeniedException the actor reported the case
     * @throws com.souldealers.crowdtracebackend.shared.ConflictException not pending, stale version or no report
     */
    @RequiresModerator
    CaseReviewDecisionResponse approve(String actorEmail, Long caseId, ApproveCaseRequest request);

    /** Rejects a pending case; same transaction and errors as {@link #approve}, but notes are required. */
    @RequiresModerator
    CaseReviewDecisionResponse reject(String actorEmail, Long caseId, RejectCaseRequest request);
}
