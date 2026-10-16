package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseConsentResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseFileMetadataResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CasePresentation;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.SensitiveDetailsResponse;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseDuplicateMatch;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import org.jspecify.annotations.Nullable;

import java.util.List;

final class CaseMapper {
    CaseMapper() {}

    static PublicCaseResponse toPublic(CaseRecord c) {
        boolean deceased = c.getCaseStatus() == CaseStatus.FOUND_DECEASED;
        return new PublicCaseResponse(c.getId(), c.getFullName(), c.getAge(), c.getGender(), c.getLastSeenDate(),
                c.getRegion(), deceased ? null : c.getLastSeenLocation(),
                deceased ? null : c.getPhysicalDescription(), deceased ? null : c.getClothing(),
                deceased ? null : c.getCircumstances(), c.getCaseStatus(), c.getClosingStatement(),
                deceased ? null : c.getPublicContactNumber(), c.getApprovedAt(), c.getResolvedAt(),
                deceased ? CasePresentation.DECEASED_NOTICE : CasePresentation.STANDARD);
    }

    static PublicCaseSummaryResponse toPublicSummary(CaseRecord c, Long thumbnailPhotoId) {
        PublicCaseResponse publicCase = toPublic(c);
        return new PublicCaseSummaryResponse(publicCase.id(), publicCase.fullName(), publicCase.age(),
                publicCase.gender(), publicCase.region(), publicCase.lastSeenDate(), publicCase.caseStatus(),
                publicCase.presentation(), publicCase.approvedAt(), publicCase.resolvedAt(), thumbnailPhotoId);
    }

    static ReporterCaseResponse toReporter(CaseRecord c, @Nullable CaseSensitiveDetails d) {
        return new ReporterCaseResponse(c.getId(), c.getFullName(), c.getAge(), c.getGender(), c.getLastSeenDate(),
                c.getRegion(), c.getLastSeenLocation(), c.getPhysicalDescription(), c.getClothing(),
                c.getCircumstances(), c.getPublicContactNumber(), c.getReviewStatus(), c.getCaseStatus(),
                c.getClosingStatement(), c.getSubmittedAt(), c.getApprovedAt(), toSensitive(d), c.getVersion(),
                c.getResolvedAt(), c.getClosedAt());
    }

    private static @Nullable SensitiveDetailsResponse toSensitive(@Nullable CaseSensitiveDetails d) {
        return d == null ? null : new SensitiveDetailsResponse(d.getReporterRelationship(), d.getMedicalConditions(),
                d.getKnownAssociates(), d.getVehicleInfo(), d.getSocialMediaHandles());
    }

    static ReporterCaseSummaryResponse toReporterSummary(CaseRecord c) {
        return new ReporterCaseSummaryResponse(c.getId(), c.getFullName(), c.getReviewStatus(),
                c.getCaseStatus(), c.getSubmittedAt());
    }

    static AdminCaseResponse toAdmin(CaseRecord c, @Nullable CaseSensitiveDetails d, @Nullable String reporterEmail,
            List<CaseFile> liveFiles, List<CaseConsent> consents) {
        return new AdminCaseResponse(c.getId(), c.getReporterId(), reporterEmail, c.getFullName(), c.getAge(),
                c.getGender(), c.getLastSeenDate(), c.getRegion(), c.getLastSeenLocation(), c.getPhysicalDescription(),
                c.getClothing(), c.getCircumstances(), c.getPublicContactNumber(), c.getReviewStatus(), c.getCaseStatus(),
                c.getClosingStatement(), c.isPriorityMinor(), c.isDuplicateFlag(), c.getVersion(), c.getSubmittedAt(),
                c.getApprovedAt(), c.getResolvedAt(), c.getClosedAt(), toSensitive(d),
                liveFiles.stream().map(CaseMapper::toFileMetadata).toList(),
                consents.stream().map(consent -> new CaseConsentResponse(consent.getConsentType(), consent.getConsentVersion(),
                        consent.getSource(), consent.getAcceptedAt())).toList(), c.getTakedownReason(), c.getTakenDownBy(),
                c.getTakenDownAt());
    }

    static AdminCaseSummaryResponse toAdminSummary(CaseRecord c) {
        return new AdminCaseSummaryResponse(c.getId(), c.getFullName(), c.getAge(), c.getRegion(),
                c.getReviewStatus(), c.isPriorityMinor(), c.isDuplicateFlag(), c.getSubmittedAt());
    }

    static DuplicateMatchResponse toDuplicateMatch(CaseDuplicateMatch m) {
        return new DuplicateMatchResponse(m.getMatchedCaseId(), m.getConfidence(), m.getNameSimilarity(),
                m.getDayDifference(), List.of(m.getReasons().split(",")), m.getAlgorithmVersion(), m.getDetectedAt());
    }

    static CaseFileMetadataResponse toFileMetadata(CaseFile file) {
        return new CaseFileMetadataResponse(file.getId(), file.getPurpose(), file.getVisibility(),
                file.getContentType(), file.getSizeBytes(), file.getUploadedAt());
    }

}
