package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewDecision;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewMutationService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewQueueQuery;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewTransition;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.governance.ApproveCaseRequest;
import com.souldealers.crowdtracebackend.modules.governance.CaseReviewDecisionResponse;
import com.souldealers.crowdtracebackend.modules.governance.RejectCaseRequest;
import com.souldealers.crowdtracebackend.modules.governance.ReviewCaseDetailResponse;
import com.souldealers.crowdtracebackend.modules.governance.ReviewQueueFilter;
import com.souldealers.crowdtracebackend.modules.governance.ReviewService;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.CaseReviewRepository;
import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final ReviewDetailSnapshot snapshot;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminCaseSummaryResponse> listQueue(ReviewQueueFilter filter, Pageable pageable) {
        return caseQueries.listReviewQueue(filter == null ? CaseReviewQueueQuery.allPending() : filter.toQuery(),
                pageable);
    }

    /**
     * Reads the snapshot first, so an unknown case throws before anything is written, then audits the read in this
     * write transaction. The audit commits before the controller returns the details; if it fails, nothing is shown.
     * REQUIRES_NEW keeps the caller's transaction out of it, as the snapshot does.
     */
    @Override
    @RequiresModerator
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @AuditedOperation(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED)
    public ReviewCaseDetailResponse getCaseDetail(String actorEmail, Long caseId) {
        ReviewCaseDetailResponse detail = snapshot.read(caseId);
        ActorView actor = actorLookup.byEmail(actorEmail);
        auditRecorder.record(AuditActor.user(actor.userId(), auditRole(actor.role())),
                AuditAction.CASE_SENSITIVE_DETAILS_VIEWED, caseId,
                AuditMetadata.of(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED).build());
        return detail;
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
