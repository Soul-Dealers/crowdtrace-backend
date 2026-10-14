package com.souldealers.crowdtracebackend.modules.casefile.internal.lifecycle;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditCaseStatus;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class CaseLifecycleServiceImpl implements CaseLifecycleService {
    private final ActorLookup actorLookup;
    private final CaseRecordRepository cases;
    private final AuditRecorder auditRecorder;
    private final ClosingStatementClearer statementClearer;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    @AuditedOperation(AuditAction.CASE_STATUS_CHANGED)
    public CaseLifecycleResponse updateStatus(String actorEmail, Long caseId, UpdateCaseStatusRequest request) {
        requireStatusRequest(request);
        ActorView actor = actorLookup.byEmail(actorEmail);
        CaseRecord record = cases.findOwnedByIdForUpdate(caseId, actor.userId())
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        return changeStatus(record, actor, request.expectedCaseVersion(), request.caseStatus(), false);
    }

    @Override
    @Transactional
    @RequiresModerator
    @AuditedOperation(AuditAction.CASE_STATUS_CHANGED)
    public CaseLifecycleResponse updateStatusAsAdmin(String actorEmail, Long caseId,
                                                     UpdateCaseStatusRequest request) {
        requireStatusRequest(request);
        ActorView actor = actorLookup.byEmail(actorEmail);
        CaseRecord record = cases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        return changeStatus(record, actor, request.expectedCaseVersion(), request.caseStatus(), true);
    }

    private CaseLifecycleResponse changeStatus(CaseRecord record, ActorView actor, long expectedVersion,
                                               CaseStatus target, boolean admin) {
        if (record.getReviewStatus() != ReviewStatus.APPROVED) {
            throw new ConflictException("Only an approved case can change status");
        }
        requireVersion(record, expectedVersion);
        CaseLifecyclePolicy.requireStatusChange(record.getReviewStatus(), record.getCaseStatus(), target, admin);

        LocalDateTime occurredAt = LocalDateTime.now(clock);
        CaseStatus from = record.getCaseStatus();
        if (from == CaseStatus.MISSING && target != CaseStatus.MISSING) {
            record.setResolvedAt(occurredAt);
        }
        if (target == CaseStatus.CLOSED) {
            record.setClosedAt(occurredAt);
        } else if (from == CaseStatus.CLOSED) {
            record.setClosedAt(null);
        }
        record.setCaseStatus(target);
        if (target == CaseStatus.MISSING) {
            record.setResolvedAt(null);
            record.setClosedAt(null);
            if (record.getClosingStatement() != null) {
                record.setClosingStatement(null);
                statementClearer.recordCleared(auditActor(actor), record.getId());
            }
        }
        cases.flush();

        long auditId = auditRecorder.record(auditActor(actor), AuditAction.CASE_STATUS_CHANGED, record.getId(),
                AuditMetadata.of(AuditAction.CASE_STATUS_CHANGED)
                        .put("fromStatus", auditStatus(from)).put("toStatus", auditStatus(target)).build());
        events.publishEvent(CaseStatusChangedEvent.of(auditId, record.getId(), record.getReporterId(),
                eventStatus(from), eventStatus(target), admin ? CaseStatusChangedEvent.ActorKind.ADMIN
                : CaseStatusChangedEvent.ActorKind.REPORTER, occurredAt));
        return response(record, occurredAt);
    }

    private static void requireStatusRequest(UpdateCaseStatusRequest request) {
        if (request == null || request.expectedCaseVersion() == null || request.expectedCaseVersion() < 0
                || request.caseStatus() == null) {
            throw new ValidationException("Expected case version and case status are required");
        }
    }

    private static void requireVersion(CaseRecord record, long expectedVersion) {
        if (record.getVersion() != expectedVersion) {
            throw new ConflictException("Case has changed since it was opened");
        }
    }

    private static AuditActor auditActor(ActorView actor) {
        return AuditActor.user(actor.userId(), switch (actor.role()) {
            case REGISTERED_USER -> AuditActorRole.REGISTERED_USER;
            case MODERATOR -> AuditActorRole.MODERATOR;
            case SUPER_ADMIN -> AuditActorRole.SUPER_ADMIN;
        });
    }

    private static AuditCaseStatus auditStatus(CaseStatus status) {
        return switch (status) {
            case MISSING -> AuditCaseStatus.MISSING;
            case FOUND_SAFE -> AuditCaseStatus.FOUND_SAFE;
            case FOUND_DECEASED -> AuditCaseStatus.FOUND_DECEASED;
            case CLOSED -> AuditCaseStatus.CLOSED;
        };
    }

    private static NotifiedCaseStatus eventStatus(CaseStatus status) {
        return switch (status) {
            case MISSING -> NotifiedCaseStatus.MISSING;
            case FOUND_SAFE -> NotifiedCaseStatus.FOUND_SAFE;
            case FOUND_DECEASED -> NotifiedCaseStatus.FOUND_DECEASED;
            case CLOSED -> NotifiedCaseStatus.CLOSED;
        };
    }

    private static CaseLifecycleResponse response(CaseRecord record, LocalDateTime occurredAt) {
        return new CaseLifecycleResponse(record.getId(), record.getVersion(), record.getReviewStatus(),
                record.getCaseStatus(), record.getClosingStatement(), record.getResolvedAt(), record.getClosedAt(),
                occurredAt);
    }
}
