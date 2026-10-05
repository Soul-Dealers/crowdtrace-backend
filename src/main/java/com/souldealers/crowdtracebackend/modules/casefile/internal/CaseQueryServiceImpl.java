package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;

import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseResponse;

import org.springframework.data.domain.Pageable;

import com.souldealers.crowdtracebackend.shared.PagedResponse;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaseQueryServiceImpl implements CaseQueryService {
    private static final String CASE_NOT_FOUND = "Case not found";
    private final CaseRecordRepository caseRepository;
    private final CaseSensitiveDetailsRepository sensitiveRepository;

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

}
