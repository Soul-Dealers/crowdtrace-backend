package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseFilePurpose;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseFileRepository;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseReviewDecidedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Postgres, real proxies; fixtures for pending cases, actors and delivered decision events. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReviewDecisionTestSupport.Fixtures.class)
abstract class ReviewDecisionTestSupport extends CasePostgresTestSupport {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    static final LocalDateTime DECIDED_AT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Autowired protected ReviewService reviewService;
    @Autowired protected CaseRecordRepository cases;
    @Autowired protected CaseFileRepository files;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected DecisionEventProbe probe;

    protected long reporter;
    protected String reporterEmail;

    @BeforeEach
    void prepareReporter() {
        probe.delivered.clear();
        reporterEmail = email("reporter");
        reporter = actor(reporterEmail, UserRoles.REGISTERED_USER);
    }

    protected static String email(String prefix) {
        return "review-" + prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    protected long actor(String email, UserRoles role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id
                """, Long.class, email, role.name());
    }

    protected CaseRecord pendingCaseWithReport() {
        return pendingCaseWithReport(reporter);
    }

    protected CaseRecord pendingCaseWithReport(long reporterId) {
        long caseId = cases.saveAndFlush(aCase(reporterId).reviewStatus(ReviewStatus.SUBMITTED).build()).getId();
        long fileId = files.saveAndFlush(CaseFile.builder().uploadedBy(reporterId).purpose(CaseFilePurpose.REPORT)
                .visibility(CaseFilePurpose.REPORT.visibility()).storageKey(UUID.randomUUID().toString())
                .contentType("application/pdf").sizeBytes(123).checksumSha256("a".repeat(64)).build()).getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                files.findById(fileId).orElseThrow().attachTo(caseId, DECIDED_AT));
        return cases.findById(caseId).orElseThrow();
    }

    protected void assertUndecided(CaseRecord before) {
        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getReviewStatus()).isEqualTo(before.getReviewStatus());
        assertThat(after.getCaseStatus()).isEqualTo(before.getCaseStatus());
        assertThat(after.getApprovedAt()).isEqualTo(before.getApprovedAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(reviewCount(before.getId())).isZero();
        assertThat(auditCount(before.getId())).isZero();
        assertThat(probe.deliveredFor(before.getId())).isEmpty();
    }

    protected int reviewCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM case_reviews WHERE case_id = ?", Integer.class, caseId);
    }

    protected int auditCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ?",
                Integer.class, caseId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        Clock reviewDecisionTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        DecisionEventProbe decisionEventProbe() {
            return new DecisionEventProbe();
        }
    }

    /** Records decision events the way a CT-027 consumer would receive them: after commit only. */
    static class DecisionEventProbe {
        final List<CaseReviewDecidedEvent> delivered = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void decided(CaseReviewDecidedEvent event) {
            delivered.add(event);
        }

        List<CaseReviewDecidedEvent> deliveredFor(long caseId) {
            return delivered.stream().filter(event -> event.caseId() == caseId).toList();
        }
    }
}
