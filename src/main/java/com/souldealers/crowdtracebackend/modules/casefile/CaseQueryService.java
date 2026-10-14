package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

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

    /** Pending cases matching the query, minors first, then oldest submission, then id; client sorting is ignored. */
    @RequiresModerator
    PagedResponse<AdminCaseSummaryResponse> listReviewQueue(CaseReviewQueueQuery query, Pageable pageable);

    /** Duplicates recorded against the case, highest confidence first; empty for an unknown case. */
    @RequiresModerator
    List<DuplicateMatchResponse> listDuplicateMatches(Long caseId);

}
