package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewDecision;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewMutationService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewQueueQuery;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewTransition;
import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.governance.ApproveCaseRequest;
import com.souldealers.crowdtracebackend.modules.governance.CaseReviewDecisionResponse;
import com.souldealers.crowdtracebackend.modules.governance.CaseReviewHistoryResponse;
import com.souldealers.crowdtracebackend.modules.governance.RejectCaseRequest;
import com.souldealers.crowdtracebackend.modules.governance.ReviewCaseDetailResponse;
import com.souldealers.crowdtracebackend.modules.governance.ReviewEvidenceResponse;
import com.souldealers.crowdtracebackend.modules.governance.ReviewQueueFilter;
import com.souldealers.crowdtracebackend.modules.governance.ReviewService;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.CaseReviewRepository;
import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseReviewDecidedEvent;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.List;

/**
 * Queue and detail are read-only. One transaction per decision: transition the locked case, append the review,
 * audit it, publish the reporter event. Any failure rolls all four back. Annotations sit on the concrete methods
 * because the audit aspect matches the target method, not the interface.
 */
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final ActorLookup actorLookup;
    private final CaseReviewMutationService caseReviews;
    private final CaseQueryService caseQueries;
    private final CaseReviewRepository reviewRepository;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminCaseSummaryResponse> listQueue(ReviewQueueFilter filter, Pageable pageable) {
        return caseQueries.listReviewQueue(filter == null ? CaseReviewQueueQuery.allPending() : filter.toQuery(),
                pageable);
    }

    /**
     * One read-only REPEATABLE READ snapshot, so the case, its files, matches and history all come from one point
     * in time; the casefile and history reads join this transaction. Spring can't set the isolation itself: the
     * connection is released after each transaction (application.yaml, CT-014 D10a), which HibernateJpaDialect
     * rejects for custom isolation, and the JDBC read-only hint is skipped too. So both are set in SQL, which
     * Postgres only accepts as a transaction's first statement. REQUIRES_NEW guarantees that: the detail never joins
     * a caller's transaction, so it can't fail after the caller queried or turn the caller's transaction read-only.
     * The settings end with this transaction and never reach the pooled connection's next user.
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ReviewCaseDetailResponse getCaseDetail(Long caseId) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            }
        });
        AdminCaseResponse caseDetails = caseQueries.getAdminCase(caseId);
        List<DuplicateMatchResponse> matches = caseQueries.listDuplicateMatches(caseId);
        List<CaseReviewHistoryResponse> history = reviewRepository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId)
                .stream().map(ReviewServiceImpl::toHistory).toList();
        return new ReviewCaseDetailResponse(caseDetails,
                caseDetails.files().stream().map(ReviewEvidenceResponse::metadataOnly).toList(), matches, history);
    }

    private static CaseReviewHistoryResponse toHistory(CaseReview review) {
        return new CaseReviewHistoryResponse(review.id(), review.decision(), review.reviewerId(), review.source(),
                review.reviewNotes(), review.createdAt());
    }

    @Override
    @Transactional
    @AuditedOperation(AuditAction.CASE_APPROVED)
    public CaseReviewDecisionResponse approve(String actorEmail, Long caseId, ApproveCaseRequest request) {
        if (request == null) {
            throw missingVersion();
        }
        return decide(actorEmail, caseId, request.expectedCaseVersion(), CaseReviewDecision.APPROVED,
                ReviewNotes.optional(request.reviewNotes()), AuditAction.CASE_APPROVED);
    }

    @Override
    @Transactional
    @AuditedOperation(AuditAction.CASE_REJECTED)
    public CaseReviewDecisionResponse reject(String actorEmail, Long caseId, RejectCaseRequest request) {
        if (request == null) {
            throw missingVersion();
        }
        return decide(actorEmail, caseId, request.expectedCaseVersion(), CaseReviewDecision.REJECTED,
                ReviewNotes.required(request.reviewNotes()), AuditAction.CASE_REJECTED);
    }

    private CaseReviewDecisionResponse decide(String actorEmail, Long caseId, Long expectedCaseVersion,
                                              CaseReviewDecision decision, String notes, AuditAction action) {
        if (expectedCaseVersion == null || expectedCaseVersion < 0) {
            throw missingVersion();
        }
        ActorView actor = actorLookup.byEmail(actorEmail);
        CaseReviewTransition transition = caseReviews.decide(caseId, expectedCaseVersion, decision, actor.userId());
        ReviewStatus status = decision == CaseReviewDecision.APPROVED ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        CaseReview review = reviewRepository.saveAndFlush(
                CaseReview.create(transition.caseId(), actor.userId(), status, notes, transition.decidedAt()));

        auditRecorder.record(AuditActor.user(actor.userId(), auditRole(actor.role())), action, transition.caseId(),
                AuditMetadata.of(action).put("reviewId", review.id()).build());
        events.publishEvent(CaseReviewDecidedEvent.of(transition.reporterId(), transition.caseId(), review.id(),
                CaseReviewDecidedEvent.Decision.valueOf(decision.name()), transition.decidedAt()));

        return new CaseReviewDecisionResponse(review.id(), transition.caseId(), status, transition.caseVersion(),
                actor.userId(), notes, transition.decidedAt());
    }

    /** Mirrors identity's AuditRoleMapper, which is internal to identity. */
    private static AuditActorRole auditRole(UserRoles role) {
        return switch (role) {
            case REGISTERED_USER -> AuditActorRole.REGISTERED_USER;
            case MODERATOR -> AuditActorRole.MODERATOR;
            case SUPER_ADMIN -> AuditActorRole.SUPER_ADMIN;
        };
    }

    private static ValidationException missingVersion() {
        return new ValidationException("expectedCaseVersion is required and must not be negative");
    }
}
