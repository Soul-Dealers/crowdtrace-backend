package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.Gender;
import com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion;
import com.souldealers.crowdtracebackend.modules.casefile.RestoreCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.SetClosingStatementRequest;
import com.souldealers.crowdtracebackend.modules.casefile.TakeDownCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.notification.CaseRestoredEvent;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.CaseTakenDownEvent;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** A failed or missing audit record rolls back every lifecycle operation: case row, audit rows and events. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LifecycleAuditRollbackTest.Fixtures.class)
class LifecycleAuditRollbackTest extends CasePostgresTestSupport {
    private static final Instant NOW = Instant.parse("2030-02-01T12:30:00Z");
    private static final LocalDateTime TIME = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Autowired private CaseLifecycleService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private LifecycleEventProbe events;
    @MockitoSpyBean private AuditRecorder auditRecorder;

    private String reporterEmail;
    private long reporter;
    private String moderatorEmail;

    enum Failure { RECORDER_THROWS, RECORD_MISSING }

    @BeforeEach
    void actors() {
        events.delivered.clear();
        reporterEmail = email("reporter");
        reporter = user(reporterEmail);
        moderatorEmail = email("moderator");
        jdbc.update("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Moderator', 'MODERATOR', 'ACTIVE')", moderatorEmail);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void reporterStatusChangeRollsBack(Failure failure) {
        CaseRecord before = approved(CaseStatus.MISSING, null);
        assertRolledBack(failure, before, id -> asReporter(() -> service.updateStatus(reporterEmail, id,
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.FOUND_SAFE))));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void adminStatusCorrectionRollsBack(Failure failure) {
        CaseRecord before = approved(CaseStatus.FOUND_SAFE, null);
        assertRolledBack(failure, before, id -> asModerator(() -> service.updateStatusAsAdmin(moderatorEmail, id,
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.CLOSED))));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void closingStatementRollsBack(Failure failure) {
        CaseRecord before = approved(CaseStatus.FOUND_SAFE, null);
        assertRolledBack(failure, before, id -> asReporter(() -> service.setClosingStatement(reporterEmail, id,
                new SetClosingStatementRequest(before.getVersion(), "Thank you all"))));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void takedownRollsBack(Failure failure) {
        CaseRecord before = approved(CaseStatus.MISSING, null);
        assertRolledBack(failure, before, id -> asModerator(() -> service.takeDownAsAdmin(moderatorEmail, id,
                new TakeDownCaseRequest(before.getVersion(), "Hoax report"))));
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void restoreRollsBack(Failure failure) {
        CaseRecord before = takenDown();
        assertRolledBack(failure, before, id -> asModerator(() -> service.restoreAsAdmin(moderatorEmail, id,
                new RestoreCaseRequest(before.getVersion()))));
    }

    @Test
    void reopeningWithoutTheStatementClearAuditRollsBackEverything() {
        CaseRecord before = approved(CaseStatus.FOUND_SAFE, "Found at home");
        doAnswer(call -> 0L).when(target()).record(any(), eq(AuditAction.CASE_CLOSING_STATEMENT_SET), anyLong(),
                any());
        Map<String, Object> snapshot = row(before.getId());

        assertThatThrownBy(() -> asModerator(() -> service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.MISSING))))
                .isInstanceOf(AuditMissingException.class);

        assertThat(row(before.getId())).isEqualTo(snapshot);
        assertThat(auditCount(before.getId())).isZero();
        assertThat(events.delivered).isEmpty();
    }

    private void assertRolledBack(Failure failure, CaseRecord before, Consumer<Long> operation) {
        if (failure == Failure.RECORDER_THROWS) {
            doThrow(new AuditRecordingException("audit storage unavailable")).when(target())
                    .record(any(), any(), anyLong(), any());
        } else {
            doAnswer(call -> 0L).when(target()).record(any(), any(), anyLong(), any());
        }
        Map<String, Object> snapshot = row(before.getId());

        assertThatThrownBy(() -> operation.accept(before.getId())).isInstanceOf(
                failure == Failure.RECORDER_THROWS ? AuditRecordingException.class : AuditMissingException.class);

        assertThat(row(before.getId())).isEqualTo(snapshot);
        assertThat(auditCount(before.getId())).isZero();
        assertThat(events.delivered).isEmpty();
    }

    /** Stubs the spy behind the recorder's MANDATORY transactional proxy. */
    private AuditRecorder target() {
        return AopTestUtils.getUltimateTargetObject(auditRecorder);
    }

    private Map<String, Object> row(long caseId) {
        return jdbc.queryForMap("SELECT review_status, case_status, closing_statement, resolved_at, closed_at, "
                + "takedown_reason, taken_down_by, taken_down_at, version FROM cases WHERE id = ?", caseId);
    }

    private int auditCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ?",
                Integer.class, caseId);
    }

    private void asReporter(Runnable call) {
        as(reporterEmail, "REGISTERED_USER", call);
    }

    private void asModerator(Runnable call) {
        as(moderatorEmail, "MODERATOR", call);
    }

    private static void as(String email, String authority, Runnable call) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null, authority));
        try {
            call.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private CaseRecord approved(CaseStatus status, String statement) {
        return cases.saveAndFlush(base().reviewStatus(ReviewStatus.APPROVED).caseStatus(status)
                .closingStatement(statement)
                .resolvedAt(status == CaseStatus.MISSING ? null : TIME.minusDays(1)).build());
    }

    private CaseRecord takenDown() {
        return cases.saveAndFlush(base().reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(CaseStatus.MISSING)
                .takedownReason("Reason").takenDownBy(reporter).takenDownAt(TIME.minusHours(1)).build());
    }

    private CaseRecord.CaseRecordBuilder base() {
        return CaseRecord.builder().reporterId(reporter).fullName("Test Person").age(24).gender(Gender.MALE)
                .lastSeenDate(LocalDate.of(2026, 10, 1)).region(GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .approvedAt(TIME.minusDays(2)).submittedAt(TIME.minusDays(3)).createdAt(TIME.minusDays(3))
                .updatedAt(TIME.minusDays(3));
    }

    private static String email(String role) {
        return "rollback-" + role + "-" + UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock rollbackTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean LifecycleEventProbe lifecycleEventProbe() { return new LifecycleEventProbe(); }
    }

    static class LifecycleEventProbe {
        final List<Object> delivered = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void statusChanged(CaseStatusChangedEvent event) { delivered.add(event); }
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void takenDown(CaseTakenDownEvent event) { delivered.add(event); }
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void restored(CaseRestoredEvent event) { delivered.add(event); }
    }
}
