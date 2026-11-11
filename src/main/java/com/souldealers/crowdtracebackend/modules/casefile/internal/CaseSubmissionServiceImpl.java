package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFileAttachmentService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionRequest;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmissionService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseSubmittedEvent;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseConsentRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseSensitiveDetailsRepository;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
@Validated
public class CaseSubmissionServiceImpl implements CaseSubmissionService {
    private final CaseRecordRepository caseRepository;
    private final CaseSensitiveDetailsRepository sensitiveRepository;
    private final CaseConsentRepository consentRepository;
    private final CaseFileAttachmentService attachmentService;
    private final ConsentProperties consentProperties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    public CaseSubmissionResponse submit(Long reporterId, CaseSubmissionRequest request) {
        String consentVersion = consentProperties.getSensitiveDataVersion();
        if (!consentVersion.equals(request.consent().version())) {
            throw new ValidationException("Consent version is outdated");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        CaseRecord record = caseRepository.save(CaseRecord.builder().reporterId(reporterId)
                .fullName(request.fullName()).age(request.age()).gender(request.gender())
                .lastSeenDate(request.lastSeenDate()).region(request.region())
                .lastSeenLocation(request.lastSeenLocation()).physicalDescription(request.physicalDescription())
                .clothing(request.clothing()).circumstances(request.circumstances())
                .publicContactNumber(request.publicContactNumber()).reviewStatus(ReviewStatus.SUBMITTED)
                .caseStatus(null).priorityMinor(request.age() < 18).duplicateFlag(false).submittedAt(now).build());

        CaseSubmissionRequest.SensitiveDetailsInput sensitive = request.sensitiveDetails();
        sensitiveRepository.save(CaseSensitiveDetails.builder().caseRecord(record)
                .reporterRelationship(sensitive.reporterRelationship()).medicalConditions(sensitive.medicalConditions())
                .knownAssociates(sensitive.knownAssociates()).vehicleInfo(sensitive.vehicleInfo())
                .socialMediaHandles(sensitive.socialMediaHandles()).build());
        consentRepository.save(CaseConsent.builder().caseId(record.getId()).userId(reporterId)
                .consentType(ConsentType.SENSITIVE_DATA_COLLECTION).consentVersion(consentVersion)
                .source(request.consent().source()).acceptedAt(now).build());

        attachmentService.attachSubmission(record.getId(), reporterId, request.reportFileIds(), request.photoFileIds());
        events.publishEvent(new CaseSubmittedEvent(record.getId()));
        return new CaseSubmissionResponse(record.getId(), record.getReviewStatus(), record.getSubmittedAt());
    }
}
