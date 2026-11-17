package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CaseQueryService {
    /** Approved cases only; anything else is not found. */
    PublicCaseResponse getPublicCase(Long caseId);

    /** Whether the case is approved and so publicly visible. */
    boolean isPublicCase(Long caseId);

    /** The case's reporter, for notifying them only; never put it in a public response. Empty for an unknown case. */
    Optional<Long> findReporterId(Long caseId);

    /**
     * Like {@link #isPublicCase} but holds a shared lock on the case row until the caller's transaction ends, so a
     * concurrent takedown waits. Must run inside a read-write transaction.
     */
    boolean lockPublicCase(Long caseId);

    /** Summaries of the approved cases among caseIds, keyed by id; any other id is left out. */
    Map<Long, PublicCaseSummaryResponse> getPublicCaseSummaries(Collection<Long> caseIds);

    /** Approved cases matching the query, newest approval first, then id; client sorting is ignored. */
    CursorResponse<PublicCaseSummaryResponse> searchPublicCases(PublicCaseSearchQuery query, String cursor, int size);

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
