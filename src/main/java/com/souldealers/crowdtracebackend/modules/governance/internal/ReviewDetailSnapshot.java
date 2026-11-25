package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAccessService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;
import com.souldealers.crowdtracebackend.modules.governance.CaseReviewHistoryResponse;
import com.souldealers.crowdtracebackend.modules.governance.ReviewCaseDetailResponse;
import com.souldealers.crowdtracebackend.modules.governance.ReviewEvidenceResponse;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.CaseReviewRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.List;

/** The review detail's read, in its own bean so the proxy applies its read-only snapshot transaction. */
@Component
@RequiredArgsConstructor
class ReviewDetailSnapshot {
    private final CaseQueryService caseQueries;
    private final CaseFileAccessService fileAccess;
    private final CaseReviewRepository reviewRepository;
    private final EntityManager entityManager;

    /**
     * One read-only REPEATABLE READ snapshot, so the case, its files, matches and history all come from one point
     * in time; the casefile and history reads join this transaction. Spring can't set the isolation itself: the
     * connection is released after each transaction (application.yaml, CT-014 D10a), which HibernateJpaDialect
     * rejects for custom isolation, and the JDBC read-only hint is skipped too. So both are set in SQL, which
     * Postgres only accepts as a transaction's first statement. REQUIRES_NEW guarantees that: the detail never joins
     * a caller's transaction, so it can't fail after the caller queried or turn the caller's transaction read-only.
     * The settings end with this transaction and never reach the pooled connection's next user.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ReviewCaseDetailResponse read(Long caseId) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            }
        });
        AdminCaseResponse caseDetails = caseQueries.getAdminCase(caseId);
        List<DuplicateMatchResponse> matches = caseQueries.listDuplicateMatches(caseId);
        List<CaseReviewHistoryResponse> history = reviewRepository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId)
                .stream().map(ReviewDetailSnapshot::toHistory).toList();
        boolean accessAvailable = fileAccess.signedAccessAvailable();
        return new ReviewCaseDetailResponse(caseDetails, caseDetails.files().stream()
                .map(file -> new ReviewEvidenceResponse(file, accessAvailable)).toList(), matches, history);
    }

    private static CaseReviewHistoryResponse toHistory(CaseReview review) {
        return new CaseReviewHistoryResponse(review.id(), review.decision(), review.reviewerId(), review.source(),
                review.reviewNotes(), review.createdAt());
    }
}
