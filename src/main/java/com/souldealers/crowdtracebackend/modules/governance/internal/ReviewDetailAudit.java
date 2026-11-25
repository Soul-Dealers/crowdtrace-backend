package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a moderator's view of a case's sensitive details in its own transaction, committed before the details
 * are returned. It runs after the snapshot's transaction has ended, so a detail load holds one connection at a time.
 */
@Component
@RequiredArgsConstructor
public class ReviewDetailAudit {
    private final ActorLookup actorLookup;
    private final AuditRecorder auditRecorder;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @AuditedOperation(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED)
    public void recordView(String actorEmail, Long caseId) {
        ActorView actor = actorLookup.byEmail(actorEmail);
        auditRecorder.record(AuditActor.user(actor.userId(), ReviewServiceImpl.auditRole(actor.role())),
                AuditAction.CASE_SENSITIVE_DETAILS_VIEWED, caseId,
                AuditMetadata.of(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED).build());
    }
}
