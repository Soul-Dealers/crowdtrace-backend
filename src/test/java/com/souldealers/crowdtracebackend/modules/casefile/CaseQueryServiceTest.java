package com.souldealers.crowdtracebackend.modules.casefile;

import java.util.stream.IntStream;

import java.util.ArrayList;

import java.util.List;

import org.springframework.data.domain.Sort;

import org.springframework.data.domain.PageRequest;

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

    @Test
    void publicListContainsOnlyApprovedCasesNewestApprovalFirst() {
        long r = user("public-list@example.com");
        CaseRecord older = cases.save(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.FOUND_SAFE).approvedAt(at(9)).build());
        CaseRecord newer = cases.save(aCase(r).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(10)).build());
        cases.save(aCase(r).build());
        cases.save(aCase(r).reviewStatus(ReviewStatus.REJECTED).build());
        cases.saveAndFlush(aCase(r).reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(CaseStatus.MISSING).build());
        var response = service.listPublicCases(PageRequest.of(0, 10000, Sort.by("duplicateFlag")));
        assertThat(response.content()).extracting(PublicCaseResponse::id).containsExactly(newer.getId(), older.getId());
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void publicPagesAreStableWhenApprovalTimesTie() {
        long r = user("public-tie@example.com");
        List<Long> ids = IntStream.range(0, 5).mapToObj(i -> cases.save(aCase(r)
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).approvedAt(at(9)).build()).getId()).toList();
        cases.flush();
        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            service.listPublicCases(PageRequest.of(page, 2)).content().forEach(c -> seen.add(c.id()));
        }
        assertThat(seen).containsExactlyElementsOf(ids.reversed());
    }

}
