package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentSource;
import com.souldealers.crowdtracebackend.modules.casefile.ConsentType;
import com.souldealers.crowdtracebackend.modules.casefile.FileVisibility;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseConsent;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;

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

    static CaseSensitiveDetails fullDetails() {
        return CaseSensitiveDetails.builder().caseRecord(fullCase()).caseId(7L).reporterRelationship(RELATION)
                .medicalConditions(MEDICAL).knownAssociates(ASSOCIATES).vehicleInfo(VEHICLE)
                .socialMediaHandles(SOCIALS).build();
    }

    static CaseFile reportFile() {
        return CaseFile.builder().id(8L).caseId(7L).uploadedBy(42L).purpose(CaseFilePurpose.REPORT)
                .visibility(FileVisibility.PRIVATE).storageKey(STORAGE_KEY).contentType("application/pdf")
                .sizeBytes(123L).checksumSha256("a".repeat(64)).uploadedAt(fullCase().getCreatedAt()).build();
    }

    static CaseConsent consent() {
        return CaseConsent.builder().id(9L).caseId(7L).userId(42L).consentType(ConsentType.SENSITIVE_DATA_COLLECTION)
                .consentVersion("v1").source(ConsentSource.WEB).acceptedAt(fullCase().getCreatedAt()).build();
    }

}
