package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewQueueQuery;
import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseCursor;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSearchQuery;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseConsentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseDuplicateMatchRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseReviewQueueSpecification;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.PublicCaseSpecification;
import com.souldealers.crowdtracebackend.modules.identity.RequiresModerator;
import com.souldealers.crowdtracebackend.modules.identity.UserService;
import com.souldealers.crowdtracebackend.shared.CursorResponse;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.CASE_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaseQueryServiceImpl implements CaseQueryService {
    private final CaseRecordRepository caseRepository;
    private final CaseSensitiveDetailsRepository sensitiveRepository;
    private final CaseFileRepository fileRepository;
    private final CaseConsentRepository consentRepository;
    private final CaseDuplicateMatchRepository matchRepository;
    private final UserService userService;

    @Override
    public PublicCaseResponse getPublicCase(Long caseId) {
        return caseRepository.findByIdAndReviewStatus(caseId, ReviewStatus.APPROVED)
                .map(CaseMapper::toPublic)
                .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
    }

    @Override
    public CursorResponse<PublicCaseSummaryResponse> searchPublicCases(PublicCaseSearchQuery query, String token, int size) {
        if (size < 1 || size > 50) {
            throw new ValidationException("size must be between 1 and 50");
        }
        PublicCaseCursor cursor = token == null ? null : PublicCaseCursor.decode(token);
        List<CaseRecord> fetched = caseRepository.findBy(PublicCaseSpecification.matching(query, cursor),
                fluent -> fluent.sortBy(PublicCaseSpecification.ORDER).limit(size + 1).all());
        boolean hasNext = fetched.size() > size;
        List<CaseRecord> returned = hasNext ? fetched.subList(0, size) : fetched;
        List<Long> ids = returned.stream().map(CaseRecord::getId).toList();
        Map<Long, Long> thumbnails = ids.isEmpty() ? Map.of() : fileRepository.findThumbnails(ids).stream()
                .collect(Collectors.toMap(row -> row.getCaseId(), row -> row.getPhotoId()));
        List<PublicCaseSummaryResponse> content = returned.stream()
                .map(c -> CaseMapper.toPublicSummary(c, thumbnails.get(c.getId()))).toList();
        String nextCursor = hasNext && !returned.isEmpty()
                ? new PublicCaseCursor(returned.getLast().getApprovedAt(), returned.getLast().getId()).encode() : null;
        return new CursorResponse<>(content, size, hasNext, nextCursor);
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
    public PagedResponse<AdminCaseSummaryResponse> listReviewQueue(CaseReviewQueueQuery query, Pageable pageable) {
        Pageable page = CasePageRequests.fixed(pageable);
        return PagedResponse.from(caseRepository.findAll(CaseReviewQueueSpecification.matching(
                        query == null ? CaseReviewQueueQuery.allPending() : query),
                PageRequest.of(page.getPageNumber(), page.getPageSize(), CaseReviewQueueSpecification.ORDER))
                .map(CaseMapper::toAdminSummary));
    }

    @Override
    @RequiresModerator
    public List<DuplicateMatchResponse> listDuplicateMatches(Long caseId) {
        return matchRepository.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(caseId).stream()
                .map(CaseMapper::toDuplicateMatch).toList();
    }

}
