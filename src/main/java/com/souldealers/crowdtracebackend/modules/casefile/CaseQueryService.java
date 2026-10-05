package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface CaseQueryService {
    /** Approved cases only; anything else is not found. */
    PublicCaseResponse getPublicCase(Long caseId);

    /** Approved cases, newest approval first; client sorting is ignored. */
    PagedResponse<PublicCaseResponse> listPublicCases(Pageable pageable);

    /** reporterId must come from the authenticated principal, never the request. */
    ReporterCaseResponse getOwnCase(Long caseId, Long reporterId);

    /** reporterId must come from the authenticated principal, never the request. */
    PagedResponse<ReporterCaseSummaryResponse> listOwnCases(Long reporterId, Pageable pageable);

    @RequiresModerator
    AdminCaseResponse getAdminCase(Long caseId);

    @RequiresModerator
    PagedResponse<AdminCaseSummaryResponse> listReviewQueue(Pageable pageable);

}
