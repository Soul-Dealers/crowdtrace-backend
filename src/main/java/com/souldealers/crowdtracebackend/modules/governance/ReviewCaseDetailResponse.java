package com.souldealers.crowdtracebackend.modules.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;

import java.util.List;

/**
 * Everything a moderator needs to decide, read from one snapshot. {@code caseDetails.version} is the
 * {@code expectedCaseVersion} for a decision. Files appear once, as {@code evidence}; the admin projection's own
 * file list is left out of the JSON. History is oldest first.
 */
public record ReviewCaseDetailResponse(
        @JsonIgnoreProperties("files") AdminCaseResponse caseDetails,
        List<ReviewEvidenceResponse> evidence,
        List<DuplicateMatchResponse> duplicateMatches,
        List<CaseReviewHistoryResponse> reviewHistory) {}
