package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.SetClosingStatementRequest;
import com.souldealers.crowdtracebackend.modules.casefile.TakeDownCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseTakenDownEvent;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CaseTakedownTest.Fixtures.class)
class CaseTakedownTest extends CasePostgresTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 2, 1, 12, 30);
    @Autowired private CaseLifecycleService service;
    @Autowired private CaseQueryService queryService;
    @Autowired private CaseRecordRepository cases;
    @Autowired private TakedownEventProbe events;
    private long reporter;
    private String reporterEmail;
    private String moderatorEmail;
    private long moderator;

    @BeforeEach
    void actors() {
        events.delivered.clear();
        reporterEmail = email("reporter");
        reporter = user(reporterEmail);
        moderatorEmail = email("moderator");
        moderator = actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void moderatorCanTakeDownEveryOutcomeWithoutChangingOutcomeStatementOrReviews() {
        for (CaseStatus status : CaseStatus.values()) {
            String statement = status == CaseStatus.MISSING ? null : "Preserved statement";
            CaseRecord before = approved(status, statement, reporter);
            long photoId = attachedPhoto(before.getId());
            int reviewsBefore = reviewCount(before.getId());
            CaseLifecycleResponse response = service.takeDownAsAdmin(moderatorEmail, before.getId(),
                    new TakeDownCaseRequest(before.getVersion(), "  Confirmed hoax  "));

            CaseRecord after = cases.findById(before.getId()).orElseThrow();
            assertThat(after.getReviewStatus()).isEqualTo(ReviewStatus.TAKEN_DOWN);
            assertThat(after.getCaseStatus()).isEqualTo(status);
            assertThat(after.getClosingStatement()).isEqualTo(statement);
            assertThat(after.getTakedownReason()).isEqualTo("Confirmed hoax");
            assertThat(after.getTakenDownBy()).isEqualTo(moderator);
            assertThat(after.getTakenDownAt()).isEqualTo(NOW);
            assertThat(after.getResolvedAt()).isEqualTo(before.getResolvedAt());
            assertThat(after.getClosedAt()).isEqualTo(before.getClosedAt());
            assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
            assertThat(response.caseVersion()).isEqualTo(after.getVersion());
            assertThat(reviewCount(before.getId())).isEqualTo(reviewsBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM case_files WHERE id=? AND case_id=? "
                    + "AND attached_at IS NOT NULL AND deleted_at IS NULL", Integer.class, photoId, before.getId()))
                    .isEqualTo(1);
            assertThat(events.delivered).anySatisfy(event -> {
                assertThat(event.caseId()).isEqualTo(before.getId());
                assertThat(event.reporterId()).isEqualTo(reporter);
                assertThat(event.occurredAt()).isEqualTo(NOW);
            });
            assertThat(jdbc.queryForList("SELECT action, metadata::text AS metadata FROM audit_events "
                    + "WHERE target_type='CASE' AND target_id=?", before.getId())).singleElement()
                    .satisfies(row -> assertThat(row).containsEntry("action", "CASE.TAKEN_DOWN")
                            .containsEntry("metadata", "{}"));
        }
        assertThat(events.delivered).hasSize(CaseStatus.values().length);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void repeatedTakedownIsConflictAndTakenDownCasesAreNotPublicOrEditable() {
        CaseRecord before = approved(CaseStatus.FOUND_SAFE, "Statement");
        service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(before.getVersion(), "Reason"));
        CaseRecord takenDown = cases.findById(before.getId()).orElseThrow();
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(takenDown.getVersion(), "Second reason")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> queryService.getPublicCase(before.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateStatusAsAdmin(moderatorEmail, before.getId(),
                new UpdateCaseStatusRequest(takenDown.getVersion(), CaseStatus.CLOSED)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.setClosingStatementAsAdmin(moderatorEmail, before.getId(),
                new SetClosingStatementRequest(takenDown.getVersion(), "Changed statement")))
                .isInstanceOf(ConflictException.class);
        assertThat(auditCount(before.getId())).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void rejectsMissingBlankAndTooLongReasonsWithoutMutation() {
        CaseRecord before = approved(CaseStatus.MISSING, null);
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, before.getId(), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(before.getVersion(), " \u2003 "))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(before.getVersion(), "x".repeat(2001))))
                .isInstanceOf(ValidationException.class);
        assertThat(cases.findById(before.getId()).orElseThrow().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(auditCount(before.getId())).isZero();
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void rejectsModeratorTakingDownOwnCaseAndStaleVersions() {
        CaseRecord own = approved(CaseStatus.MISSING, null, moderator);
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, own.getId(),
                new TakeDownCaseRequest(own.getVersion(), "Reason")))
                .isInstanceOf(AccessDeniedException.class);
        CaseRecord stale = approved(CaseStatus.MISSING, null);
        assertThatThrownBy(() -> service.takeDownAsAdmin(moderatorEmail, stale.getId(),
                new TakeDownCaseRequest(stale.getVersion() + 1, "Reason"))).isInstanceOf(ConflictException.class);
        assertThat(auditCount(own.getId())).isZero();
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void registeredUserCannotCallModeratorTakedownService() {
        CaseRecord own = approved(CaseStatus.MISSING, null, reporter);
        assertThatThrownBy(() -> service.takeDownAsAdmin(reporterEmail, own.getId(),
                new TakeDownCaseRequest(own.getVersion(), "Reason"))).isInstanceOf(AccessDeniedException.class);
        assertThat(auditCount(own.getId())).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock lifecycleTestClock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
        @Bean TakedownEventProbe takedownEventProbe() { return new TakedownEventProbe(); }
    }

    static class TakedownEventProbe {
        final List<CaseTakenDownEvent> delivered = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(CaseTakenDownEvent event) { delivered.add(event); }
    }

    private CaseRecord approved(CaseStatus status, String statement) {
        return approved(status, statement, reporter);
    }

    private CaseRecord approved(CaseStatus status, String statement, long owner) {
        LocalDateTime resolved = status == CaseStatus.MISSING ? null : NOW.minusDays(1);
        return cases.saveAndFlush(aCase(owner).reviewStatus(ReviewStatus.APPROVED).caseStatus(status)
                .closingStatement(statement).approvedAt(NOW.minusDays(2)).resolvedAt(resolved)
                .closedAt(status == CaseStatus.CLOSED ? NOW.minusHours(3) : null)
                .submittedAt(NOW.minusDays(3)).createdAt(NOW.minusDays(3)).updatedAt(NOW.minusDays(3)).build());
    }

    private long actor(String email, UserRoles role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id", Long.class, email, role.name());
    }
    private int reviewCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM case_reviews WHERE case_id=?", Integer.class, caseId);
    }
    private long attachedPhoto(long caseId) {
        return jdbc.queryForObject("INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key, "
                + "content_type, size_bytes, checksum_sha256, uploaded_at, attached_at) "
                + "VALUES (?, ?, 'PHOTO', 'PUBLIC', ?, 'image/jpeg', 32, ?, ?, ?) RETURNING id", Long.class,
                caseId, reporter, "lifecycle-photo-" + UUID.randomUUID(), "a".repeat(64), NOW.minusDays(1), NOW);
    }
    private int auditCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type='CASE' AND target_id=?",
                Integer.class, caseId);
    }
    private static String email(String role) { return "takedown-" + role + "-" + UUID.randomUUID() + "@example.com"; }
}
