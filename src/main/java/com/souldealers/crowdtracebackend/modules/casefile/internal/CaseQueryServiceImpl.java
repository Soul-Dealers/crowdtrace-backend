package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseConsentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaseQueryServiceImpl implements CaseQueryService {
    private final CaseRecordRepository caseRepository;
    private final CaseSensitiveDetailsRepository sensitiveRepository;
    private final CaseFileRepository fileRepository;
    private final CaseConsentRepository consentRepository;
    private final UserService userService;

    @Override
    public PublicCaseResponse getPublicCase(Long caseId) {
        return caseRepository.findByIdAndReviewStatus(caseId, ReviewStatus.APPROVED)
                .map(CaseMapper::toPublic)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
    }

    @Override
    public PagedResponse<PublicCaseResponse> listPublicCases(Pageable pageable) {
        return PagedResponse.from(caseRepository.findByReviewStatusOrderByApprovedAtDescIdDesc(
                ReviewStatus.APPROVED, CasePageRequests.fixed(pageable)).map(CaseMapper::toPublic));
    }

    @Override
    public ReporterCaseResponse getOwnCase(Long caseId, Long reporterId) {
        CaseRecord c = caseRepository.findByIdAndReporterId(caseId, reporterId)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        return CaseMapper.toReporter(c, sensitiveRepository.findById(caseId).orElse(null));
    }

    @Override
    public PagedResponse<ReporterCaseSummaryResponse> listOwnCases(Long reporterId, Pageable pageable) {
        return PagedResponse.from(caseRepository.findByReporterIdOrderByCreatedAtDescIdDesc(
                reporterId, CasePageRequests.fixed(pageable)).map(CaseMapper::toReporterSummary));
    }

    @Override
    @RequiresModerator
    public AdminCaseResponse getAdminCase(Long caseId) {
        CaseRecord c = caseRepository.findById(caseId).orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
        return CaseMapper.toAdmin(c, sensitiveRepository.findById(caseId).orElse(null),
                userService.getAccountEmail(c.getReporterId()).orElse(null),
                fileRepository.findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(caseId),
                consentRepository.findByCaseIdOrderByAcceptedAtAscIdAsc(caseId));
    }

    @Override
    @RequiresModerator
    public PagedResponse<AdminCaseSummaryResponse> listReviewQueue(Pageable pageable) {
        return PagedResponse.from(caseRepository.findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAscIdAsc(
                List.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW), CasePageRequests.fixed(pageable))
                .map(CaseMapper::toAdminSummary));
    }

}
