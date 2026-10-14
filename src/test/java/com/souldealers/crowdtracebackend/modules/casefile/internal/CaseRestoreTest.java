package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.RestoreCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.TakeDownCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseRestoredEvent;
import com.souldealers.crowdtracebackend.modules.notification.CaseTakenDownEvent;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
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
@Import(CaseRestoreTest.Fixtures.class)
class CaseRestoreTest extends CasePostgresTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 2, 2, 12, 30);
    @Autowired private CaseLifecycleService service;
    @Autowired private CaseQueryService queryService;
    @Autowired private CaseRecordRepository cases;
    @Autowired private LifecycleEvents events;
    private long reporter;
    private String moderatorEmail;
    private long moderator;
    private String secondModeratorEmail;
    private long secondModerator;

    @BeforeEach
    void actors() {
        events.restored.clear();
        events.takenDown.clear();
        reporter = user(email("reporter"));
        moderatorEmail = email("moderator");
        moderator = actor(moderatorEmail, UserRoles.MODERATOR);
        secondModeratorEmail = email("second-moderator");
        secondModerator = actor(secondModeratorEmail, UserRoles.MODERATOR);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void restoreReapprovesWhilePreservingOutcomeStatementAndLatestTakedownDetails() {
        CaseRecord before = approved(CaseStatus.FOUND_DECEASED, reporter, "Respectful statement");
        long initialVersion = before.getVersion();
        service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(initialVersion, "Confirmed hoax"));
        CaseRecord takenDown = cases.findById(before.getId()).orElseThrow();
        long originalTakedownActor = takenDown.getTakenDownBy();
        LocalDateTime originalTakedownAt = takenDown.getTakenDownAt();
        events.restored.clear();
        events.takenDown.clear();

        var response = service.restoreAsAdmin(moderatorEmail, before.getId(),
                new RestoreCaseRequest(takenDown.getVersion()));

        CaseRecord restored = cases.findById(before.getId()).orElseThrow();
        assertThat(restored.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(restored.getCaseStatus()).isEqualTo(CaseStatus.FOUND_DECEASED);
        assertThat(restored.getClosingStatement()).isEqualTo("Respectful statement");
        assertThat(restored.getTakedownReason()).isEqualTo("Confirmed hoax");
        assertThat(restored.getTakenDownBy()).isEqualTo(originalTakedownActor);
        assertThat(restored.getTakenDownAt()).isEqualTo(originalTakedownAt);
        assertThat(restored.getVersion()).isEqualTo(takenDown.getVersion() + 1);
        assertThat(response.caseVersion()).isEqualTo(restored.getVersion());
        assertThat(queryService.getPublicCase(before.getId()).caseStatus()).isEqualTo(CaseStatus.FOUND_DECEASED);
        assertThat(queryService.getPublicCase(before.getId()).closingStatement()).isEqualTo("Respectful statement");
        assertThat(auditCount(before.getId(), "CASE.TAKEN_DOWN")).isEqualTo(1);
        assertThat(auditCount(before.getId(), "CASE.RESTORED")).isEqualTo(1);
        assertThat(events.restored).singleElement().satisfies(event -> {
            assertThat(event.caseId()).isEqualTo(before.getId());
            assertThat(event.reporterId()).isEqualTo(reporter);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
        assertThat(events.takenDown).isEmpty();
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void secondTakedownReplacesLatestAttributionAndReasonAfterRestore() {
        CaseRecord before = approved(CaseStatus.FOUND_SAFE, reporter, null);
        service.takeDownAsAdmin(moderatorEmail, before.getId(),
                new TakeDownCaseRequest(before.getVersion(), "First reason"));
        CaseRecord takenDown = cases.findById(before.getId()).orElseThrow();
        service.restoreAsAdmin(moderatorEmail, before.getId(), new RestoreCaseRequest(takenDown.getVersion()));
        CaseRecord restored = cases.findById(before.getId()).orElseThrow();

        service.takeDownAsAdmin(secondModeratorEmail, before.getId(),
                new TakeDownCaseRequest(restored.getVersion(), "Updated reason"));

        CaseRecord takenDownAgain = cases.findById(before.getId()).orElseThrow();
        assertThat(takenDownAgain.getReviewStatus()).isEqualTo(ReviewStatus.TAKEN_DOWN);
        assertThat(takenDownAgain.getTakedownReason()).isEqualTo("Updated reason");
        assertThat(takenDownAgain.getTakenDownBy()).isEqualTo(secondModerator);
        assertThat(takenDownAgain.getTakenDownAt()).isEqualTo(NOW);
        assertThat(auditCount(before.getId(), "CASE.TAKEN_DOWN")).isEqualTo(2);
        assertThat(auditCount(before.getId(), "CASE.RESTORED")).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void rejectsRepeatRestoreStaleVersionAndRestoringAnApprovedCase() {
        CaseRecord before = approved(CaseStatus.CLOSED, reporter, null);
        assertThatThrownBy(() -> service.restoreAsAdmin(moderatorEmail, before.getId(),
                new RestoreCaseRequest(before.getVersion()))).isInstanceOf(ConflictException.class);
        CaseRecord takenDown = takenDown(CaseStatus.CLOSED, reporter, moderator, "Reason");
        assertThatThrownBy(() -> service.restoreAsAdmin(moderatorEmail, takenDown.getId(),
                new RestoreCaseRequest(takenDown.getVersion() + 1))).isInstanceOf(ConflictException.class);
        service.restoreAsAdmin(moderatorEmail, takenDown.getId(), new RestoreCaseRequest(takenDown.getVersion()));
        CaseRecord restored = cases.findById(takenDown.getId()).orElseThrow();
        assertThatThrownBy(() -> service.restoreAsAdmin(moderatorEmail, takenDown.getId(),
                new RestoreCaseRequest(restored.getVersion()))).isInstanceOf(ConflictException.class);
        assertThat(auditCount(before.getId(), "CASE.RESTORED")).isZero();
        assertThat(auditCount(takenDown.getId(), "CASE.RESTORED")).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void moderatorCannotRestoreTheirOwnCase() {
        CaseRecord ownTakenDown = takenDown(CaseStatus.CLOSED, moderator, secondModerator, "Reason");
        assertThatThrownBy(() -> service.restoreAsAdmin(moderatorEmail, ownTakenDown.getId(),
                new RestoreCaseRequest(ownTakenDown.getVersion()))).isInstanceOf(AccessDeniedException.class);
        assertThat(auditCount(ownTakenDown.getId(), "CASE.RESTORED")).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock lifecycleTestClock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
        @Bean LifecycleEvents lifecycleEvents() { return new LifecycleEvents(); }
    }

    static class LifecycleEvents {
        final List<CaseRestoredEvent> restored = new CopyOnWriteArrayList<>();
        final List<CaseTakenDownEvent> takenDown = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(CaseRestoredEvent event) { restored.add(event); }
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(CaseTakenDownEvent event) { takenDown.add(event); }
    }

    private CaseRecord approved(CaseStatus status, long owner, String statement) {
        return cases.saveAndFlush(aCase(owner).reviewStatus(ReviewStatus.APPROVED).caseStatus(status)
                .closingStatement(statement).approvedAt(NOW.minusDays(2))
                .resolvedAt(status == CaseStatus.MISSING ? null : NOW.minusDays(1))
                .closedAt(status == CaseStatus.CLOSED ? NOW.minusHours(3) : null)
                .submittedAt(NOW.minusDays(3)).createdAt(NOW.minusDays(3)).updatedAt(NOW.minusDays(3)).build());
    }

    private CaseRecord takenDown(CaseStatus status, long owner, long takedownActor, String reason) {
        return cases.saveAndFlush(aCase(owner).reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(status)
                .takedownReason(reason).takenDownBy(takedownActor).takenDownAt(NOW.minusHours(1))
                .approvedAt(NOW.minusDays(2)).resolvedAt(NOW.minusDays(1))
                .closedAt(status == CaseStatus.CLOSED ? NOW.minusHours(3) : null)
                .submittedAt(NOW.minusDays(3)).createdAt(NOW.minusDays(3)).updatedAt(NOW.minusDays(3)).build());
    }

    private long actor(String email, UserRoles role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, display_name, role, account_status) "
                + "VALUES (?, 'x', 'Actor', ?, 'ACTIVE') RETURNING id", Long.class, email, role.name());
    }
    private int auditCount(long caseId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type='CASE' AND target_id=? "
                + "AND action=?", Integer.class, caseId, action);
    }
    private static String email(String role) { return "restore-" + role + "-" + UUID.randomUUID() + "@example.com"; }
}
