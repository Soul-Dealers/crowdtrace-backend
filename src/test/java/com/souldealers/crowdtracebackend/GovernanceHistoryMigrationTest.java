package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves review and report history cannot be rewritten (V17) and defaults are UTC (V18). */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class GovernanceHistoryMigrationTest {

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

    @Autowired private JdbcTemplate jdbc;

    @Test
    void caseReviewsRejectUpdatesDeletesAndTruncate() {
        long reviewId = review("REJECTED", "Insufficient detail");

        assertAppendOnly(() -> jdbc.update(
                "UPDATE case_reviews SET decision = 'APPROVED', review_notes = NULL WHERE id = ?", reviewId),
                "case_reviews is append-only");
        assertAppendOnly(() -> jdbc.update(
                "UPDATE case_reviews SET decision = decision WHERE id = ?", reviewId),
                "case_reviews is append-only");
        assertAppendOnly(() -> jdbc.update("DELETE FROM case_reviews WHERE id = ?", reviewId),
                "case_reviews is append-only");
        assertAppendOnly(() -> jdbc.execute("TRUNCATE TABLE case_reviews"),
                "case_reviews is append-only");

        assertThat(jdbc.queryForObject("SELECT decision FROM case_reviews WHERE id = ?", String.class, reviewId))
                .isEqualTo("REJECTED");
    }

    @Test
    void reviewedCaseAndReviewerCannotBeDeleted() {
        long reviewer = user();
        long caseId = caseFor(user());
        jdbc.update("INSERT INTO case_reviews (case_id, reviewer_id, decision) VALUES (?, ?, 'APPROVED')",
                caseId, reviewer);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM cases WHERE id = ?", caseId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", reviewer))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openReportCanBeResolvedOnce() {
        long reportId = openReport(9101);
        long moderator = user();

        jdbc.update("""
                UPDATE content_reports
                SET status = 'RESOLVED', resolution = 'REMOVED', moderator_id = ?,
                    resolution_notes = 'Abusive', resolved_at = created_at + INTERVAL '1 minute'
                WHERE id = ?""", moderator, reportId);

        assertAppendOnly(() -> jdbc.update("""
                UPDATE content_reports
                SET status = 'OPEN', resolution = NULL, moderator_id = NULL,
                    resolution_notes = NULL, resolved_at = NULL
                WHERE id = ?""", reportId), "content_reports only allows resolving an open report");
        assertAppendOnly(() -> jdbc.update(
                "UPDATE content_reports SET resolution = 'DISMISSED' WHERE id = ?", reportId),
                "content_reports only allows resolving an open report");

        assertThat(jdbc.queryForObject("SELECT resolution FROM content_reports WHERE id = ?", String.class, reportId))
                .isEqualTo("REMOVED");
    }

    @Test
    void openReportSubmissionCannotBeEdited() {
        long reportId = openReport(9102);

        assertAppendOnly(() -> jdbc.update(
                "UPDATE content_reports SET reason = 'OTHER', details = 'Changed' WHERE id = ?", reportId),
                "content_reports only allows resolving an open report");
        assertAppendOnly(() -> jdbc.update("""
                UPDATE content_reports
                SET status = 'RESOLVED', resolution = 'DISMISSED', moderator_id = ?,
                    resolved_at = created_at, comment_id = 1
                WHERE id = ?""", user(), reportId),
                "content_reports only allows resolving an open report");
    }

    @Test
    void reportsCannotBeDeletedOrTruncated() {
        long reportId = openReport(9103);

        assertAppendOnly(() -> jdbc.update("DELETE FROM content_reports WHERE id = ?", reportId),
                "content_reports only allows resolving an open report");
        assertAppendOnly(() -> jdbc.execute("TRUNCATE TABLE content_reports"),
                "content_reports only allows resolving an open report");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE id = ?", Integer.class, reportId))
                .isEqualTo(1);
    }

    @Test
    void reportCannotBeResolvedBeforeItWasCreated() {
        long reportId = openReport(9104);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE content_reports
                SET status = 'RESOLVED', resolution = 'DISMISSED', moderator_id = ?,
                    resolved_at = created_at - INTERVAL '1 second'
                WHERE id = ?""", user(), reportId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(failure.getCause().getMessage())
                        .contains("content_reports_resolved_after_created_check"));
    }

    @Test
    void defaultTimestampsAreUtcWhateverTheSessionTimeZone() {
        long reporter = user();
        Duration skew = jdbc.execute((ConnectionCallback<Duration>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'Pacific/Kiritimati'");
                try (ResultSet row = statement.executeQuery("""
                        WITH inserted AS (
                            INSERT INTO content_reports (comment_id, reporter_id, reason)
                            VALUES (9105, %d, 'SPAM') RETURNING created_at)
                        SELECT extract(epoch FROM (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - created_at)
                        FROM inserted""".formatted(reporter))) {
                    row.next();
                    return Duration.ofMillis(Math.round(row.getDouble(1) * 1000));
                }
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("RESET TIME ZONE");
                }
            }
        });

        assertThat(skew.abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND column_default ILIKE '%CURRENT_TIMESTAMP%'
                  AND column_default NOT ILIKE '%UTC%'""", String.class)).isEmpty();
    }

    private void assertAppendOnly(Runnable statement, String message) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(message);
    }

    private long review(String decision, String notes) {
        return jdbc.queryForObject("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_notes)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, caseFor(user()), user(), decision, notes);
    }

    private long openReport(long commentId) {
        return jdbc.queryForObject("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (?, ?, 'SPAM') RETURNING id""", Long.class, commentId, user());
    }

    private long user() {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""",
                Long.class, "history-" + UUID.randomUUID() + "@example.test");
    }

    private long caseFor(long reporterId) {
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances,
                    public_contact_number, review_status, submitted_at)
                VALUES (?, 'Kofi Mensah', 12, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA',
                    'Madina market', 'Slim, 1.4m', 'Blue school uniform', 'Did not return from school',
                    '+233200000000', 'SUBMITTED', CURRENT_TIMESTAMP) RETURNING id""", Long.class, reporterId);
    }
}
