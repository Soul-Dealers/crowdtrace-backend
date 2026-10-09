package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Pins reusable governance SQL contracts on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
@Transactional
class GovernanceQueryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""", Long.class, email);
    }

    private long caseFor(long reporterId) {
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances,
                    public_contact_number, review_status, submitted_at, created_at)
                VALUES (?, 'Ama Owusu', 30, 'FEMALE', DATE '2026-09-30', 'ASHANTI', 'Kejetia',
                    'Short', 'Red dress', 'Left home', '+233200000001', 'SUBMITTED', ?, ?)
                RETURNING id""", Long.class, reporterId, at(9), at(9));
    }

    private long review(long caseId, long reviewerId, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source, created_at)
                VALUES (?, ?, 'APPROVED', 'HUMAN', ?) RETURNING id""", Long.class,
                caseId, reviewerId, createdAt);
    }

    private long report(long commentId, long reporterId, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, created_at)
                VALUES (?, ?, 'SPAM', ?) RETURNING id""", Long.class, commentId, reporterId, createdAt);
    }

    private long resolvedReport(long commentId, long reporterId, long moderatorId,
                                LocalDateTime createdAt, LocalDateTime resolvedAt) {
        return jdbc.queryForObject("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution,
                    moderator_id, created_at, resolved_at)
                VALUES (?, ?, 'SPAM', 'RESOLVED', 'DISMISSED', ?, ?, ?) RETURNING id""", Long.class,
                commentId, reporterId, moderatorId, createdAt, resolvedAt);
    }

    private long audit(Long actorId, String actorRole, String targetType, long targetId,
                       LocalDateTime occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO audit_events (actor_id, actor_role, action, target_type, target_id, occurred_at)
                VALUES (?, ?, 'CASE.REVIEWED', ?, ?, ?) RETURNING id""", Long.class,
                actorId, actorRole, targetType, targetId, occurredAt);
    }

    private static LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 10, 1, hour, 0);
    }

    private static LocalDateTime localDateTime(Object value) {
        return value == null ? null : ((Timestamp) value).toLocalDateTime();
    }

    @Test
    void reviewHistoryForCaseIsChronologicalWithIdTieBreak() {
        long reviewer = user("review-history@x.com");
        long caseId = caseFor(reviewer);
        long otherCase = caseFor(reviewer);
        long late = review(caseId, reviewer, at(11));
        long tieFirst = review(caseId, reviewer, at(10));
        long tieSecond = review(caseId, reviewer, at(10));
        review(otherCase, reviewer, at(9));

        assertThat(jdbc.queryForList("""
                SELECT id FROM case_reviews WHERE case_id = ? ORDER BY created_at, id""", Long.class, caseId))
                .containsExactly(tieFirst, tieSecond, late);
    }

    @Test
    void openReportQueueIsOldestFirstAndExcludesResolved() {
        long reporter = user("queue-reports@x.com");
        long resolvedReporter = user("resolved-queue@x.com");
        long newest = report(101, reporter, at(12));
        long tieFirst = report(102, reporter, at(10));
        long tieSecond = report(103, reporter, at(10));
        long middle = report(104, reporter, at(11));
        resolvedReport(105, resolvedReporter, reporter, at(9), at(10));
        String sql = "SELECT id FROM content_reports WHERE status = 'OPEN' "
                + "ORDER BY created_at, id LIMIT ? OFFSET ?";

        assertThat(jdbc.queryForList(sql, Long.class, 2, 0)).containsExactly(tieFirst, tieSecond);
        assertThat(jdbc.queryForList(sql, Long.class, 2, 2)).containsExactly(middle, newest);
    }

    @Test
    void openReportsGroupByComment() {
        long firstReporter = user("group-first@x.com");
        long secondReporter = user("group-second@x.com");
        long thirdReporter = user("group-third@x.com");
        report(201, firstReporter, at(10));
        report(201, secondReporter, at(12));
        report(202, thirdReporter, at(10));
        report(203, firstReporter, at(11));
        resolvedReport(201, thirdReporter, firstReporter, at(9), at(12));

        assertThat(jdbc.queryForList("""
                SELECT comment_id, count(*) AS report_count, min(created_at) AS oldest
                FROM content_reports WHERE status = 'OPEN' GROUP BY comment_id
                ORDER BY min(created_at), comment_id"""))
                .extracting(row -> row.get("comment_id"), row -> row.get("report_count"),
                        row -> localDateTime(row.get("oldest")))
                .containsExactly(
                        tuple(201L, 2L, at(10)),
                        tuple(202L, 1L, at(10)),
                        tuple(203L, 1L, at(11)));
    }

    @Test
    void removingACommentResolvesAllItsOpenReports() {
        long reporterOne = user("remove-one@x.com");
        long reporterTwo = user("remove-two@x.com");
        long moderator = user("remove-moderator@x.com");
        long targetOne = report(301, reporterOne, at(9));
        long targetTwo = report(301, reporterTwo, at(10));
        long alreadyResolved = resolvedReport(301, user("remove-old@x.com"), moderator, at(8), at(11));
        long otherComment = report(302, user("remove-other@x.com"), at(10));
        LocalDateTime resolvedAt = at(12);
        String sql = "UPDATE content_reports SET status = 'RESOLVED', resolution = 'REMOVED', "
                + "moderator_id = ?, resolved_at = ? WHERE comment_id = ? AND status = 'OPEN'";

        assertThat(jdbc.update(sql, moderator, resolvedAt, 301L)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT id, status, resolution, moderator_id, resolved_at
                FROM content_reports WHERE id IN (?, ?, ?, ?) ORDER BY id""",
                targetOne, targetTwo, alreadyResolved, otherComment))
                .extracting(row -> row.get("id"), row -> row.get("status"), row -> row.get("resolution"),
                        row -> row.get("moderator_id"), row -> localDateTime(row.get("resolved_at")))
                .containsExactly(
                        tuple(targetOne, "RESOLVED", "REMOVED", moderator, resolvedAt),
                        tuple(targetTwo, "RESOLVED", "REMOVED", moderator, resolvedAt),
                        tuple(alreadyResolved, "RESOLVED", "DISMISSED", moderator, at(11)),
                        tuple(otherComment, "OPEN", null, null, null));
        assertThat(jdbc.update(sql, moderator, resolvedAt, 301L)).isZero();
    }

    @Test
    void existingOpenReportIsFoundForRepeatSubmission() {
        long reporter = user("repeat-reporter@x.com");
        long anotherReporter = user("repeat-other@x.com");
        long moderator = user("repeat-moderator@x.com");
        long open = report(401, reporter, at(12));
        resolvedReport(401, reporter, moderator, at(8), at(9));
        report(401, anotherReporter, at(10));
        report(402, reporter, at(11));

        assertThat(jdbc.queryForList("""
                SELECT id FROM content_reports
                WHERE comment_id = ? AND reporter_id = ? AND status = 'OPEN'""", Long.class, 401L, reporter))
                .containsExactly(open);
    }

    @Test
    void auditTrailForTargetIsChronological() {
        long actor = user("audit-target@x.com");
        long late = audit(actor, "REGISTERED_USER", "CASE", 501, at(11));
        long tieFirst = audit(actor, "REGISTERED_USER", "CASE", 501, at(10));
        long tieSecond = audit(actor, "REGISTERED_USER", "CASE", 501, at(10));
        audit(actor, "REGISTERED_USER", "COMMENT", 501, at(9));
        audit(actor, "REGISTERED_USER", "CASE", 502, at(8));

        assertThat(jdbc.queryForList("""
                SELECT id FROM audit_events WHERE target_type = ? AND target_id = ?
                ORDER BY occurred_at, id""", Long.class, "CASE", 501L))
                .containsExactly(tieFirst, tieSecond, late);
    }

    @Test
    void auditByActorExcludesSystemEvents() {
        long actor = user("audit-actor@x.com");
        long otherActor = user("audit-other-actor@x.com");
        long earlierTie = audit(actor, "REGISTERED_USER", "CASE", 601, at(10));
        long laterTie = audit(actor, "REGISTERED_USER", "CASE", 602, at(10));
        long newest = audit(actor, "REGISTERED_USER", "CASE", 603, at(11));
        audit(otherActor, "REGISTERED_USER", "CASE", 604, at(12));
        audit(null, "SYSTEM", "CASE", 605, at(13));

        assertThat(jdbc.queryForList("""
                SELECT id FROM audit_events WHERE actor_id = ? ORDER BY occurred_at DESC, id DESC""",
                Long.class, actor)).containsExactly(newest, laterTie, earlierTie);
    }

    @Test
    void queueQueriesUseTheirIndexes() {
        long reporter = user("explain-reporter@x.com");
        for (int i = 0; i < 40; i++) {
            report(700 + i, reporter, at(9 + (i % 4)));
            audit(reporter, "REGISTERED_USER", "CASE", 700 + i, at(9 + (i % 4)));
        }
        jdbc.execute("SET LOCAL enable_seqscan = off");

        List<String> queuePlan = jdbc.queryForList("""
                EXPLAIN SELECT id FROM content_reports WHERE status = 'OPEN'
                ORDER BY created_at, id LIMIT 10 OFFSET 0""", String.class);
        List<String> targetPlan = jdbc.queryForList("""
                EXPLAIN SELECT id FROM audit_events WHERE target_type = 'CASE' AND target_id = 700
                ORDER BY occurred_at, id""", String.class);

        assertThat(String.join("\n", queuePlan)).contains("idx_content_reports_open_queue");
        assertThat(String.join("\n", targetPlan)).contains("idx_audit_events_target");
    }
}
