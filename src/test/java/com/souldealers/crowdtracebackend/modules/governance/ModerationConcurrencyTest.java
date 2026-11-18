package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A third transaction holds the comment lock while both contenders start, so they always overlap. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModerationConcurrencyTest extends ModerationTestSupport {
    @Autowired private ModerationService moderation;
    @Autowired private CommentReportService reports;
    @Autowired private PlatformTransactionManager transactionManager;

    private long comment;

    @BeforeEach
    void setUp() {
        long author = user(email("author"));
        comment = comment(liveCase(author), author, "abuse");
        report(comment, user(email("r1")), "SPAM", at(10));
    }

    @Test
    void twoModeratorsRemoveConcurrently() throws Exception {
        String a = emailOf(moderator("racer-a"));
        String b = emailOf(moderator("racer-b"));
        List<Object> outcomes = race(
                () -> as(a, "MODERATOR", () -> moderation.remove(a, comment, new RemoveCommentRequest("a"))),
                () -> as(b, "MODERATOR", () -> moderation.remove(b, comment, new RemoveCommentRequest("b"))));
        assertThat(outcomes).filteredOn(ModerationDecisionResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'COMMENT.REMOVED' "
                + "AND target_id = ?", Integer.class, comment)).isOne();
    }

    @Test
    void reportRacingRemovalNeverLeavesOpenReportOnRemovedComment() throws Exception {
        String mod = emailOf(moderator("mod"));
        long late = user(email("late"));
        List<Object> outcomes = race(
                () -> as(mod, "MODERATOR", () -> moderation.remove(mod, comment, new RemoveCommentRequest("gone"))),
                () -> as(emailOf(late), "REGISTERED_USER", () -> reports.report(comment, late,
                        new ReportCommentRequest(ReportReason.SPAM, null))));
        assertThat(outcomes).filteredOn(ModerationDecisionResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof ReportOutcome || o instanceof NotFoundException).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_reports r JOIN comments c ON c.id = r.comment_id "
                + "WHERE r.status = 'OPEN' AND c.moderation_status = 'REMOVED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT report_count FROM comments WHERE id = ?", Integer.class, comment))
                .isZero();
    }

    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> holdCommentLock(locked, release));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Object> a = executor.submit(first);
                Future<Object> b = executor.submit(second);
                awaitLockWaiters(2);
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

    private void holdCommentLock(CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM comments WHERE id = ? FOR UPDATE", Long.class, comment);
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

    /** Both contenders are parked on a row lock: the overlap is forced, not left to scheduling. */
    private void awaitLockWaiters(int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class) < waiters) {
            assertThat(System.nanoTime()).as("both contenders waiting on the comment lock").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static Object as(String email, String authority, Callable<Object> call) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null, authority));
        try {
            return call.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
