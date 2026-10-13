package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

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
        CountDownLatch start = new CountDownLatch(1);

        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CaseReviewDecisionResponse> a = executor.submit(() -> asModerator(firstEmail, start,
                    () -> first.apply(firstEmail, before)));
            Future<CaseReviewDecisionResponse> b = executor.submit(() -> asModerator(secondEmail, start,
                    () -> second.apply(secondEmail, before)));
            start.countDown();
            for (Future<CaseReviewDecisionResponse> future : List.of(a, b)) {
                try {
                    outcomes.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    outcomes.add(failure.getCause());
                }
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

    private static <T> T asModerator(String email, CountDownLatch start, java.util.concurrent.Callable<T> call)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(email, null, "MODERATOR"));
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return call.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
