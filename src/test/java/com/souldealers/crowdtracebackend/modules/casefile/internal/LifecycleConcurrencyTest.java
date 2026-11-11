package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.AddCasePhotosRequest;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.SetClosingStatementRequest;
import com.souldealers.crowdtracebackend.modules.casefile.TakeDownCaseRequest;
import com.souldealers.crowdtracebackend.modules.casefile.UpdateCaseStatusRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A third transaction holds the case lock while both mutations start, so they always overlap. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleConcurrencyTest extends CasePostgresTestSupport {
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Autowired private CaseLifecycleService lifecycle;
    @Autowired private PlatformTransactionManager transactionManager;

    private long reporterId;
    private String reporterEmail;
    private String moderatorEmail;

    @BeforeEach
    void createActors() {
        reporterEmail = "race-reporter-" + UUID.randomUUID() + "@example.com";
        reporterId = user(reporterEmail);
        moderatorEmail = "race-moderator-" + UUID.randomUUID() + "@example.com";
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Moderator', 'MODERATOR', 'ACTIVE')
                """, moderatorEmail);
    }

    @Test
    void twoStatusChangesFromTheSameVersionGetOneSuccessAndOneConflict() throws Exception {
        long caseId = approvedCase(CaseStatus.MISSING);

        List<Object> outcomes = race(caseId,
                asReporter(() -> lifecycle.updateStatus(reporterEmail, caseId,
                        new UpdateCaseStatusRequest(0L, CaseStatus.FOUND_SAFE))),
                asModerator(() -> lifecycle.updateStatusAsAdmin(moderatorEmail, caseId,
                        new UpdateCaseStatusRequest(0L, CaseStatus.CLOSED))));

        assertOneWinner(outcomes);
        assertThat(auditCount(caseId, "CASE.STATUS_CHANGED")).isEqualTo(1);
    }

    @Test
    void takedownRacingAStatusChangeLetsOnlyOneCommit() throws Exception {
        long caseId = approvedCase(CaseStatus.MISSING);

        List<Object> outcomes = race(caseId, takeDown(caseId),
                asReporter(() -> lifecycle.updateStatus(reporterEmail, caseId,
                        new UpdateCaseStatusRequest(0L, CaseStatus.FOUND_SAFE))));

        assertOneWinner(outcomes);
        assertThat(auditCount(caseId, "CASE.TAKEN_DOWN") + auditCount(caseId, "CASE.STATUS_CHANGED")).isEqualTo(1);
    }

    @Test
    void takedownRacingAClosingStatementLetsOnlyOneCommit() throws Exception {
        long caseId = approvedCase(CaseStatus.FOUND_SAFE);

        List<Object> outcomes = race(caseId, takeDown(caseId),
                asReporter(() -> lifecycle.setClosingStatement(reporterEmail, caseId,
                        new SetClosingStatementRequest(0L, "Thank you all"))));

        assertOneWinner(outcomes);
        assertThat(auditCount(caseId, "CASE.TAKEN_DOWN") + auditCount(caseId, "CASE.CLOSING_STATEMENT_SET"))
                .isEqualTo(1);
    }

    @Test
    void takedownRacingAPhotoBatchNeverLeavesAPhotoOnATakenDownCase() throws Exception {
        long caseId = approvedCase(CaseStatus.MISSING);
        long photo = upload();

        List<Object> outcomes = race(caseId, takeDown(caseId),
                asReporter(() -> lifecycle.addPhotos(reporterEmail, caseId,
                        new AddCasePhotosRequest(0L, List.of(photo)))));

        assertOneWinner(outcomes);
        String reviewStatus = jdbc.queryForObject("SELECT review_status FROM cases WHERE id = ?", String.class,
                caseId);
        int attached = livePhotos(caseId);
        if ("TAKEN_DOWN".equals(reviewStatus)) {
            assertThat(attached).isZero();
            assertThat(auditCount(caseId, "CASE.PHOTO_ADDED")).isZero();
        } else {
            assertThat(attached).isEqualTo(1);
            assertThat(auditCount(caseId, "CASE.TAKEN_DOWN")).isZero();
        }
    }

    @Test
    void twoPhotoBatchesCannotExceedTheCap() throws Exception {
        long caseId = approvedCase(CaseStatus.MISSING);
        List<Long> first = List.of(upload(), upload(), upload());
        List<Long> second = List.of(upload(), upload(), upload());

        List<Object> outcomes = race(caseId,
                asReporter(() -> lifecycle.addPhotos(reporterEmail, caseId, new AddCasePhotosRequest(0L, first))),
                asReporter(() -> lifecycle.addPhotos(reporterEmail, caseId, new AddCasePhotosRequest(0L, second))));

        assertOneWinner(outcomes);
        assertThat(livePhotos(caseId)).isEqualTo(3);
        assertThat(auditCount(caseId, "CASE.PHOTO_ADDED")).isEqualTo(3);
    }

    private Callable<Object> takeDown(long caseId) {
        return asModerator(() -> lifecycle.takeDownAsAdmin(moderatorEmail, caseId,
                new TakeDownCaseRequest(0L, "Hoax report")));
    }

    private List<Object> race(long caseId, Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> holdCaseLock(caseId, locked, release));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Object> a = executor.submit(first);
                Future<Object> b = executor.submit(second);
                awaitBothBlockedOnTheCaseLock();
                assertThat(a.isDone()).isFalse();
                assertThat(b.isDone()).isFalse();
                release.countDown();
                for (Future<Object> future : List.of(a, b)) {
                    try {
                        outcomes.add(future.get(20, TimeUnit.SECONDS));
                    } catch (ExecutionException failure) {
                        outcomes.add(failure.getCause());
                    }
                }
            } finally {
                release.countDown();
                blocker.get(20, TimeUnit.SECONDS);
            }
        }
        return outcomes;
    }

    private static void assertOneWinner(List<Object> outcomes) {
        assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !(outcome instanceof Throwable)).hasSize(1);
    }

    /** Holds the case row lock in its own transaction so both mutations must queue behind it. */
    private void holdCaseLock(long caseId, CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM cases WHERE id = ? FOR UPDATE", Long.class, caseId);
            locked.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lock holder was never released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
    }

    /** Both mutation transactions are parked on a row lock: the overlap is forced, not left to scheduling. */
    private void awaitBothBlockedOnTheCaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class) < 2) {
            assertThat(System.nanoTime()).as("both mutations waiting on the case lock").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private Callable<Object> asReporter(Callable<Object> call) {
        return as(reporterEmail, "REGISTERED_USER", call);
    }

    private Callable<Object> asModerator(Callable<Object> call) {
        return as(moderatorEmail, "MODERATOR", call);
    }

    private static Callable<Object> as(String email, String authority, Callable<Object> call) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null,
                    authority));
            try {
                return call.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private long approvedCase(CaseStatus outcome) {
        LocalDateTime resolvedAt = outcome == CaseStatus.MISSING ? null : APPROVED_AT.plusHours(1);
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region, last_seen_location,
                    physical_description, clothing, circumstances, public_contact_number, review_status, case_status,
                    approved_at, resolved_at, submitted_at, created_at, updated_at, version)
                VALUES (?, 'Kofi Mensah', 30, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA', 'Madina', 'Slim',
                    'Blue shirt', 'Did not return', '+233200000000', 'APPROVED', ?, ?, ?, ?, ?, ?, 0) RETURNING id
                """, Long.class, reporterId, outcome.name(), APPROVED_AT, resolvedAt, APPROVED_AT.minusDays(1),
                APPROVED_AT.minusDays(1), APPROVED_AT);
    }

    private long upload() {
        return jdbc.queryForObject("""
                INSERT INTO case_files (uploaded_by, purpose, visibility, storage_key, content_type, size_bytes,
                    checksum_sha256, uploaded_at)
                VALUES (?, 'PHOTO', 'PUBLIC', ?, 'image/jpeg', 20, ?, ?) RETURNING id
                """, Long.class, reporterId, "photos/" + UUID.randomUUID(), "a".repeat(64), APPROVED_AT);
    }

    private int livePhotos(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM case_files WHERE case_id = ? AND purpose = 'PHOTO' "
                + "AND deleted_at IS NULL", Integer.class, caseId);
    }

    private int auditCount(long caseId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ? "
                + "AND action = ?", Integer.class, caseId, action);
    }
}
