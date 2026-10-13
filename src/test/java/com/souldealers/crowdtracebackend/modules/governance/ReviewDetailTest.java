package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.DuplicateMatchResponse;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseFile;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.governance.internal.repository.CaseReviewRepository;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(authorities = "MODERATOR")
class ReviewDetailTest extends ReviewDecisionTestSupport {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String moderatorEmail;
    private long moderator;

    @BeforeEach
    void prepareModerator() {
        moderatorEmail = email("detail-moderator");
        moderator = actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @AfterEach
    void clearHook() {
        HistoryReadHook.BEFORE_HISTORY.set(null);
    }

    @Test
    void detailShowsTheCurrentVersionMatchesAndHistoryOldestFirst() {
        CaseRecord c = pendingCaseWithReport();
        long strong = cases.saveAndFlush(aCase(reporter).build()).getId();
        long weak = cases.saveAndFlush(aCase(reporter).build()).getId();
        match(c.getId(), weak, 70, "NAME_FUZZY");
        match(c.getId(), strong, 95, "NAME_EXACT,LAST_SEEN_EXACT");
        long later = review(c.getId(), "REJECTED", "Second look", LocalDateTime.of(2026, 10, 2, 9, 0));
        long earlier = review(c.getId(), "APPROVED", null, LocalDateTime.of(2026, 10, 1, 9, 0));

        ReviewCaseDetailResponse detail = reviewService.getCaseDetail(c.getId());

        assertThat(detail.caseDetails().id()).isEqualTo(c.getId());
        assertThat(detail.caseDetails().version()).isEqualTo(cases.findById(c.getId()).orElseThrow().getVersion());
        assertThat(detail.duplicateMatches()).extracting(DuplicateMatchResponse::matchedCaseId)
                .containsExactly(strong, weak);
        DuplicateMatchResponse top = detail.duplicateMatches().getFirst();
        assertThat(top.confidence()).isEqualTo(95);
        assertThat(top.nameSimilarity()).isEqualTo(950);
        assertThat(top.dayDifference()).isEqualTo(1);
        assertThat(top.reasons()).containsExactly("NAME_EXACT", "LAST_SEEN_EXACT");
        assertThat(top.algorithmVersion()).isEqualTo("v1");
        assertThat(top.detectedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 0));
        assertThat(detail.reviewHistory()).extracting(CaseReviewHistoryResponse::reviewId)
                .containsExactly(earlier, later);
        assertThat(detail.reviewHistory().getLast()).isEqualTo(new CaseReviewHistoryResponse(later,
                ReviewStatus.REJECTED, moderator, "HUMAN", "Second look", LocalDateTime.of(2026, 10, 2, 9, 0)));
    }

    @Test
    void evidenceIsMetadataOnlyWithNoAccessYet() {
        CaseRecord c = pendingCaseWithReport();
        CaseFile report = files.findAll().stream().filter(f -> c.getId().equals(f.getCaseId())).findFirst().orElseThrow();

        ReviewCaseDetailResponse detail = reviewService.getCaseDetail(c.getId());

        assertThat(detail.evidence()).hasSize(1);
        assertThat(detail.evidence().getFirst().accessAvailable()).isFalse();
        assertThat(detail.evidence().getFirst().file().id()).isEqualTo(report.getId());
        String raw = mapper.writeValueAsString(detail);
        assertThat(raw).doesNotContain(report.getStorageKey(), report.getChecksumSha256(),
                "storageKey", "checksum", "url", "Url");
        JsonNode json = mapper.readTree(raw);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "caseDetails", "evidence", "duplicateMatches", "reviewHistory");
        assertThat(json.get("caseDetails").has("files")).as("files appear once, as evidence").isFalse();
        assertThat(json.get("caseDetails").get("version").asLong()).isEqualTo(detail.caseDetails().version());
        JsonNode evidence = json.get("evidence").get(0);
        assertThat(evidence.propertyNames()).containsExactlyInAnyOrder("file", "accessAvailable");
        assertThat(evidence.get("accessAvailable").asBoolean()).isFalse();
        assertThat(evidence.get("file").propertyNames()).containsExactlyInAnyOrder(
                "id", "purpose", "visibility", "contentType", "sizeBytes", "uploadedAt");
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThatThrownBy(() -> reviewService.getCaseDetail(999_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void detailIsDeniedToRegisteredUsers() {
        assertThatThrownBy(() -> reviewService.getCaseDetail(1L)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "SUPER_ADMIN")
    void detailIsAvailableToSuperAdmins() {
        CaseRecord c = pendingCaseWithReport();
        assertThat(reviewService.getCaseDetail(c.getId()).caseDetails().id()).isEqualTo(c.getId());
    }

    /**
     * The case is read first, then an approval commits in its own transaction, then history is read. One
     * REPEATABLE READ snapshot means the detail still shows the pending case with no history, never the pending
     * case next to the approval.
     */
    @Test
    void anApprovalCommittedBetweenTheDetailQueriesDoesNotProduceAMixedView() {
        CaseRecord before = pendingCaseWithReport();
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        HistoryReadHook.BEFORE_HISTORY.set(() -> separate.executeWithoutResult(status -> reviewService.approve(
                moderatorEmail, before.getId(), new ApproveCaseRequest(before.getVersion(), "Looks complete"))));

        ReviewCaseDetailResponse detail = reviewService.getCaseDetail(before.getId());

        assertThat(reviewCount(before.getId())).as("the approval committed mid-read").isEqualTo(1);
        assertThat(detail.caseDetails().reviewStatus()).isEqualTo(ReviewStatus.SUBMITTED);
        assertThat(detail.caseDetails().version()).isEqualTo(before.getVersion());
        assertThat(detail.reviewHistory()).isEmpty();

        ReviewCaseDetailResponse after = reviewService.getCaseDetail(before.getId());
        assertThat(after.caseDetails().reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(after.caseDetails().version()).isEqualTo(cases.findById(before.getId()).orElseThrow().getVersion());
        assertThat(after.reviewHistory()).singleElement()
                .satisfies(review -> assertThat(review.decision()).isEqualTo(ReviewStatus.APPROVED));
    }

    private void match(long caseId, long matchedCaseId, int confidence, String reasons) {
        jdbc.update("""
                INSERT INTO case_duplicate_matches (case_id, matched_case_id, confidence, name_similarity,
                    day_difference, reasons, algorithm_version, detected_at)
                VALUES (?, ?, ?, ?, 1, ?, 'v1', TIMESTAMP '2026-10-01 08:00')
                """, caseId, matchedCaseId, confidence, confidence * 10, reasons);
    }

    private long review(long caseId, String decision, String notes, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source, review_notes, created_at)
                VALUES (?, ?, ?, 'HUMAN', ?, ?) RETURNING id
                """, Long.class, caseId, moderator, decision, notes, createdAt);
    }

    /** Runs a one-shot action just before governance reads review history, inside the detail transaction. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HistoryReadHook {
        static final AtomicReference<Runnable> BEFORE_HISTORY = new AtomicReference<>();

        @Bean
        static BeanPostProcessor historyReadHook() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof CaseReviewRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                            ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                                if (method.getName().equals("findByCaseIdOrderByCreatedAtAscIdAsc")) {
                                    Runnable hook = BEFORE_HISTORY.getAndSet(null);
                                    if (hook != null) hook.run();
                                }
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getTargetException();
                                }
                            });
                }
            };
        }
    }
}
