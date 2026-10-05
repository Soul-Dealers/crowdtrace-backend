package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class CaseRecordRepositoryTest extends CasePostgresTestSupport {
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseSensitiveDetailsRepository sensitive;

    @Test
    void findsOnlyApprovedCasesForPublicLookup() {
        long r = user("lookup@example.com");
        CaseRecord submitted = cases.saveAndFlush(aCase(r).build());
        CaseRecord approved = cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).build());
        assertThat(cases.findByIdAndReviewStatus(submitted.getId(), ReviewStatus.APPROVED)).isEmpty();
        assertThat(cases.findByIdAndReviewStatus(approved.getId(), ReviewStatus.APPROVED))
                .map(CaseRecord::getId).contains(approved.getId());
    }

    @ParameterizedTest @EnumSource(GhanaRegion.class)
    void everyRegionIsAcceptedByTheDatabase(GhanaRegion region) {
        CaseRecord c = cases.saveAndFlush(aCase(user("region@example.com")).region(region).build());
        assertThat(c.getId()).isNotNull();
    }

    @ParameterizedTest @EnumSource(Gender.class)
    void everyGenderIsAcceptedByTheDatabase(Gender gender) {
        assertThat(cases.saveAndFlush(aCase(user("gender@example.com")).gender(gender).build()).getId()).isNotNull();
    }

    @ParameterizedTest @EnumSource(ReviewStatus.class)
    void everyReviewStatusIsAcceptedByTheDatabase(ReviewStatus status) {
        CaseStatus outcome = status == ReviewStatus.APPROVED || status == ReviewStatus.TAKEN_DOWN
                ? CaseStatus.MISSING : null;
        assertThat(cases.saveAndFlush(aCase(user("review@example.com"))
                .reviewStatus(status).caseStatus(outcome).build()).getId()).isNotNull();
    }

    @ParameterizedTest @EnumSource(CaseStatus.class)
    void everyCaseStatusIsAcceptedByTheDatabase(CaseStatus status) {
        assertThat(cases.saveAndFlush(aCase(user("outcome@example.com"))
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(status).build()).getId()).isNotNull();
    }

    @Test
    void scopesLookupToTheOwner() {
        long a = user("repo-owner@example.com"), b = user("repo-other@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(a).build());
        assertThat(cases.findByIdAndReporterId(c.getId(), b)).isEmpty();
        assertThat(cases.findByIdAndReporterId(c.getId(), a)).map(CaseRecord::getId).contains(c.getId());
    }

    @Test
    void sensitiveDetailsShareTheCaseId() {
        long r = user("shared-id@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).build());
        CaseRecord other = cases.saveAndFlush(aCase(r).build());
        sensitive.saveAndFlush(CaseSensitiveDetails.builder().caseRecord(c).reporterRelationship("Brother").build());
        assertThat(sensitive.findById(c.getId())).map(CaseSensitiveDetails::getCaseId).contains(c.getId());
        assertThat(sensitive.findById(other.getId())).isEmpty();
    }

}
