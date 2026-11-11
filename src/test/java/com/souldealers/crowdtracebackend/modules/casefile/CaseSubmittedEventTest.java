package com.souldealers.crowdtracebackend.modules.casefile;

import com.zaxxer.hikari.HikariDataSource;
import com.souldealers.crowdtracebackend.modules.casefile.internal.CaseSubmissionEndpointSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.duplicates.DuplicateDetectionService;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseDuplicateMatchRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000"})
@ExtendWith(OutputCaptureExtension.class)
@Import(CaseSubmittedEventTest.ListenerConfiguration.class)
class CaseSubmittedEventTest extends CaseSubmissionEndpointSupport {
    @Autowired private EventProbe probe;
    @Autowired private HikariDataSource dataSource;
    @Autowired private CaseRecordRepository cases;
    @Autowired private CaseDuplicateMatchRepository matches;
    @MockitoSpyBean private DuplicateDetectionService detector;
    @Autowired private CaseSubmissionService service;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetProbe() {
        clearInvocations(detector);
        probe.delivered.clear();
        probe.throwAfterCommit = false;
        probe.commitsReady = null;
        probe.continueDetection = null;
    }

    @Test
    void aThrowingAfterCommitListenerCannotFailTheSubmissionOrUndoItsData() throws Exception {
        probe.throwAfterCommit = true;

        String body = submit(validRequest()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long caseId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.caseId")).longValue();
        assertThat(probe.delivered).containsExactly(caseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE id=?", Long.class, caseId)).isEqualTo(1);
        assertThat(files.findById(reportId).orElseThrow().getCaseId()).isEqualTo(caseId);
    }

    @Test
    void aPublishedEventIsNotDeliveredWhenTheOuterTransactionRollsBack() {
        CaseSubmissionResponse response = new TransactionTemplate(transactionManager).execute(status -> {
            CaseSubmissionResponse submitted = service.submit(reporterId, validRequest());
            assertThat(probe.delivered).isEmpty();
            assertThat(files.findById(reportId).orElseThrow().getCaseId()).isEqualTo(submitted.caseId());
            status.setRollbackOnly();
            return submitted;
        });

        assertThat(response).isNotNull();
        verifyNoInteractions(detector);
        assertThat(probe.delivered).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE id=?", Long.class, response.caseId())).isZero();
        assertThat(files.findById(reportId).orElseThrow().getCaseId()).isNull();
    }

    @Test
    void matchingSubmissionIsCreatedAndFlaggedAfterCommit() throws Exception {
        String name = "Ama " + java.util.UUID.randomUUID();
        CaseSubmissionRequest request = validRequest().toBuilder().fullName(name).build();
        long original = cases.saveAndFlush(aCase(reporterId).fullName(name)
                .lastSeenDate(request.lastSeenDate()).build()).getId();

        String body = submit(request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewStatus").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.caseId")).longValue();
        assertThat(cases.findById(id).orElseThrow().isDuplicateFlag()).isTrue();
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(id))
                .singleElement().satisfies(match -> assertThat(match.getMatchedCaseId()).isEqualTo(original));
        assertThat(cases.findById(original).orElseThrow().isDuplicateFlag()).isFalse();
        assertThat(body).doesNotContain("duplicateFlag", "nameSimilarity", "algorithmVersion");
    }

    @Test
    void throwingDetectorDoesNotFailSubmission(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("Ama Mensah 2026-10-01 sensitive failure"))
                .when(detector).detect(anyLong());
        String body = submit(validRequest()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.caseId")).longValue();
        assertThat(cases.findById(id).orElseThrow().isDuplicateFlag()).isFalse();
        assertThat(matches.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc(id)).isEmpty();
        assertThat(output.getAll().lines().filter(line -> line.contains("Duplicate detection failed")).toList())
                .singleElement().satisfies(line -> assertThat(line)
                        .contains("WARN", Long.toString(id), "IllegalStateException")
                        .doesNotContain("Ama Mensah", "2026-10-01", "sensitive failure"));
    }

    @Test
    void concurrentSubmissionsReleaseTheirConnectionsBeforeDetection() throws Exception {
        String name = "Ama " + java.util.UUID.randomUUID();
        CaseSubmissionRequest first = validRequest().toBuilder().fullName(name).build();
        CaseSubmissionRequest second = first.toBuilder()
                .reportFileIds(List.of(upload(reporterId, CaseFilePurpose.REPORT))).build();
        long original = cases.saveAndFlush(aCase(reporterId).fullName(name)
                .lastSeenDate(first.lastSeenDate()).build()).getId();
        probe.commitsReady = new CountDownLatch(2);
        probe.continueDetection = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResponse = executor.submit(() -> submit(first).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            var secondResponse = executor.submit(() -> submit(second).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            try {
                assertThat(probe.commitsReady.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally {
                probe.continueDetection.countDown();
            }
            List<Long> ids = List.of(firstResponse.get(15, TimeUnit.SECONDS), secondResponse.get(15, TimeUnit.SECONDS))
                    .stream().map(body -> ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.caseId")).longValue())
                    .toList();
            for (long id : ids) {
                assertThat(cases.findById(id).orElseThrow().isDuplicateFlag()).isTrue();
                assertThat(matches.existsByCaseIdAndMatchedCaseId(id, original)).isTrue();
            }
            assertThat(matches.existsByCaseIdAndMatchedCaseId(ids.get(0), ids.get(1))).isTrue();
            assertThat(matches.existsByCaseIdAndMatchedCaseId(ids.get(1), ids.get(0))).isTrue();
        }
        assertThat(cases.findById(original).orElseThrow().isDuplicateFlag()).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean EventProbe submissionEventProbe() {
            return new EventProbe();
        }
    }

    static class EventProbe {
        private final List<Long> delivered = new CopyOnWriteArrayList<>();
        private boolean throwAfterCommit;
        private CountDownLatch commitsReady;
        private CountDownLatch continueDetection;

        @Order(Ordered.HIGHEST_PRECEDENCE)
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void submitted(CaseSubmittedEvent event) {
            delivered.add(event.caseId());
            if (commitsReady != null) {
                commitsReady.countDown();
                try {
                    if (!continueDetection.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Commit probe timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Commit probe interrupted");
                }
            }
            if (throwAfterCommit) {
                throw new IllegalStateException("Duplicate detection failed");
            }
        }
    }
}
