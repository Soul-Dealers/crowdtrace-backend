package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseSensitiveDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseRecordRepositoryTest extends CasePostgresTestSupport {
    @Autowired private CaseRecordRepository cases;
    @Autowired private PlatformTransactionManager transactionManager;
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

    @Test
    void listsOnlyTheReportersOwnCasesNewestFirst() {
        long a = user("list-a@example.com"), b = user("list-b@example.com");
        CaseRecord older = cases.save(aCase(a).createdAt(at(9)).build());
        CaseRecord newer = cases.save(aCase(a).createdAt(at(10)).build());
        cases.saveAndFlush(aCase(b).createdAt(at(11)).build());
        assertThat(cases.findByReporterIdOrderByCreatedAtDescIdDesc(a, PageRequest.of(0, 20)))
                .extracting(CaseRecord::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void pagesAreStableWhenTimestampsTie() {
        long r = user("tie@example.com");
        List<Long> ids = IntStream.range(0, 5).mapToObj(i -> cases.save(aCase(r)
                .createdAt(at(9)).submittedAt(at(9)).build()).getId()).toList();
        cases.flush();
        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            cases.findByReporterIdOrderByCreatedAtDescIdDesc(r, PageRequest.of(page, 2)).forEach(c -> seen.add(c.getId()));
        }
        assertThat(seen).containsExactlyElementsOf(ids.reversed());
    }

    @Test
    void reviewQueueShowsMinorsFirstThenOldest() {
        long r = user("repo-queue@example.com");
        CaseRecord adult = cases.save(aCase(r).submittedAt(at(9)).build());
        CaseRecord earlyMinor = cases.save(aCase(r).submittedAt(at(10)).priorityMinor(true).build());
        CaseRecord laterMinor = cases.save(aCase(r).submittedAt(at(11)).priorityMinor(true)
                .reviewStatus(ReviewStatus.UNDER_REVIEW).build());
        cases.saveAndFlush(aCase(r).submittedAt(at(8)).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).build());
        assertThat(cases.findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAscIdAsc(
                List.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW), PageRequest.of(0, 20)))
                .extracting(CaseRecord::getId).containsExactly(earlyMinor.getId(), laterMinor.getId(), adult.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void optimisticLockRejectsAStaleUpdate() {
        long r = user("stale-update@example.com");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long caseId = null;
        try {
            CaseRecord created = tx.execute(status -> cases.saveAndFlush(aCase(r).version(0L).build()));
            caseId = created.getId();
            Long id = caseId;
            CaseRecord first = tx.execute(status -> cases.findById(id).orElseThrow());
            CaseRecord stale = tx.execute(status -> cases.findById(id).orElseThrow());
            first.setClosingStatement("First update");
            tx.executeWithoutResult(status -> cases.saveAndFlush(first));
            stale.setClosingStatement("Stale update");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> cases.saveAndFlush(stale)))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            String statement = tx.execute(status -> cases.findById(id).orElseThrow().getClosingStatement());
            assertThat(statement).isEqualTo("First update");
        } finally {
            if (caseId != null) jdbc.update("DELETE FROM cases WHERE id = ?", caseId);
            jdbc.update("DELETE FROM users WHERE id = ?", r);
        }
    }

}
