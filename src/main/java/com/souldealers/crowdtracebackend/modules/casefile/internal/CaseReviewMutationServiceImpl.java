package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewDecision;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewMutationService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewTransition;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class CaseReviewMutationServiceImpl implements CaseReviewMutationService {
    private static final Set<ReviewStatus> PENDING = Set.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW);

    private final CaseRecordRepository caseRepository;
    private final CaseFileRepository fileRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CaseReviewTransition decide(Long caseId, long expectedCaseVersion, CaseReviewDecision decision,
                                       Long reviewerId) {
        CaseRecord record = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        if (record.getReporterId().equals(reviewerId)) {
            throw new AccessDeniedException("Moderators cannot review their own case");
        }
        if (!PENDING.contains(record.getReviewStatus())) {
            throw new ConflictException("Case is not awaiting review");
        }
        if (record.getVersion() != expectedCaseVersion) {
            throw new ConflictException("Case has changed since it was opened");
        }
        LocalDateTime decidedAt = LocalDateTime.now(clock);
        if (decision == CaseReviewDecision.APPROVED) {
            if (!fileRepository.existsByCaseIdAndPurposeAndDeletedAtIsNull(caseId, CaseFilePurpose.REPORT)) {
                throw new ConflictException("Case has no missing-person report");
            }
            record.setReviewStatus(ReviewStatus.APPROVED);
            record.setCaseStatus(CaseStatus.MISSING);
            record.setApprovedAt(decidedAt);
        } else {
            record.setReviewStatus(ReviewStatus.REJECTED);
        }
        caseRepository.flush();
        return new CaseReviewTransition(record.getId(), record.getReporterId(), record.getVersion(), decidedAt);
    }
}
