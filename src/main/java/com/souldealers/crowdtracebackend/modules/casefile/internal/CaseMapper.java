package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReporterCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.SensitiveDetailsResponse;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import org.jspecify.annotations.Nullable;

final class CaseMapper {
    CaseMapper() {}

    static PublicCaseResponse toPublic(CaseRecord c) {
        return new PublicCaseResponse(c.getId(), c.getFullName(), c.getAge(), c.getGender(), c.getLastSeenDate(),
                c.getRegion(), c.getLastSeenLocation(), c.getPhysicalDescription(), c.getClothing(),
                c.getCircumstances(), c.getCaseStatus(), c.getClosingStatement(), c.getPublicContactNumber(),
                c.getApprovedAt(), c.getResolvedAt());
    }

    static ReporterCaseResponse toReporter(CaseRecord c, @Nullable CaseSensitiveDetails d) {
        return new ReporterCaseResponse(c.getId(), c.getFullName(), c.getAge(), c.getGender(), c.getLastSeenDate(),
                c.getRegion(), c.getLastSeenLocation(), c.getPhysicalDescription(), c.getClothing(),
                c.getCircumstances(), c.getPublicContactNumber(), c.getReviewStatus(), c.getCaseStatus(),
                c.getClosingStatement(), c.getSubmittedAt(), c.getApprovedAt(), toSensitive(d));
    }

    private static @Nullable SensitiveDetailsResponse toSensitive(@Nullable CaseSensitiveDetails d) {
        return d == null ? null : new SensitiveDetailsResponse(d.getReporterRelationship(), d.getMedicalConditions(),
                d.getKnownAssociates(), d.getVehicleInfo(), d.getSocialMediaHandles());
    }

    static ReporterCaseSummaryResponse toReporterSummary(CaseRecord c) {
        return new ReporterCaseSummaryResponse(c.getId(), c.getFullName(), c.getReviewStatus(),
                c.getCaseStatus(), c.getSubmittedAt());
    }

}
