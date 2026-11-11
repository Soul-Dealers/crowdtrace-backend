package com.souldealers.crowdtracebackend.modules.casefile;

/**
 * Moves a pending case to its review outcome. Callers must already be in a transaction; the case stays
 * locked until that transaction ends, so the caller can append its review record under the same lock.
 */
public interface CaseReviewMutationService {
    /**
     * Locks the case and applies the decision.
     *
     * @throws com.souldealers.crowdtracebackend.shared.NotFoundException unknown case
     * @throws org.springframework.security.access.AccessDeniedException the reviewer reported the case
     * @throws com.souldealers.crowdtracebackend.shared.ConflictException the case is not pending, its version is
     *         not {@code expectedCaseVersion}, or an approval finds no live report file
     */
    CaseReviewTransition decide(Long caseId, long expectedCaseVersion, CaseReviewDecision decision, Long reviewerId);
}
