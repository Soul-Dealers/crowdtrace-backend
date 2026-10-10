package com.souldealers.crowdtracebackend.shared.audit.internal;

import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditActorRole;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditVerificationType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(AuditRecorderTest.Fixtures.class)
class AuditRecorderTest {

    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 10, 10, 12, 30);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRecorder recorder;
    @Autowired ScenarioService scenarios;
    @Autowired InnerScenarioService inner;
    @Autowired NewTransactionScenarioService newTransaction;
    @Autowired NoTransactionAdviceScenarioService noTransactionAdvice;
    @Autowired NestedScenarioService nestedScenario;
    @Autowired TransactionTemplate transactions;
    private AbstractPlatformTransactionManager transactionManager;
    private JpaTransactionManager jpaTransactionManager;
    private JpaDialect originalJpaDialect;
    @Autowired AuditEventRepository repository;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void createScratchTable() {
        transactionManager = (AbstractPlatformTransactionManager) transactions.getTransactionManager();
        jpaTransactionManager = (JpaTransactionManager) transactionManager;
        originalJpaDialect = jpaTransactionManager.getJpaDialect();
        jpaTransactionManager.setJpaDialect(new SavepointHibernateJpaDialect());
        transactionManager.setNestedTransactionAllowed(true);
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_recorder_scratch (test_key VARCHAR(80) PRIMARY KEY)");
    }

    @AfterEach
    void disableNestedTransactions() {
        transactionManager.setNestedTransactionAllowed(false);
        jpaTransactionManager.setJpaDialect(originalJpaDialect);
    }

    @Test
    void recordsEventInCallersTransaction() {
        String key = key();
        long eventId = scenarios.record(key, AuditActor.system());

        var row = jdbc.queryForMap("SELECT action, target_type, target_id, actor_id, actor_role, "
                + "metadata->>'userId' AS metadata_user_id, metadata->>'verificationType' AS metadata_type, occurred_at "
                + "FROM audit_events WHERE id = ?", eventId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_recorder_scratch WHERE test_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(row.get("action")).isEqualTo("VERIFICATION.APPROVED");
        assertThat(row.get("target_type")).isEqualTo("VERIFICATION_REQUEST");
        long expectedTarget = targetId(key);
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(expectedTarget);
        assertThat(row.get("actor_id")).isNull();
        assertThat(row.get("actor_role")).isEqualTo("SYSTEM");
        assertThat(row.get("metadata_user_id")).isEqualTo(Long.toString(expectedTarget));
        assertThat(row.get("metadata_type")).isEqualTo("POLICE");
        assertThat(((Timestamp) row.get("occurred_at")).toLocalDateTime()).isEqualTo(STORED_AT);
    }

    @Test
    void failsWithoutTransaction() {
        assertThatThrownBy(() -> recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED,
                731L, verificationMetadata(731L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void rejectsReadOnlyTransaction() {
        assertThatThrownBy(() -> scenarios.recordReadOnly())
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void recorderFailureRollsBackProtectedChange() {
        String key = key();
        assertThatThrownBy(() -> scenarios.recordWithInvalidActor(key))
                .isInstanceOf(AuditRecordingException.class);
        assertNoScratch(key);
        assertThat(countForTarget(targetId(key)))
                .isZero();
    }

    @Test
    void metadataForAnotherActionIsRejected() {
        String key = key();
        assertThatThrownBy(() -> scenarios.mismatchedMetadata(key))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("does not match");
        assertNoScratch(key);
    }

    @Test
    void requiresNewHelperCannotDischargeOuterObligation() {
        String key = key();
        assertThatThrownBy(() -> scenarios.outerCatchingNewTransactionFailure(key, newTransaction))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void correctionEventReferencesOriginal() {
        long originalId = scenarios.record(key(), AuditActor.system());
        long replacementId = scenarios.record(key(), AuditActor.system());
        long correctionId = scenarios.correct(originalId, replacementId);

        assertThat(jdbc.queryForObject("SELECT action FROM audit_events WHERE id = ?", String.class, correctionId))
                .isEqualTo("AUDIT_EVENT.CORRECTED");
        assertThat(((Number) jdbc.queryForObject("SELECT target_id FROM audit_events WHERE id = ?",
                Object.class, correctionId)).longValue()).isEqualTo(originalId);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_events WHERE id = ?", String.class, originalId))
                .isEqualTo("VERIFICATION.APPROVED");
        assertThat(jdbc.queryForObject("SELECT metadata->>'replacementEventId' FROM audit_events WHERE id = ?",
                String.class, correctionId)).isEqualTo(Long.toString(replacementId));
    }

    @Test
    void callerFailureAfterRecordingRollsBackAudit() {
        String key = key();
        assertThatThrownBy(() -> scenarios.recordThenFail(key))
                .isInstanceOf(IllegalStateException.class).hasMessage("caller failed");
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void caughtRecorderFailureInAuditedOperationStillRollsBack() {
        String key = key();
        assertThatThrownBy(() -> scenarios.catchesRecorderFailure(key))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
    }

    @Test
    void caughtRecorderFailureMarksUnauditedTransactionRollbackOnly() {
        String key = key();
        assertThatThrownBy(() -> scenarios.catchesRecorderFailureWithoutAudit(key))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertNoScratch(key);
    }

    @Test
    void caughtMissingFromAuditedHelperWithoutTransactionAdviceStillRollsBack() {
        String key = key();
        assertThatThrownBy(() -> scenarios.catchesMissingFromHelper(key, noTransactionAdvice))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
        assertNoScratch(key + "-helper");
    }

    @Test
    void nestedSavepointRollbackPoisonsOuterAuditedTransaction() {
        String key = key();
        assertThatThrownBy(() -> scenarios.outerAuditedWithNestedRollback(key, nestedScenario))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("savepoint rollback");
        assertNoScratch(key);
        assertNoScratch(key + "-nested");
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void nestedRollbackOnlyPoisonsOuterAuditedTransaction() {
        String key = key();
        assertThatThrownBy(() -> scenarios.nestedRollbackOnly(key))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("savepoint rollback");
        assertNoScratch(key);
        assertNoScratch(key + "-nested");
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void caughtFullyNestedAuditFailureCannotCommitOrLog(CapturedOutput output) {
        String key = key();
        String before = output.getOut();
        assertThatThrownBy(() -> scenarios.catchesNestedFailure(key, nestedScenario))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("savepoint rollback");
        assertNoScratch(key);
        assertNoScratch(key + "-nested");
        assertThat(countForTarget(targetId(key))).isZero();
        assertThat(output.getOut().substring(before.length())).doesNotContain("audit_recorded eventId=");
    }

    @Test
    void unauditedRecordInsideRolledBackSavepointCannotCommitOrLog(CapturedOutput output) {
        String key = key();
        String before = output.getOut();
        assertThatThrownBy(() -> scenarios.catchesUnauditedNestedFailure(key, nestedScenario))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("savepoint rollback");
        assertNoScratch(key);
        assertNoScratch(key + "-nested");
        assertThat(countForTarget(targetId(key))).isZero();
        assertThat(output.getOut().substring(before.length())).doesNotContain("audit_recorded eventId=");
    }

    @Test
    void releasedSavepointCommitsExactlyOneEvent(CapturedOutput output) {
        String key = key();
        long eventId = scenarios.recordsInReleasedSavepoint(key, targetId(key), nestedScenario);
        assertThat(countForTarget(targetId(key))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM audit_events WHERE target_id = ?", Long.class,
                targetId(key))).isEqualTo(eventId);
        assertThat(output.getOut()).contains("audit_recorded eventId=" + eventId);
    }

    @Test
    void programmaticSavepointRollbackPoisonsTransaction() {
        String key = key();
        assertThatThrownBy(() -> scenarios.programmaticSavepointRollback(key))
                .isInstanceOf(AuditRecordingException.class)
                .hasMessageContaining("savepoint rollback");
        assertNoScratch(key);
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void auditedOperationWithoutRecordRollsBack() {
        String key = key();
        assertThatThrownBy(() -> scenarios.omitRecord(key))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
    }

    @Test
    void wrongActionDoesNotDischarge() {
        String key = key();
        assertThatThrownBy(() -> scenarios.recordsWrongAction(key))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
    }

    @Test
    void eachInvocationNeedsItsOwnEvent() {
        String key = key();
        assertThatThrownBy(() -> scenarios.nestedWithMissingInner(key, inner))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void sameActionNestedInvocationDoesNotDischargeOuterInvocation() {
        String key = key();
        assertThatThrownBy(() -> scenarios.sameActionOuterOmission(key, inner))
                .isInstanceOf(AuditMissingException.class);
        assertNoScratch(key);
        assertNoScratch(key + "-inner");
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void multiActionOperationRequiresEveryDeclaredAction() {
        String key = key();
        assertThatThrownBy(() -> scenarios.recordsOnlyOneDeclaredAction(key))
                .isInstanceOf(AuditMissingException.class)
                .hasMessageContaining("CASE.REJECTED");
        assertNoScratch(key);
        assertThat(countForTarget(targetId(key))).isZero();
    }

    @Test
    void multiActionOperationCommitsWhenEveryActionIsRecorded() {
        String key = key();
        scenarios.recordsBothDeclaredActions(key);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_id = ? ORDER BY action",
                String.class, targetId(key))).containsExactly("CASE.APPROVED", "CASE.REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_recorder_scratch WHERE test_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void aspectRequiresTransaction() {
        assertThatThrownBy(() -> scenarios.auditedWithoutTransaction())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    @Test
    void correlationIdCopiedOnlyWhenValid() {
        long validId;
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "abc-123")) {
            validId = scenarios.record(key(), AuditActor.system());
        }
        long invalidId;
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "<script>")) {
            invalidId = scenarios.record(key(), AuditActor.system());
        }
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit_events WHERE id = ?", String.class, validId))
                .isEqualTo("abc-123");
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit_events WHERE id = ?", String.class, invalidId))
                .isNull();
    }

    @Test
    void logsOnlyAfterCommit(CapturedOutput output) {
        long committed = scenarios.record(key(), AuditActor.system());
        assertThat(output.getOut()).contains("audit_recorded eventId=" + committed);
        String beforeRollback = output.getOut();
        String key = key();
        assertThatThrownBy(() -> scenarios.recordThenFail(key)).isInstanceOf(IllegalStateException.class);
        assertThat(output.getOut().substring(beforeRollback.length())).doesNotContain("audit_recorded");
    }

    @Test
    void repositoryRefusesExistingEntity() {
        long id = scenarios.record(key(), AuditActor.system());
        AuditEvent persisted = transactions.execute(status -> entityManager.find(AuditEvent.class, id));
        assertThat(persisted.id()).isEqualTo(id);
        assertThatThrownBy(() -> persisted.metadata().put("unsafe", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.saveAndFlush(persisted)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already has an id");
    }

    private int countForTarget(long targetId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_id = ?", Integer.class, targetId);
    }

    private void assertNoScratch(String key) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_recorder_scratch WHERE test_key = ?",
                Integer.class, key)).isZero();
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static long targetId(String key) {
        return UUID.fromString(key).getMostSignificantBits() & Long.MAX_VALUE;
    }

    private static AuditMetadata verificationMetadata(long targetId) {
        return AuditMetadata.of(AuditAction.VERIFICATION_APPROVED)
                .put("userId", targetId).put("verificationType", AuditVerificationType.POLICE).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        Clock auditTestClock() {
            return Clock.fixed(Instant.parse("2026-10-10T12:30:00Z"), ZoneOffset.UTC);
        }

        @Bean ScenarioService auditScenarioService(
                JdbcTemplate jdbc, AuditRecorder recorder, PlatformTransactionManager transactionManager) {
            return new ScenarioService(jdbc, recorder, transactionManager);
        }

        @Bean InnerScenarioService innerScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            return new InnerScenarioService(jdbc, recorder);
        }

        @Bean NewTransactionScenarioService newTransactionScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            return new NewTransactionScenarioService(jdbc, recorder);
        }

        @Bean NoTransactionAdviceScenarioService noTransactionAdviceScenarioService(JdbcTemplate jdbc) {
            return new NoTransactionAdviceScenarioService(jdbc);
        }

        @Bean NestedScenarioService nestedScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            return new NestedScenarioService(jdbc, recorder);
        }
    }

    public static class ScenarioService {
        private final JdbcTemplate jdbc;
        private final AuditRecorder recorder;

        ScenarioService(JdbcTemplate jdbc, AuditRecorder recorder, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.recorder = recorder;
            this.nestedTransactions = new TransactionTemplate(transactionManager);
            this.nestedTransactions.setPropagationBehavior(Propagation.NESTED.value());
        }

        private final TransactionTemplate nestedTransactions;

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public long record(String key, AuditActor actor) {
            touch(key);
            return recorder.record(actor, AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void recordWithInvalidActor(String key) {
            touch(key);
            recorder.record(AuditActor.user(Long.MAX_VALUE, AuditActorRole.MODERATOR),
                    AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void mismatchedMetadata(String key) {
            touch(key);
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, targetId(key),
                    AuditMetadata.none(AuditAction.USER_DEACTIVATED));
        }

        @Transactional
        @AuditedOperation(AuditAction.AUDIT_EVENT_CORRECTED)
        public long correct(long originalId, long replacementId) {
            return recorder.record(AuditActor.system(), AuditAction.AUDIT_EVENT_CORRECTED, originalId,
                    AuditMetadata.of(AuditAction.AUDIT_EVENT_CORRECTED)
                            .put("reason", com.souldealers.crowdtracebackend.shared.audit.AuditCorrectionReason.WRONG_METADATA)
                            .put("replacementEventId", replacementId)
                            .build());
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void recordThenFail(String key) {
            touch(key);
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
            throw new IllegalStateException("caller failed");
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void catchesRecorderFailure(String key) {
            touch(key);
            try {
                recorder.record(AuditActor.user(Long.MAX_VALUE, AuditActorRole.MODERATOR),
                        AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
            } catch (AuditRecordingException ignored) { }
        }

        @Transactional
        public void catchesRecorderFailureWithoutAudit(String key) {
            touch(key);
            try {
                recorder.record(AuditActor.user(Long.MAX_VALUE, AuditActorRole.MODERATOR),
                        AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
            } catch (AuditRecordingException ignored) { }
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void omitRecord(String key) { touch(key); }

        @Transactional
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void recordsWrongAction(String key) {
            touch(key);
            recorder.record(AuditActor.system(), AuditAction.CASE_REJECTED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_REJECTED).put("reviewId", 17L).build());
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void outerCatchingNewTransactionFailure(String key, NewTransactionScenarioService helper) {
            touch(key);
            try {
                helper.recordThenFail(key, targetId(key));
            } catch (IllegalStateException ignored) { }
        }

        @Transactional
        public void catchesMissingFromHelper(String key, NoTransactionAdviceScenarioService helper) {
            touch(key);
            try {
                helper.omit(key + "-helper");
            } catch (AuditMissingException ignored) { }
        }

        @Transactional
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void outerAuditedWithNestedRollback(String key, NestedScenarioService helper) {
            touch(key);
            try {
                helper.recordThenFail(key, targetId(key));
            } catch (IllegalStateException ignored) { }
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
        }

        @Transactional
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void nestedRollbackOnly(String key) {
            touch(key);
            nestedTransactions.executeWithoutResult(status -> {
                touch(key + "-nested");
                recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId(key),
                        AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
                status.setRollbackOnly();
            });
        }

        @Transactional
        public void catchesNestedFailure(String key, NestedScenarioService helper) {
            touch(key);
            try {
                helper.recordThenFail(key, targetId(key));
            } catch (IllegalStateException ignored) { }
        }

        @Transactional
        public void catchesUnauditedNestedFailure(String key, NestedScenarioService helper) {
            touch(key);
            try {
                helper.recordThenFailWithoutAudit(key, targetId(key));
            } catch (IllegalStateException ignored) { }
        }

        @Transactional
        public long recordsInReleasedSavepoint(String key, long targetId, NestedScenarioService helper) {
            return helper.recordAndReturn(key, targetId);
        }

        @Transactional
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void programmaticSavepointRollback(String key) {
            touch(key);
            var status = TransactionAspectSupport.currentTransactionStatus();
            Object savepoint = status.createSavepoint();
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
            status.rollbackToSavepoint(savepoint);
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void nestedWithMissingInner(String key, InnerScenarioService helper) {
            touch(key);
            helper.record(targetId(key));
            try {
                helper.omit(targetId(key));
            } catch (AuditMissingException ignored) { }
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, targetId(key), verificationMetadata(targetId(key)));
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void sameActionOuterOmission(String key, InnerScenarioService helper) {
            touch(key);
            helper.recordVerification(key, targetId(key));
        }

        @Transactional
        @AuditedOperation({AuditAction.CASE_APPROVED, AuditAction.CASE_REJECTED})
        public void recordsOnlyOneDeclaredAction(String key) {
            touch(key);
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
        }

        @Transactional
        @AuditedOperation({AuditAction.CASE_APPROVED, AuditAction.CASE_REJECTED})
        public void recordsBothDeclaredActions(String key) {
            touch(key);
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
            recorder.record(AuditActor.system(), AuditAction.CASE_REJECTED, targetId(key),
                    AuditMetadata.of(AuditAction.CASE_REJECTED).put("reviewId", 2L).build());
        }

        @Transactional(readOnly = true)
        public void recordReadOnly() {
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, 731L,
                    verificationMetadata(731L));
        }

        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void auditedWithoutTransaction() { }

        private void touch(String key) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key);
        }
    }

    public static class InnerScenarioService {
        private final JdbcTemplate jdbc;
        private final AuditRecorder recorder;

        InnerScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            this.jdbc = jdbc;
            this.recorder = recorder;
        }

        @Transactional
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void record(long targetId) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key());
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId,
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
        }

        @Transactional
        @AuditedOperation(AuditAction.CASE_REJECTED)
        public void omit(long targetId) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key());
        }

        @Transactional
        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void recordVerification(String key, long targetId) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key + "-inner");
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, targetId,
                    verificationMetadata(targetId));
        }
    }

    public static class NewTransactionScenarioService {
        private final JdbcTemplate jdbc;
        private final AuditRecorder recorder;

        NewTransactionScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            this.jdbc = jdbc;
            this.recorder = recorder;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void recordThenFail(String key, long targetId) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key + "-inner");
            recorder.record(AuditActor.system(), AuditAction.VERIFICATION_APPROVED, targetId,
                    verificationMetadata(targetId));
            throw new IllegalStateException("inner failed");
        }
    }

    public static class NoTransactionAdviceScenarioService {
        private final JdbcTemplate jdbc;

        NoTransactionAdviceScenarioService(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @AuditedOperation(AuditAction.VERIFICATION_APPROVED)
        public void omit(String key) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key);
        }
    }

    public static class NestedScenarioService {
        private final JdbcTemplate jdbc;
        private final AuditRecorder recorder;

        NestedScenarioService(JdbcTemplate jdbc, AuditRecorder recorder) {
            this.jdbc = jdbc;
            this.recorder = recorder;
        }

        @Transactional(propagation = Propagation.NESTED)
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public void recordThenFail(String key, long targetId) {
            touch(key + "-nested");
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId,
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
            throw new IllegalStateException("nested failed");
        }

        @Transactional(propagation = Propagation.NESTED)
        public void recordThenFailWithoutAudit(String key, long targetId) {
            touch(key + "-nested");
            recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId,
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
            throw new IllegalStateException("nested failed");
        }

        @Transactional(propagation = Propagation.NESTED)
        @AuditedOperation(AuditAction.CASE_APPROVED)
        public long recordAndReturn(String key, long targetId) {
            touch(key + "-nested");
            return recorder.record(AuditActor.system(), AuditAction.CASE_APPROVED, targetId,
                    AuditMetadata.of(AuditAction.CASE_APPROVED).put("reviewId", 1L).build());
        }

        private void touch(String key) {
            jdbc.update("INSERT INTO audit_recorder_scratch(test_key) VALUES (?)", key);
        }
    }

    /** Test-only dialect adapter: Hibernate keeps owning the connection, while Spring gets its savepoint handle. */
    private static final class SavepointHibernateJpaDialect extends HibernateJpaDialect {

        @Override
        public Object beginTransaction(jakarta.persistence.EntityManager entityManager,
                org.springframework.transaction.TransactionDefinition definition)
                throws jakarta.persistence.PersistenceException, SQLException, TransactionException {
            Object transactionData = super.beginTransaction(entityManager, definition);
            try {
                Connection connection = super.getJdbcConnection(entityManager, false).getConnection();
                return new SavepointTransactionData(transactionData, connection);
            } catch (SQLException exception) {
                super.cleanupTransaction(transactionData);
                throw exception;
            }
        }

        @Override
        public void cleanupTransaction(Object transactionData) {
            if (transactionData instanceof SavepointTransactionData wrapped) {
                super.cleanupTransaction(wrapped.transactionData());
            } else {
                super.cleanupTransaction(transactionData);
            }
        }

        private record SavepointTransactionData(Object transactionData, Connection connection)
                implements SavepointManager {

            @Override
            public Object createSavepoint() {
                try {
                    return connection.setSavepoint();
                } catch (SQLException exception) {
                    throw new TransactionSystemException("Could not create test savepoint", exception);
                }
            }

            @Override
            public void rollbackToSavepoint(Object savepoint) {
                try {
                    connection.rollback((Savepoint) savepoint);
                } catch (SQLException exception) {
                    throw new TransactionSystemException("Could not roll back test savepoint", exception);
                }
            }

            @Override
            public void releaseSavepoint(Object savepoint) {
                try {
                    connection.releaseSavepoint((Savepoint) savepoint);
                } catch (SQLException exception) {
                    throw new TransactionSystemException("Could not release test savepoint", exception);
                }
            }
        }
    }
}
