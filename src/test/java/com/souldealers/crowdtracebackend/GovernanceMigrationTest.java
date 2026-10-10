package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises governance schema constraints and indexes on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class GovernanceMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsTheThreeGovernanceTables() {
        assertThat(List.of("case_reviews", "audit_events", "content_reports")).allMatch(this::tableExists);
    }

    @Test
    void approvedReviewWithoutNotesPersists() {
        long caseId = caseFor(user());
        long reviewerId = user();
        jdbc.update("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source)
                VALUES (?, ?, 'APPROVED', 'HUMAN')
                """, caseId, reviewerId);
        assertThat(jdbc.queryForMap("""
                SELECT decision, review_source, review_notes
                FROM case_reviews
                WHERE case_id = ?
                """, caseId))
                .containsEntry("decision", "APPROVED").containsEntry("review_source", "HUMAN").containsEntry("review_notes", null);
    }

    @Test
    void rejectionRequiresNonBlankNotes() {
        long caseId = caseFor(user());
        long reviewer = user();
        for (String notes : new String[]{null, "", "   "}) {
            assertViolation("case_reviews_rejection_notes_check", () -> jdbc.update(
                    """
                    INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source, review_notes)
                    VALUES (?, ?, 'REJECTED', 'HUMAN', ?)
                    """,
                    caseId, reviewer, notes));
        }
        jdbc.update("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source, review_notes)
                VALUES (?, ?, 'REJECTED', 'HUMAN', 'Insufficient information')
                """, caseId, reviewer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_reviews WHERE case_id = ? AND decision = 'REJECTED'", Integer.class, caseId))
                .isEqualTo(1);
    }

    @Test
    void decisionVocabularyIsPinned() {
        long caseId = caseFor(user());
        long reviewer = user();
        for (String decision : List.of("TAKEN_DOWN", "REQUEST_CHANGES")) {
            assertViolation("case_reviews_decision_check", () -> jdbc.update("""
                    INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source)
                    VALUES (?, ?, ?, 'HUMAN')
                    """, caseId, reviewer, decision));
        }
    }

    @Test
    void onlyHumanReviewSourceIsAccepted() {
        long caseId = caseFor(user());
        long reviewer = user();
        assertViolation("case_reviews_source_check", () -> jdbc.update("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source)
                VALUES (?, ?, 'APPROVED', 'AI')
                """, caseId, reviewer));
    }

    @Test
    void reviewSourceDefaultsToHuman() {
        long reviewer = user();
        long caseId = caseFor(user());
        jdbc.update("INSERT INTO case_reviews (case_id, reviewer_id, decision) VALUES (?, ?, 'APPROVED')", caseId, reviewer);
        assertThat(jdbc.queryForObject("SELECT review_source FROM case_reviews WHERE case_id = ?", String.class, caseId))
                .isEqualTo("HUMAN");
    }

    @Test
    void reviewRequiresExistingCaseAndReviewer() {
        long reviewer = user();
        long caseId = caseFor(user());
        assertViolation("fk_case_reviews_case", () -> insertReview(999_999L, reviewer));
        assertViolation("fk_case_reviews_reviewer", () -> insertReview(caseId, 999_999L));
    }

    @Test
    void aCaseMayHaveSeveralReviews() {
        long caseId = caseFor(user());
        long reviewer = user();
        insertReview(caseId, reviewer);
        insertReview(caseId, reviewer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_reviews WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(2);
    }

    @Test
    void userActorEventPersists() {
        long actor = user();
        jdbc.update("""
                INSERT INTO audit_events (actor_id, actor_role, action, target_type, target_id)
                VALUES (?, 'REGISTERED_USER', 'CASE.SUBMITTED', 'CASE', 42)
                """, actor);
        assertThat(jdbc.queryForMap("""
                SELECT actor_id, actor_role, metadata::text AS metadata
                FROM audit_events
                WHERE actor_id = ?
                """, actor))
                .containsEntry("actor_id", actor).containsEntry("actor_role", "REGISTERED_USER").containsEntry("metadata", "{}");
    }

    @Test
    void systemEventHasNoActor() {
        jdbc.update("""
                INSERT INTO audit_events (actor_role, action, target_type, target_id)
                VALUES ('SYSTEM', 'CASE.EXPIRED', 'CASE', 7)
                """);
        assertThat(jdbc.queryForMap("""
                SELECT actor_id, actor_role
                FROM audit_events
                WHERE action = 'CASE.EXPIRED'
                """))
                .containsEntry("actor_id", null).containsEntry("actor_role", "SYSTEM");
    }

    @Test
    void actorAndSystemRoleMustAgree() {
        long actor = user();
        assertViolation("audit_events_system_actor_check", () -> insertAudit(null, "MODERATOR", "CASE.REVIEWED", "CASE", 1));
        assertViolation("audit_events_system_actor_check", () -> insertAudit(actor, "SYSTEM", "CASE.REVIEWED", "CASE", 1));
    }

    @Test
    void actionAndTargetTypeMustBeUpperDottedIdentifiers() {
        for (String action : List.of("case approved", "Case.Approved", "")) {
            assertViolation("audit_events_action_format_check", () -> insertAudit(null, "SYSTEM", action, "CASE", 1));
        }
        for (String target : List.of("case approved", "Case.Approved", "")) {
            assertViolation("audit_events_target_type_format_check", () -> insertAudit(null, "SYSTEM", "CASE.APPROVED", target, 1));
        }
        insertAudit(null, "SYSTEM", "CASE.REVIEW.APPROVED", "CASE.REVIEW", 1);
    }

    @Test
    void metadataMustBeAJsonObject() {
        for (String value : List.of("[]", "\"x\"", "null")) {
            assertViolation("audit_events_metadata_object_check", () -> jdbc.update("""
                    INSERT INTO audit_events (actor_role, action, target_type, target_id, metadata)
                    VALUES ('SYSTEM', 'CASE.X', 'CASE', 1, ?::jsonb)
                    """, value));
        }
        insertAudit(null, "SYSTEM", "CASE.DEFAULT", "CASE", 1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit_events WHERE action = 'CASE.DEFAULT'", String.class))
                .isEqualTo("{}");
    }

    @Test
    void metadataHasAn8KbDatabaseBackstop() {
        String asciiAtLimit = "{\"k\":\"" + "x".repeat(8183) + "\"}";
        String asciiOverLimit = "{\"k\":\"" + "x".repeat(8184) + "\"}";
        String multibyteAtLimit = "{\"k\":\"" + "é".repeat(4087) + "x".repeat(9) + "\"}";
        String multibyteOverLimit = "{\"k\":\"" + "é".repeat(4087) + "x".repeat(10) + "\"}";

        assertThat(normalizedMetadataBytes(asciiAtLimit)).isEqualTo(8192);
        assertThat(normalizedMetadataBytes(asciiOverLimit)).isEqualTo(8193);
        assertThat(normalizedMetadataBytes(multibyteAtLimit)).isEqualTo(8192);
        assertThat(normalizedMetadataBytes(multibyteOverLimit)).isEqualTo(8193);

        insertAuditWithMetadata("CASE.ASCII_LIMIT", asciiAtLimit);
        insertAuditWithMetadata("CASE.MULTIBYTE_LIMIT", multibyteAtLimit);
        assertThat(jdbc.queryForList("""
                SELECT octet_length(metadata::text)
                FROM audit_events
                WHERE action IN ('CASE.ASCII_LIMIT', 'CASE.MULTIBYTE_LIMIT')
                """, Integer.class)).containsOnly(8192);
        assertViolation("audit_events_metadata_size_check", () -> insertAuditWithMetadata("CASE.ASCII_OVER_LIMIT", asciiOverLimit));
        assertViolation("audit_events_metadata_size_check", () -> insertAuditWithMetadata("CASE.MULTIBYTE_OVER_LIMIT", multibyteOverLimit));
    }

    @Test
    void compactMetadataAtTheApplicationLimitIsAccepted() {
        // Many small entries: Postgres adds a space after every ':' and ',' when normalizing.
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; ; i++) {
            String entry = (i == 0 ? "" : ",") + "\"" + Integer.toString(i, 36) + "\":[0,0]";
            if (json.length() + entry.length() + 1 > 4096) {
                break;
            }
            json.append(entry);
        }
        String compact = json.append("}").toString();
        assertThat(compact.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(normalizedMetadataBytes(compact)).isGreaterThan(5000);

        insertAuditWithMetadata("CASE.COMPACT_LIMIT", compact);
    }

    @Test
    void correlationIdIsBounded() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_events (actor_role, action, target_type, target_id, correlation_id)
                VALUES ('SYSTEM', 'CASE.X', 'CASE', 1, ?)
                """, "c".repeat(65)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openReportPersists() {
        long reporter = user();
        jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (9001, ?, 'SPAM')
                """, reporter);
        assertThat(jdbc.queryForMap("""
                SELECT status, resolution, resolved_at
                FROM content_reports
                WHERE comment_id = 9001 AND reporter_id = ?
                """, reporter))
                .containsEntry("status", "OPEN").containsEntry("resolution", null).containsEntry("resolved_at", null);
    }

    @Test
    void otherReasonRequiresDetails() {
        long reporter = user();
        for (String details : new String[]{null, "", "   "}) {
            assertViolation("content_reports_other_details_check", () -> jdbc.update("""
                    INSERT INTO content_reports (comment_id, reporter_id, reason, details)
                    VALUES (9002, ?, 'OTHER', ?)
                    """, reporter, details));
        }
        jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, details)
                VALUES (9002, ?, 'OTHER', 'Other concern')
                """, reporter);
    }

    @Test
    void reasonVocabularyIsPinned() {
        assertViolation("content_reports_reason_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (9003, ?, 'OFF_TOPIC')
                """, user()));
    }

    @Test
    void oneOpenReportPerReporterAndComment() {
        long reporter = user();
        jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (9004, ?, 'SPAM')
                """, reporter);
        assertViolation("uq_content_reports_open_reporter", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (9004, ?, 'HARASSMENT')
                """, reporter));
        jdbc.update("""
                UPDATE content_reports
                SET status = 'RESOLVED', resolution = 'DISMISSED', moderator_id = ?, resolved_at = CURRENT_TIMESTAMP
                WHERE comment_id = 9004
                """, user());
        jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason)
                VALUES (9004, ?, 'SPAM')
                """, reporter);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM content_reports WHERE comment_id = 9004 AND reporter_id = ?
                """, Integer.class, reporter)).isEqualTo(2);
    }

    @Test
    void resolvedReportMustBeAttributed() {
        long reporter = user();
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution, moderator_id, resolved_at)
                VALUES (9005, ?, 'SPAM', 'RESOLVED', 'REMOVED', ?, CURRENT_TIMESTAMP)
                """, reporter, null));
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution, moderator_id, resolved_at)
                VALUES (9006, ?, 'SPAM', 'RESOLVED', NULL, ?, CURRENT_TIMESTAMP)
                """, reporter, user()));
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution, moderator_id)
                VALUES (9007, ?, 'SPAM', 'RESOLVED', 'REMOVED', ?)
                """, reporter, user()));
    }

    @Test
    void openReportCarriesNoResolution() {
        long reporter = user();
        long moderator = user();
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, moderator_id)
                VALUES (9008, ?, 'SPAM', ?)
                """, reporter, moderator));
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, resolution)
                VALUES (9009, ?, 'SPAM', 'DISMISSED')
                """, reporter));
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, resolution_notes)
                VALUES (9010, ?, 'SPAM', 'No action yet')
                """, reporter));
        assertViolation("content_reports_attribution_check", () -> jdbc.update("""
                INSERT INTO content_reports (comment_id, reporter_id, reason, resolved_at)
                VALUES (9011, ?, 'SPAM', CURRENT_TIMESTAMP)
                """, reporter));
    }

    @Test
    void commentIdHasNoForeignKeyYet() {
        jdbc.update("INSERT INTO content_reports (comment_id, reporter_id, reason) VALUES (987654321, ?, 'SPAM')", user());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE comment_id = 987654321", Integer.class)).isEqualTo(1);
    }

    @Test
    void governanceIndexesExist() {
        assertThat(indexesOn("case_reviews")).contains("idx_case_reviews_case", "idx_case_reviews_reviewer");
        assertThat(indexesOn("audit_events")).contains("idx_audit_events_target", "idx_audit_events_actor")
                .doesNotContain("idx_audit_events_action", "idx_audit_events_occurred");
        assertThat(indexesOn("content_reports")).contains("uq_content_reports_open_reporter", "idx_content_reports_open_queue", "idx_content_reports_reporter")
                .doesNotContain("idx_content_reports_open_comment");
    }

    private long user() {
        String email = "governance-" + UUID.randomUUID() + "@example.test";
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE')
                RETURNING id
                """, Long.class, email);
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

    private void insertReview(long caseId, long reviewerId) {
        jdbc.update("""
                INSERT INTO case_reviews (case_id, reviewer_id, decision, review_source)
                VALUES (?, ?, 'APPROVED', 'HUMAN')
                """, caseId, reviewerId);
    }

    private void insertAudit(Long actor, String role, String action, String targetType, long targetId) {
        jdbc.update("""
                INSERT INTO audit_events (actor_id, actor_role, action, target_type, target_id)
                VALUES (?, ?, ?, ?, ?)
                """, actor, role, action, targetType, targetId);
    }

    private void insertAuditWithMetadata(String action, String metadata) {
        jdbc.update("""
                INSERT INTO audit_events (actor_role, action, target_type, target_id, metadata)
                VALUES ('SYSTEM', ?, 'CASE', 1, ?::jsonb)
                """, action, metadata);
    }

    private int normalizedMetadataBytes(String metadata) {
        return jdbc.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, metadata);
    }

    private void assertViolation(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(failure.getCause().getMessage()).contains(constraint));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = ?)", Boolean.class, table));
    }

    private List<String> indexesOn(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }
}
