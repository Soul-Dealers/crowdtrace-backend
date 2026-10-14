package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CaseStatusUpdateTest.Fixtures.class)
class CaseStatusUpdateTest extends CasePostgresTestSupport {
    private static final Instant NOW = Instant.parse("2030-02-01T12:30:00Z");
    private static final LocalDateTime TIME = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    @Autowired private CaseLifecycleService service;
    @Autowired private CaseRecordRepository cases;
    @Autowired private StatusEventProbe events;
    private String reporterEmail;
    private long reporter;
    private String moderatorEmail;

    @BeforeEach
    void actors() {
        events.delivered.clear();
        reporterEmail = email("reporter");
        reporter = user(reporterEmail);
        moderatorEmail = email("moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void reporterCanResolveMissingCaseAndRecordsMappedAuditAndEvent() {
        CaseRecord before = approved(CaseStatus.MISSING);

        CaseLifecycleResponse response = service.updateStatus(reporterEmail, before.getId(),
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.FOUND_SAFE));

        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getCaseStatus()).isEqualTo(CaseStatus.FOUND_SAFE);
        assertThat(after.getResolvedAt()).isEqualTo(TIME);
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(response.caseVersion()).isEqualTo(after.getVersion());
        assertThat(jdbc.queryForList("SELECT action, actor_id, actor_role, metadata::text AS metadata FROM audit_events "
                + "WHERE target_type = 'CASE' AND target_id = ?", before.getId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("action", "CASE.STATUS_CHANGED").containsEntry("actor_id", reporter)
                    .containsEntry("actor_role", "REGISTERED_USER");
            assertThat(row.get("metadata").toString()).contains("\"fromStatus\": \"MISSING\"",
                    "\"toStatus\": \"FOUND_SAFE\"");
        });
        assertThat(events.delivered).singleElement().satisfies(event -> {
            assertThat(event.caseId()).isEqualTo(before.getId());
            assertThat(event.actorKind()).isEqualTo(CaseStatusChangedEvent.ActorKind.REPORTER);
            assertThat(event.fromStatus()).isEqualTo(NotifiedCaseStatus.MISSING);
            assertThat(event.toStatus()).isEqualTo(NotifiedCaseStatus.FOUND_SAFE);
            assertThat(event.occurredAt()).isEqualTo(TIME);
        });
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void moderatorCanCorrectResolvedOutcomesAndReopeningClearsResolutionTimesAndStatement() {
        CaseRecord before = approved(CaseStatus.CLOSED, "A closing note");

        service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.MISSING));

        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getCaseStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(after.getResolvedAt()).isNull();
        assertThat(after.getClosedAt()).isNull();
        assertThat(after.getClosingStatement()).isNull();
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_type = 'CASE' AND target_id = ? "
                + "ORDER BY id", before.getId())).extracting(row -> row.get("action"))
                .containsExactly("CASE.CLOSING_STATEMENT_SET", "CASE.STATUS_CHANGED");
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void resolutionClockIsKeptAcrossCorrectionsAndClosedAtTracksClosingOnly() {
        CaseRecord before = approved(CaseStatus.MISSING);
        service.updateStatus(reporterEmail, before.getId(),
                new UpdateCaseStatusRequest(before.getVersion(), CaseStatus.FOUND_SAFE));
        CaseRecord resolved = cases.findById(before.getId()).orElseThrow();
        assertThat(resolved.getResolvedAt()).isEqualTo(TIME);
        service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(resolved.getVersion(), CaseStatus.FOUND_DECEASED));
        CaseRecord corrected = cases.findById(before.getId()).orElseThrow();
        assertThat(corrected.getResolvedAt()).isEqualTo(TIME);
        assertThat(corrected.getClosedAt()).isNull();
        service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(corrected.getVersion(), CaseStatus.CLOSED));
        CaseRecord closed = cases.findById(before.getId()).orElseThrow();
        assertThat(closed.getResolvedAt()).isEqualTo(TIME);
        assertThat(closed.getClosedAt()).isEqualTo(TIME);
        service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(closed.getVersion(), CaseStatus.FOUND_SAFE));
        CaseRecord reopened = cases.findById(before.getId()).orElseThrow();
        assertThat(reopened.getResolvedAt()).isEqualTo(TIME);
        assertThat(reopened.getClosedAt()).isNull();
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void rejectsForeignCasesAndStaleVersionsWithoutWriting() {
        CaseRecord own = approved(CaseStatus.MISSING);
        CaseRecord foreign = approved(CaseStatus.MISSING, user(email("other")));

        assertThatThrownBy(() -> service.updateStatus(reporterEmail, foreign.getId(),
                new UpdateCaseStatusRequest(foreign.getVersion(), CaseStatus.CLOSED)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateStatus(reporterEmail, own.getId(),
                new UpdateCaseStatusRequest(own.getVersion() + 1, CaseStatus.CLOSED)))
                .isInstanceOf(ConflictException.class);
        assertThat(auditCount(own.getId())).isZero();
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void everyOutcomeCellIsEnforcedThroughReporterAndAdminServiceProxies() {
        for (CaseStatus from : CaseStatus.values()) {
            for (CaseStatus to : CaseStatus.values()) {
                boolean reporterAllowed = from == CaseStatus.MISSING && to != CaseStatus.MISSING;
                CaseRecord reporterCase = approved(from);
                assertStatusAttempt(reporterCase, to, reporterAllowed,
                        () -> service.updateStatus(reporterEmail, reporterCase.getId(),
                                new UpdateCaseStatusRequest(reporterCase.getVersion(), to)));

                boolean adminAllowed = from != to;
                CaseRecord adminCase = approved(from);
                assertStatusAttempt(adminCase, to, adminAllowed,
                        () -> service.updateStatusAsAdmin(moderatorEmail, adminCase.getId(),
                                new UpdateCaseStatusRequest(adminCase.getVersion(), to)));
            }
        }
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void reporterCannotCorrectResolvedOutcomesOrChangePendingRejectedOrTakenDownCases() {
        for (CaseStatus status : List.of(CaseStatus.FOUND_SAFE, CaseStatus.FOUND_DECEASED, CaseStatus.CLOSED)) {
            CaseRecord resolved = approved(status);
            assertThatThrownBy(() -> service.updateStatus(reporterEmail, resolved.getId(),
                    new UpdateCaseStatusRequest(resolved.getVersion(), CaseStatus.MISSING)))
                    .isInstanceOf(ConflictException.class);
        }
        for (ReviewStatus review : List.of(ReviewStatus.SUBMITTED, ReviewStatus.REJECTED, ReviewStatus.TAKEN_DOWN)) {
            CaseRecord blocked = caseWith(review, review == ReviewStatus.TAKEN_DOWN ? CaseStatus.MISSING : null, reporter);
            assertThatThrownBy(() -> service.updateStatus(reporterEmail, blocked.getId(),
                    new UpdateCaseStatusRequest(blocked.getVersion(), CaseStatus.CLOSED)))
                    .isInstanceOf(ConflictException.class);
            assertThat(auditCount(blocked.getId())).isZero();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock lifecycleTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean StatusEventProbe statusEventProbe() { return new StatusEventProbe(); }
    }

    static class StatusEventProbe {
        final List<CaseStatusChangedEvent> delivered = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(CaseStatusChangedEvent event) { delivered.add(event); }
    }

    private CaseRecord approved(CaseStatus status) { return approved(status, reporter); }
    private CaseRecord approved(CaseStatus status, String statement) { return approved(status, reporter, statement); }
    private CaseRecord approved(CaseStatus status, long owner) { return approved(status, owner, null); }
    private CaseRecord approved(CaseStatus status, long owner, String statement) {
        return saveCase(CaseRecord.builder().reporterId(owner).fullName("Test Person").age(24)
                .gender(com.souldealers.crowdtracebackend.modules.casefile.Gender.MALE)
                .lastSeenDate(LocalDate.of(2026, 10, 1))
                .region(com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(status).closingStatement(statement)
                .approvedAt(TIME.minusDays(2)).resolvedAt(status == CaseStatus.MISSING ? null : TIME.minusDays(1))
                .closedAt(status == CaseStatus.CLOSED ? TIME.minusHours(3) : null)
                .submittedAt(TIME.minusDays(3)).createdAt(TIME.minusDays(3)).updatedAt(TIME.minusDays(3)).build());
    }

    private CaseRecord caseWith(ReviewStatus status, CaseStatus caseStatus, long owner) {
        return saveCase(CaseRecord.builder().reporterId(owner).fullName("Test Person").age(24)
                .gender(com.souldealers.crowdtracebackend.modules.casefile.Gender.MALE)
                .lastSeenDate(LocalDate.of(2026, 10, 1))
                .region(com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .reviewStatus(status).caseStatus(caseStatus).approvedAt(TIME.minusDays(2))
                .takedownReason(status == ReviewStatus.TAKEN_DOWN ? "Reason" : null)
                .takenDownBy(status == ReviewStatus.TAKEN_DOWN ? owner : null)
                .takenDownAt(status == ReviewStatus.TAKEN_DOWN ? TIME.minusHours(1) : null)
                .submittedAt(TIME.minusDays(3)).createdAt(TIME.minusDays(3)).updatedAt(TIME.minusDays(3)).build());
    }

    private CaseRecord saveCase(CaseRecord record) { return cases.saveAndFlush(record); }
    private long actor(String email, UserRoles role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id", Long.class, email, role.name());
    }
    private static String email(String role) { return "lifecycle-" + role + "-" + UUID.randomUUID() + "@example.com"; }
    private int auditCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ?",
                Integer.class, caseId);
    }

    private void assertStatusAttempt(CaseRecord before, CaseStatus target, boolean allowed,
                                     Runnable call) {
        if (allowed) {
            call.run();
            assertThat(cases.findById(before.getId()).orElseThrow().getCaseStatus()).isEqualTo(target);
        } else {
            assertThatThrownBy(call::run).isInstanceOf(ConflictException.class);
            assertThat(cases.findById(before.getId()).orElseThrow().getCaseStatus()).isEqualTo(before.getCaseStatus());
            assertThat(auditCount(before.getId())).isZero();
        }
    }
}
