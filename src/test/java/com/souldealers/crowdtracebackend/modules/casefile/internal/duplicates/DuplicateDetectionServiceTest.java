package com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseDuplicateMatch;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseDuplicateMatchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateDetectionServiceTest extends CasePostgresTestSupport {
    @Autowired private DuplicateDetectionService detector;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseDuplicateMatchRepository matches;
    @Autowired private DuplicateProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;
    private long reporter;

    @BeforeEach
    void prepareReporter() {
        reporter = user("detect-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM case_duplicate_matches WHERE case_id IN (SELECT id FROM cases WHERE reporter_id=?)", reporter);
        jdbc.update("DELETE FROM cases WHERE reporter_id=?", reporter);
        jdbc.update("DELETE FROM users WHERE id=?", reporter);
        properties.setDateWindowDays(7);
        properties.setNameThresholdPermille(900);
    }

    @Test
    void storesMatchAndOnlyFlagsNewCase() {
        CaseRecord existing = fixture("Kofi Mensah", ReviewStatus.APPROVED, 0);
        CaseRecord submitted = fixture("Mensah Kofi", ReviewStatus.SUBMITTED, 3);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId()))
                .singleElement().satisfies(match -> {
                    assertThat(match.getMatchedCaseId()).isEqualTo(existing.getId());
                    assertThat(match.getConfidence()).isEqualTo(93);
                    assertThat(match.getReasons()).isEqualTo("NAME_REORDERED,LAST_SEEN_NEAR");
                });
        assertThat(cases.findById(submitted.getId()).orElseThrow().isDuplicateFlag()).isTrue();
    }

    @Test
    void existingCaseIsNeverModified() {
        CaseRecord existing = fixture("Kofi Mensah", ReviewStatus.APPROVED, 0);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        CaseRecord unchanged = cases.findById(existing.getId()).orElseThrow();
        assertThat(unchanged.isDuplicateFlag()).isFalse();
        assertThat(unchanged.getVersion()).isEqualTo(existing.getVersion());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(existing.getUpdatedAt());
    }

    @Test
    void comparesEveryReviewStatusAndSameReportersCases() {
        List<Long> candidateIds = new ArrayList<>();
        for (ReviewStatus status : ReviewStatus.values()) {
            candidateIds.add(fixture("Kofi Mensah", status, 0).getId());
        }
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId()))
                .extracting(CaseDuplicateMatch::getMatchedCaseId).containsExactlyElementsOf(candidateIds);
        assertThat(cases.findById(submitted.getId()).orElseThrow().getVersion()).isEqualTo(submitted.getVersion() + 1);
    }

    @Test
    void includesBothWindowBoundariesAndOrdersMatchesByConfidenceThenId() {
        CaseRecord early = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, -7);
        CaseRecord late = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 7);
        CaseRecord exact = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        fixture("Kofi Mensah", ReviewStatus.SUBMITTED, -8);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId()))
                .extracting(CaseDuplicateMatch::getMatchedCaseId)
                .containsExactly(exact.getId(), early.getId(), late.getId());
    }

    @Test
    void comparesEveryPublicCaseStatus() {
        List<Long> ids = new ArrayList<>();
        for (CaseStatus status : CaseStatus.values()) {
            CaseRecord existing = fixture("Kofi Mensah", ReviewStatus.APPROVED, 0);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    cases.findById(existing.getId()).orElseThrow().setCaseStatus(status));
            ids.add(existing.getId());
        }
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId()))
                .extracting(CaseDuplicateMatch::getMatchedCaseId).containsExactlyElementsOf(ids);
    }

    @Test
    void noMatchLeavesFlagAndVersionAlone() {
        fixture("Kofi Owusu", ReviewStatus.SUBMITTED, 0);
        fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 8);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId())).isEmpty();
        CaseRecord unchanged = cases.findById(submitted.getId()).orElseThrow();
        assertThat(unchanged.isDuplicateFlag()).isFalse();
        assertThat(unchanged.getVersion()).isEqualTo(submitted.getVersion());
    }

    @Test
    void redeliveryIsANoOp() {
        fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        Long version = cases.findById(submitted.getId()).orElseThrow().getVersion();
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId())).hasSize(1);
        assertThat(cases.findById(submitted.getId()).orElseThrow().getVersion()).isEqualTo(version);
    }

    @Test
    void concurrentDeliveryStoresEachPairOnceWithoutErrors() throws Exception {
        fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var work = (java.util.concurrent.Callable<Void>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                detector.detect(submitted.getId());
                return null;
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId())).hasSize(1);
        assertThat(cases.findById(submitted.getId()).orElseThrow().isDuplicateFlag()).isTrue();
    }

    @Test
    void candidateQueryAndMatcherUseConfiguredThresholds() {
        fixture("Kofi Mensa", ReviewStatus.REJECTED, 10);
        CaseRecord submitted = fixture("Kofi Mensah", ReviewStatus.SUBMITTED, 0);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId())).isEmpty();
        properties.setDateWindowDays(14);
        properties.setNameThresholdPermille(950);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId())).isEmpty();
        properties.setNameThresholdPermille(900);
        detector.detect(submitted.getId());
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(submitted.getId()))
                .singleElement().extracting(CaseDuplicateMatch::getDayDifference).isEqualTo(10);
    }

    private CaseRecord fixture(String name, ReviewStatus status, int dayOffset) {
        CaseRecord saved = new TransactionTemplate(transactionManager).execute(tx -> cases.saveAndFlush(
                aCase(reporter).fullName(name).reviewStatus(status)
                        .caseStatus(status == ReviewStatus.APPROVED || status == ReviewStatus.TAKEN_DOWN ? CaseStatus.MISSING : null)
                        .lastSeenDate(java.time.LocalDate.of(2026, 9, 30).plusDays(dayOffset)).build()));
        return cases.findById(saved.getId()).orElseThrow();
    }
}
