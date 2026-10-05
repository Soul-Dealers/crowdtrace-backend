package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class CaseProjectionFixtures {
    private CaseProjectionFixtures() {}
    static final String MEDICAL = "SENTINEL-MEDICAL", ASSOCIATES = "SENTINEL-ASSOCIATES",
            VEHICLE = "SENTINEL-VEHICLE", SOCIALS = "SENTINEL-SOCIALS", RELATION = "SENTINEL-RELATION",
            EMAIL = "sentinel-reporter@example.com", STORAGE_KEY = "SENTINEL-STORAGE-KEY";
    static CaseRecord fullCase() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0);
        return CaseRecord.builder().id(7L).reporterId(42L).fullName("Kofi Mensah").age(15)
                .gender(Gender.MALE).lastSeenDate(LocalDate.of(2026, 9, 30)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Madina").physicalDescription("Slim").clothing("Blue shirt")
                .circumstances("Did not return").publicContactNumber("+233200000000")
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).closingStatement("Statement")
                .priorityMinor(true).duplicateFlag(true).version(3L).submittedAt(at).approvedAt(at)
                .resolvedAt(at).closedAt(at).createdAt(at).updatedAt(at).build();
    }
}
