package com.souldealers.crowdtracebackend.modules.casefile.internal.lifecycle;

import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxied bean: a reopen must satisfy the statement audit obligation only when it clears text. */
@Service
@RequiredArgsConstructor
public class ClosingStatementClearer {
    private final AuditRecorder auditRecorder;

    @Transactional(propagation = Propagation.MANDATORY)
    @AuditedOperation(AuditAction.CASE_CLOSING_STATEMENT_SET)
    public void recordCleared(AuditActor actor, long caseId) {
        auditRecorder.record(actor, AuditAction.CASE_CLOSING_STATEMENT_SET, caseId,
                AuditMetadata.none(AuditAction.CASE_CLOSING_STATEMENT_SET));
    }
}
