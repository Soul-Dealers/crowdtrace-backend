package com.souldealers.crowdtracebackend.modules.casefile;

import com.souldealers.crowdtracebackend.modules.casefile.internal.CaseSubmissionEndpointSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(CaseSubmittedEventTest.ListenerConfiguration.class)
class CaseSubmittedEventTest extends CaseSubmissionEndpointSupport {
    @Autowired private EventProbe probe;
    @Autowired private CaseSubmissionService service;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetProbe() {
        probe.delivered.clear();
        probe.throwAfterCommit = false;
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
        assertThat(probe.delivered).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE id=?", Long.class, response.caseId())).isZero();
        assertThat(files.findById(reportId).orElseThrow().getCaseId()).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean EventProbe submissionEventProbe() {
            return new EventProbe();
        }
    }

    static class EventProbe {
        private final List<Long> delivered = new ArrayList<>();
        private boolean throwAfterCommit;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void submitted(CaseSubmittedEvent event) {
            delivered.add(event.caseId());
            if (throwAfterCommit) {
                throw new IllegalStateException("Duplicate detection failed");
            }
        }
    }
}
