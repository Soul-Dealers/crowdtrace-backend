package com.souldealers.crowdtracebackend.modules.casefile;

import org.springframework.data.domain.Pageable;

import com.souldealers.crowdtracebackend.shared.PagedResponse;

public interface CaseQueryService {
    /** Approved cases only; anything else is not found. */
    PublicCaseResponse getPublicCase(Long caseId);

    /** Approved cases, newest approval first; client sorting is ignored. */
    PagedResponse<PublicCaseResponse> listPublicCases(Pageable pageable);


    /** reporterId must come from the authenticated principal, never the request. */
    ReporterCaseResponse getOwnCase(Long caseId, Long reporterId);

}
