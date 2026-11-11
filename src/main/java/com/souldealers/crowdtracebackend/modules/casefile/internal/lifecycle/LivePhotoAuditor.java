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

@Service
@RequiredArgsConstructor
public class LivePhotoAuditor {
    private final AuditRecorder auditRecorder;

    @Transactional(propagation = Propagation.MANDATORY)
    @AuditedOperation(AuditAction.CASE_PHOTO_ADDED)
    public long recordPhotoAdded(AuditActor actor, long caseId, long fileId) {
        return auditRecorder.record(actor, AuditAction.CASE_PHOTO_ADDED, caseId,
                AuditMetadata.of(AuditAction.CASE_PHOTO_ADDED).put("fileId", fileId).build());
    }
}
