package com.souldealers.crowdtracebackend.modules.casefile;

public interface CaseQueryService {
    /** Approved cases only; anything else is not found. */
    PublicCaseResponse getPublicCase(Long caseId);
}
