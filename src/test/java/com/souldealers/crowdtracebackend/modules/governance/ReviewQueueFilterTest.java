package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.AdminCaseSummaryResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.PagedResponse;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each test rolls back, so the queue holds only the cases it creates. */
@WithMockUser(authorities = "MODERATOR")
class ReviewQueueFilterTest extends CasePostgresTestSupport {
    private static final ReviewQueueFilter NONE = new ReviewQueueFilter(null, null, null, null, null);

    @Autowired private ReviewService reviewService;
    @Autowired private CaseRecordRepository cases;

    private long reporter;

    @BeforeEach
    void prepareReporter() {
        reporter = user("queue-filter-" + System.nanoTime() + "@example.com");
    }

    private CaseRecord save(CaseRecord.CaseRecordBuilder builder) {
        return cases.saveAndFlush(builder.build());
    }

    private List<Long> ids(ReviewQueueFilter filter) {
        return ids(reviewService.listQueue(filter, PageRequest.of(0, 50)));
    }

    private static List<Long> ids(PagedResponse<AdminCaseSummaryResponse> page) {
        return page.content().stream().map(AdminCaseSummaryResponse::id).toList();
    }

    @Test
    void defaultsToBothPendingStatesInTheFixedOrder() {
        CaseRecord adult = save(aCase(reporter).submittedAt(at(8)));
        CaseRecord laterMinor = save(aCase(reporter).submittedAt(at(10)).priorityMinor(true));
        CaseRecord earlyMinor = save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW)
                .submittedAt(at(9)).priorityMinor(true));
        save(aCase(reporter).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).priorityMinor(true));
        save(aCase(reporter).reviewStatus(ReviewStatus.REJECTED).priorityMinor(true));
        save(aCase(reporter).reviewStatus(ReviewStatus.TAKEN_DOWN).caseStatus(CaseStatus.MISSING)
                .takedownReason("Verified hoax report").takenDownBy(reporter).takenDownAt(at(9)).priorityMinor(true));

        assertThat(ids(NONE)).containsExactly(earlyMinor.getId(), laterMinor.getId(), adult.getId());
        assertThat(ids((ReviewQueueFilter) null)).containsExactly(earlyMinor.getId(), laterMinor.getId(), adult.getId());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"SUBMITTED", "UNDER_REVIEW"})
    void filtersByOnePendingStatus(ReviewStatus status) {
        CaseRecord submitted = save(aCase(reporter).reviewStatus(ReviewStatus.SUBMITTED));
        CaseRecord underReview = save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW));

        assertThat(ids(new ReviewQueueFilter(status, null, null, null, null))).containsExactly(
                status == ReviewStatus.SUBMITTED ? submitted.getId() : underReview.getId());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"APPROVED", "REJECTED", "TAKEN_DOWN"})
    void decidedStatusesAreNotQueueFilters(ReviewStatus status) {
        assertThatThrownBy(() -> reviewService.listQueue(new ReviewQueueFilter(status, null, null, null, null),
                PageRequest.of(0, 20))).isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void filtersByPriorityMinor(boolean minor) {
        CaseRecord child = save(aCase(reporter).priorityMinor(true));
        CaseRecord adult = save(aCase(reporter).priorityMinor(false));

        assertThat(ids(new ReviewQueueFilter(null, minor, null, null, null)))
                .containsExactly(minor ? child.getId() : adult.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void filtersByDuplicateFlag(boolean flagged) {
        CaseRecord duplicate = save(aCase(reporter).duplicateFlag(true));
        CaseRecord unique = save(aCase(reporter).duplicateFlag(false));

        assertThat(ids(new ReviewQueueFilter(null, null, flagged, null, null)))
                .containsExactly(flagged ? duplicate.getId() : unique.getId());
    }

    @Test
    void submittedWindowIncludesFromAndExcludesTo() {
        save(aCase(reporter).submittedAt(at(8)));
        CaseRecord atFrom = save(aCase(reporter).submittedAt(at(9)));
        CaseRecord inside = save(aCase(reporter).submittedAt(at(9).plusMinutes(59)));
        save(aCase(reporter).submittedAt(at(10)));

        assertThat(ids(new ReviewQueueFilter(null, null, null, at(9), at(10))))
                .containsExactly(atFrom.getId(), inside.getId());
        assertThat(ids(new ReviewQueueFilter(null, null, null, at(10), null))).hasSize(1);
        assertThat(ids(new ReviewQueueFilter(null, null, null, null, at(9)))).hasSize(1);
    }

    @Test
    void anEmptyOrInvertedWindowIsInvalid() {
        for (var filter : List.of(new ReviewQueueFilter(null, null, null, at(9), at(9)),
                new ReviewQueueFilter(null, null, null, at(10), at(9)))) {
            assertThatThrownBy(() -> reviewService.listQueue(filter, PageRequest.of(0, 20)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void filtersCombine() {
        CaseRecord match = save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW).priorityMinor(true)
                .duplicateFlag(true).submittedAt(at(9)));
        save(aCase(reporter).reviewStatus(ReviewStatus.SUBMITTED).priorityMinor(true).duplicateFlag(true).submittedAt(at(9)));
        save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW).priorityMinor(false).duplicateFlag(true).submittedAt(at(9)));
        save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW).priorityMinor(true).duplicateFlag(false).submittedAt(at(9)));
        save(aCase(reporter).reviewStatus(ReviewStatus.UNDER_REVIEW).priorityMinor(true).duplicateFlag(true).submittedAt(at(11)));

        assertThat(ids(new ReviewQueueFilter(ReviewStatus.UNDER_REVIEW, true, true, at(9), at(10))))
                .containsExactly(match.getId());
    }

    @Test
    void tiesBreakOnIdSoPagesNeitherRepeatNorSkip() {
        List<Long> created = IntStream.range(0, 5).mapToObj(i ->
                save(aCase(reporter).submittedAt(at(9)).priorityMinor(true)).getId()).toList();

        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(ids(reviewService.listQueue(NONE, PageRequest.of(page, 2))));
        }
        assertThat(seen).containsExactlyElementsOf(created);
    }

    @Test
    void clientSortIsIgnoredAndPageSizeIsCappedAtFifty() {
        CaseRecord adult = save(aCase(reporter).submittedAt(at(8)));
        CaseRecord minor = save(aCase(reporter).submittedAt(at(10)).priorityMinor(true));

        var page = reviewService.listQueue(NONE, PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(ids(page)).containsExactly(minor.getId(), adult.getId());
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void anUnpagedRequestGetsTheDefaultSizeOfTwenty() {
        IntStream.range(0, 21).forEach(i -> save(aCase(reporter)));

        var page = reviewService.listQueue(NONE, Pageable.unpaged());

        assertThat(page.size()).isEqualTo(20);
        assertThat(page.content()).hasSize(20);
        assertThat(page.totalElements()).isEqualTo(21);
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void theQueueIsDeniedToRegisteredUsers() {
        assertThatThrownBy(() -> reviewService.listQueue(NONE, PageRequest.of(0, 20)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithAnonymousUser
    void theQueueIsDeniedToAnonymousUsers() {
        assertThatThrownBy(() -> reviewService.listQueue(NONE, PageRequest.of(0, 20)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "SUPER_ADMIN")
    void theQueueIsAvailableToSuperAdmins() {
        CaseRecord c = save(aCase(reporter));
        assertThat(ids(NONE)).containsExactly(c.getId());
    }

    /**
     * Mirrors the SQL each filter produces (the specification adds one predicate per set filter). With sequential
     * scans disabled, every shape must use an index keyed on review_status, which every queue query constrains.
     * On tiny fixtures PostgreSQL may choose either review-queue or public-age because both lead with that column.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "AND c.priority_minor = true",
            "AND c.duplicate_flag = true",
            "AND c.submitted_at >= TIMESTAMP '2026-10-01 09:00' AND c.submitted_at < TIMESTAMP '2026-10-02 00:00'",
            "AND c.priority_minor = true AND c.duplicate_flag = false"
                    + " AND c.submitted_at >= TIMESTAMP '2026-10-01 09:00' AND c.submitted_at < TIMESTAMP '2026-10-02 00:00'"})
    void everyFilterShapeUsesTheReviewQueueIndex(String filters) {
        IntStream.range(0, 30).forEach(i -> save(aCase(reporter).priorityMinor(i % 2 == 0).submittedAt(at(i % 12))));
        cases.saveAndFlush(aCase(reporter).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING).build());
        jdbc.execute("ANALYZE cases");
        jdbc.execute("SET LOCAL enable_seqscan = off");

        for (String statuses : List.of("('SUBMITTED', 'UNDER_REVIEW')", "('UNDER_REVIEW')")) {
            String where = "WHERE c.review_status IN " + statuses + " " + filters;
            String page = String.join("\n", jdbc.queryForList("EXPLAIN SELECT c.* FROM cases c " + where
                    + " ORDER BY c.priority_minor DESC, c.submitted_at ASC, c.id ASC LIMIT 20 OFFSET 0", String.class));
            String count = String.join("\n", jdbc.queryForList("EXPLAIN SELECT count(c.id) FROM cases c " + where,
                    String.class));

            for (String plan : List.of(page, count)) {
                assertThat(plan).as(plan).doesNotContain("Seq Scan")
                        .containsAnyOf("idx_cases_review_queue", "idx_cases_public_age")
                        .containsPattern("Index Cond: \\(+\\(?review_status\\)?");
            }
        }
    }
}
