package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewDecision;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewMutationService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseReviewTransition;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseReviewMutationServiceTest extends CasePostgresTestSupport {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final LocalDateTime DECIDED_AT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final List<ReviewStatus> PENDING = List.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW);

    @Autowired private CaseReviewMutationService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseFileRepository files;
    @Autowired private PlatformTransactionManager transactionManager;
    private long reporter;
    private long reviewer;

    @BeforeEach
    void prepareUsers() {
        reporter = user("review-reporter-" + UUID.randomUUID() + "@example.com");
        reviewer = user("review-moderator-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void approvesEveryPendingStateIntoAMissingCaseStampedFromTheClock() {
        for (ReviewStatus from : PENDING) {
            CaseRecord before = caseWithReport(from);

            CaseReviewTransition transition = decide(before.getId(), before.getVersion(), CaseReviewDecision.APPROVED);

            CaseRecord after = cases.findById(before.getId()).orElseThrow();
            assertThat(after.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(after.getCaseStatus()).isEqualTo(CaseStatus.MISSING);
            assertThat(after.getApprovedAt()).isEqualTo(DECIDED_AT);
            assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
            assertThat(transition).isEqualTo(
                    new CaseReviewTransition(before.getId(), reporter, after.getVersion(), DECIDED_AT));
        }
    }

    @Test
    void rejectsEveryPendingStateWithoutAnOutcomeOrApprovalTime() {
        for (ReviewStatus from : PENDING) {
            CaseRecord before = caseWithReport(from);

            CaseReviewTransition transition = decide(before.getId(), before.getVersion(), CaseReviewDecision.REJECTED);

            CaseRecord after = cases.findById(before.getId()).orElseThrow();
            assertThat(after.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
            assertThat(after.getCaseStatus()).isNull();
            assertThat(after.getApprovedAt()).isNull();
            assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
            assertThat(transition).isEqualTo(
                    new CaseReviewTransition(before.getId(), reporter, after.getVersion(), DECIDED_AT));
        }
    }

    @Test
    void everyDecidedStateConflictsForBothDecisions() {
        for (ReviewStatus from : List.of(ReviewStatus.APPROVED, ReviewStatus.REJECTED, ReviewStatus.TAKEN_DOWN)) {
            for (CaseReviewDecision decision : CaseReviewDecision.values()) {
                CaseRecord before = caseWithReport(from);

                assertThatThrownBy(() -> decide(before.getId(), before.getVersion(), decision))
                        .isInstanceOf(ConflictException.class);

                assertUnchanged(before);
            }
        }
    }

    @Test
    void aStaleVersionConflictsForBothDecisions() {
        for (CaseReviewDecision decision : CaseReviewDecision.values()) {
            CaseRecord before = caseWithReport(ReviewStatus.SUBMITTED);

            assertThatThrownBy(() -> decide(before.getId(), before.getVersion() - 1, decision))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> decide(before.getId(), before.getVersion() + 1, decision))
                    .isInstanceOf(ConflictException.class);

            assertUnchanged(before);
        }
    }

    @Test
    void aMissingOrDeletedReportBlocksApprovalOnly() {
        CaseRecord withoutReport = pendingCase(ReviewStatus.SUBMITTED);
        CaseRecord deletedReport = caseWithReport(ReviewStatus.SUBMITTED);
        jdbc.update("UPDATE case_files SET deleted_at = CURRENT_TIMESTAMP WHERE case_id = ?", deletedReport.getId());

        for (CaseRecord before : List.of(withoutReport, deletedReport)) {
            assertThatThrownBy(() -> decide(before.getId(), before.getVersion(), CaseReviewDecision.APPROVED))
                    .isInstanceOf(ConflictException.class);
            assertUnchanged(before);

            decide(before.getId(), before.getVersion(), CaseReviewDecision.REJECTED);
            assertThat(cases.findById(before.getId()).orElseThrow().getReviewStatus())
                    .isEqualTo(ReviewStatus.REJECTED);
        }
    }

    @Test
    void aLivePhotoDoesNotStandInForTheReport() {
        CaseRecord before = pendingCase(ReviewStatus.SUBMITTED);
        attachedFile(before.getId(), CaseFilePurpose.PHOTO);

        assertThatThrownBy(() -> decide(before.getId(), before.getVersion(), CaseReviewDecision.APPROVED))
                .isInstanceOf(ConflictException.class);
        assertUnchanged(before);
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThatThrownBy(() -> decide(Long.MAX_VALUE, 0, CaseReviewDecision.APPROVED))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theReporterCannotDecideTheirOwnCaseInAnyState() {
        for (ReviewStatus from : ReviewStatus.values()) {
            CaseRecord before = caseWithReport(from);

            for (CaseReviewDecision decision : CaseReviewDecision.values()) {
                assertThatThrownBy(() -> inTransaction(() ->
                        service.decide(before.getId(), before.getVersion(), decision, reporter)))
                        .isInstanceOf(AccessDeniedException.class);
            }
            assertUnchanged(before);
        }
    }

    @Test
    void selfReviewIsCheckedBeforeTheVersion() {
        CaseRecord before = caseWithReport(ReviewStatus.SUBMITTED);

        assertThatThrownBy(() -> inTransaction(() ->
                service.decide(before.getId(), before.getVersion() + 5, CaseReviewDecision.REJECTED, reporter)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requiresAnExistingTransaction() {
        CaseRecord before = caseWithReport(ReviewStatus.SUBMITTED);

        assertThatThrownBy(() -> service.decide(before.getId(), before.getVersion(), CaseReviewDecision.APPROVED,
                reviewer)).isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged(before);
    }

    @Test
    void aRolledBackCallerLeavesTheCaseUnchanged() {
        CaseRecord before = caseWithReport(ReviewStatus.SUBMITTED);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.decide(before.getId(), before.getVersion(), CaseReviewDecision.APPROVED, reviewer);
            throw new IllegalStateException("Review insert failed");
        })).isInstanceOf(IllegalStateException.class);
        assertUnchanged(before);
    }

    private CaseReviewTransition decide(long caseId, long expectedVersion, CaseReviewDecision decision) {
        return inTransaction(() -> service.decide(caseId, expectedVersion, decision, reviewer));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private CaseRecord caseWithReport(ReviewStatus status) {
        CaseRecord record = pendingCase(status);
        attachedFile(record.getId(), CaseFilePurpose.REPORT);
        return cases.findById(record.getId()).orElseThrow();
    }

    private CaseRecord pendingCase(ReviewStatus status) {
        boolean published = status == ReviewStatus.APPROVED || status == ReviewStatus.TAKEN_DOWN;
        var fixture = aCase(reporter).reviewStatus(status)
                .caseStatus(published ? CaseStatus.MISSING : null);
        if (status == ReviewStatus.TAKEN_DOWN) {
            fixture.takedownReason("Verified hoax report").takenDownBy(reviewer).takenDownAt(DECIDED_AT);
        }
        Long id = cases.saveAndFlush(fixture.build()).getId();
        return cases.findById(id).orElseThrow();
    }

    private void attachedFile(long caseId, CaseFilePurpose purpose) {
        long id = files.saveAndFlush(CaseFile.builder().uploadedBy(reporter).purpose(purpose)
                .visibility(purpose.visibility()).storageKey(UUID.randomUUID().toString())
                .contentType(purpose == CaseFilePurpose.REPORT ? "application/pdf" : "image/png")
                .sizeBytes(123).checksumSha256("a".repeat(64)).build()).getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                files.findById(id).orElseThrow().attachTo(caseId, DECIDED_AT));
    }

    private void assertUnchanged(CaseRecord before) {
        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getReviewStatus()).isEqualTo(before.getReviewStatus());
        assertThat(after.getCaseStatus()).isEqualTo(before.getCaseStatus());
        assertThat(after.getApprovedAt()).isEqualTo(before.getApprovedAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        Clock caseReviewTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
