package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;

/** Moderator review decisions. {@code actorEmail} must come from the authenticated principal. */
public interface ReviewService {

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
