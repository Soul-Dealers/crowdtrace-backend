package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseQueryServiceTest extends CasePostgresTestSupport {
    @Autowired private CaseQueryService service;
    @Autowired private CaseRecordRepository cases;

    @Test
    void publicReadReturnsAnApprovedCase() {
        long r = user("public@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).build());
        PublicCaseResponse response = service.getPublicCase(c.getId());
        assertThat(response.id()).isEqualTo(c.getId());
        assertThat(response.fullName()).isEqualTo("Kofi Mensah");
        assertThat(response.caseStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(response.approvedAt()).isEqualTo(at(9));
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"SUBMITTED", "UNDER_REVIEW", "REJECTED", "TAKEN_DOWN"})
    void publicReadHidesUnapprovedCases(ReviewStatus status) {
        long r = user("hidden@example.com");
        CaseRecord c = cases.saveAndFlush(aCase(r).reviewStatus(status)
                .caseStatus(status == ReviewStatus.TAKEN_DOWN ? CaseStatus.MISSING : null).build());
        assertThatThrownBy(() -> service.getPublicCase(c.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void publicReadHidesUnknownCases() {
        assertThatThrownBy(() -> service.getPublicCase(999999L)).isInstanceOf(NotFoundException.class);
    }
}
