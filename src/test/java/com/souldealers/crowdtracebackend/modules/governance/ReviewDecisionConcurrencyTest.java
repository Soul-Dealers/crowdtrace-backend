package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** A third transaction holds the case lock while both decisions start, so they always overlap. */
class ReviewDecisionConcurrencyTest extends ReviewDecisionTestSupport {

    @Test
    void twoModeratorsApprovingAtOnceGetOneSuccessAndOneConflict() throws Exception {
        race(this::approve, this::approve, ReviewStatus.APPROVED);
    }

    @Test
    void anApprovalRacingARejectionGetsOneSuccessAndOneConflict() throws Exception {
        race(this::approve, this::reject, null);
    }

    @Test
    void twoModeratorsRejectingAtOnceGetOneSuccessAndOneConflict() throws Exception {
        race(this::reject, this::reject, ReviewStatus.REJECTED);
    }

    private void race(BiFunction<String, CaseRecord, CaseReviewDecisionResponse> first,
                      BiFunction<String, CaseRecord, CaseReviewDecisionResponse> second,
                      ReviewStatus expectedStatus) throws Exception {
        String firstEmail = email("racer-a");
        String secondEmail = email("racer-b");
        actor(firstEmail, UserRoles.MODERATOR);
        actor(secondEmail, UserRoles.MODERATOR);
        CaseRecord before = pendingCaseWithReport();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> holdCaseLock(before.getId(), locked, release));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<CaseReviewDecisionResponse> a = executor.submit(() -> asModerator(firstEmail,
                        () -> first.apply(firstEmail, before)));
                Future<CaseReviewDecisionResponse> b = executor.submit(() -> asModerator(secondEmail,
                        () -> second.apply(secondEmail, before)));
                awaitBothBlockedOnTheCaseLock();
                assertThat(a.isDone()).isFalse();
                assertThat(b.isDone()).isFalse();
                release.countDown();
                for (Future<CaseReviewDecisionResponse> future : List.of(a, b)) {
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

        assertThat(outcomes).filteredOn(CaseReviewDecisionResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(1);
        CaseReviewDecisionResponse winner = outcomes.stream().filter(CaseReviewDecisionResponse.class::isInstance)
                .map(CaseReviewDecisionResponse.class::cast).findFirst().orElseThrow();
        if (expectedStatus != null) {
            assertThat(winner.reviewStatus()).isEqualTo(expectedStatus);
        }
        assertThat(cases.findById(before.getId()).orElseThrow().getReviewStatus()).isEqualTo(winner.reviewStatus());
        assertThat(reviewCount(before.getId())).isEqualTo(1);
        assertThat(auditCount(before.getId())).isEqualTo(1);
        assertThat(probe.deliveredFor(before.getId())).singleElement()
                .satisfies(event -> assertThat(event.reviewId()).isEqualTo(winner.reviewId()));
    }

    private CaseReviewDecisionResponse approve(String email, CaseRecord before) {
        return reviewService.approve(email, before.getId(), new ApproveCaseRequest(before.getVersion(), null));
    }

    private CaseReviewDecisionResponse reject(String email, CaseRecord before) {
        return reviewService.reject(email, before.getId(), new RejectCaseRequest(before.getVersion(), "Not enough"));
    }

    /** Holds the case row lock in its own transaction so both decisions must queue behind it. */
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

    /** Both decision transactions are parked on a row lock: the overlap is forced, not left to scheduling. */
    private void awaitBothBlockedOnTheCaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (lockWaiters() < 2) {
            assertThat(System.nanoTime()).as("both decisions waiting on the case lock").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private int lockWaiters() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class);
    }

    private static <T> T asModerator(String email, java.util.concurrent.Callable<T> call) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null, "MODERATOR"));
        try {
            return call.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
