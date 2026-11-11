package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAccessService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.SignedFileUrl;
import com.souldealers.crowdtracebackend.modules.casefile.internal.files.StorageKey;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.SignedFileAccess;
import com.souldealers.crowdtracebackend.modules.casefile.internal.storage.StoredObject;
import com.souldealers.crowdtracebackend.modules.identity.ActorLookup;
import com.souldealers.crowdtracebackend.modules.identity.ActorView;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.shared.FileAccessUnavailableException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Service
public class CaseFileAccessServiceImpl implements CaseFileAccessService {
    /** Public photos are fetched right after the redirect; takedown takes effect for new requests immediately. */
    static final Duration PUBLIC_TTL = Duration.ofMinutes(5);
    /** Reviewer links are opened straight away; keep forwarded links useless quickly. */
    static final Duration REVIEWER_TTL = Duration.ofMinutes(1);
    private static final String FILE_NOT_FOUND = "File not found";

    private final CaseFileRepository files;
    private final CaseRecordRepository cases;
    private final ActorLookup actorLookup;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final SignedFileAccess signer;

    public CaseFileAccessServiceImpl(CaseFileRepository files, CaseRecordRepository cases, ActorLookup actorLookup,
            AuditRecorder auditRecorder, Clock clock, ObjectProvider<SignedFileAccess> signer) {
        this.files = files;
        this.cases = cases;
        this.actorLookup = actorLookup;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.signer = signer.getIfAvailable();
    }

    @Override
    @Transactional(readOnly = true)
    public SignedFileUrl publicPhotoUrl(Long caseId, Long fileId) {
        CaseFile file = cases.findByIdAndReviewStatus(caseId, ReviewStatus.APPROVED)
                .flatMap(approved -> files.findByIdAndCaseIdAndDeletedAtIsNull(fileId, caseId))
                .filter(found -> found.getPurpose() == CaseFilePurpose.PHOTO
                        && found.getVisibility() == FileVisibility.PUBLIC)
                .orElseThrow(() -> new NotFoundException(FILE_NOT_FOUND));
        return sign(file, PUBLIC_TTL);
    }

    @Override
    @Transactional
    @RequiresModerator
    @AuditedOperation(AuditAction.CASE_FILE_PRIVATE_ACCESS_GRANTED)
    public SignedFileUrl reviewerFileUrl(String actorEmail, Long caseId, Long fileId) {
        ActorView actor = actorLookup.byEmail(actorEmail);
        CaseFile file = files.findByIdAndCaseIdAndDeletedAtIsNull(fileId, caseId)
                .orElseThrow(() -> new NotFoundException(FILE_NOT_FOUND));
        SignedFileUrl url = sign(file, REVIEWER_TTL);
        auditRecorder.record(auditActor(actor), AuditAction.CASE_FILE_PRIVATE_ACCESS_GRANTED, file.getId(),
                AuditMetadata.of(AuditAction.CASE_FILE_PRIVATE_ACCESS_GRANTED).put("caseId", caseId).build());
        return url;
    }

    @Override
    public boolean signedAccessAvailable() {
        return signer != null;
    }

    private SignedFileUrl sign(CaseFile file, Duration ttl) {
        if (signer == null) {
            throw new FileAccessUnavailableException("File downloads are not available");
        }
        StoredObject object = new StoredObject(StorageKey.parse(file.getStorageKey(), file.getPurpose()),
                file.getVisibility(), file.getContentType(), file.getSizeBytes(), file.getChecksumSha256());
        return new SignedFileUrl(signer.signedDownloadUrl(object, ttl), clock.instant().plus(ttl));
    }

    private static AuditActor auditActor(ActorView actor) {
        return AuditActor.user(actor.userId(), switch (actor.role()) {
            case REGISTERED_USER -> AuditActorRole.REGISTERED_USER;
            case MODERATOR -> AuditActorRole.MODERATOR;
            case SUPER_ADMIN -> AuditActorRole.SUPER_ADMIN;
        });
    }
}
