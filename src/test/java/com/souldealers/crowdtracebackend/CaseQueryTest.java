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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the repository query contracts for CT-012 on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
@Transactional
class CaseQueryTest {

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

    private int insertFile(long uploader, Long caseId, String purpose, String visibility, String key) {
        return jdbc.update("""
                INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key,
                    content_type, size_bytes, checksum_sha256)
                VALUES (?, ?, ?, ?, ?, 'application/pdf', 1024, ?)""",
                caseId, uploader, purpose, visibility, key, "a".repeat(64));
    }

    private int insertConsent(long caseId, long userId, String version) {
        return jdbc.update("""
                INSERT INTO case_consents (case_id, user_id, consent_type, consent_version, source)
                VALUES (?, ?, 'SENSITIVE_DATA_COLLECTION', ?, 'WEB')""", caseId, userId, version);
    }

    private long caseOf(long reporterId, int age, boolean minor, String reviewStatus,
                        String caseStatus, LocalDateTime submittedAt) {
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances,
                    public_contact_number, review_status, case_status, priority_minor,
                    submitted_at, created_at)
                VALUES (?, 'Ama Owusu', ?, 'FEMALE', DATE '2026-09-30', 'ASHANTI', 'Kejetia',
                    'Short', 'Red dress', 'Left home', '+233200000001', ?, ?, ?, ?, ?)
                RETURNING id""", Long.class,
                reporterId, age, reviewStatus, caseStatus, minor, submittedAt, submittedAt);
    }

    private static LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 10, 1, hour, 0);
    }

    @Test
    void listsOnlyTheReportersOwnCasesNewestFirst() {
        long a = user("own-a@x.com"), b = user("own-b@x.com");
        long older = caseOf(a, 30, false, "SUBMITTED", null, at(9));
        long newer = caseOf(a, 30, false, "SUBMITTED", null, at(10));
        caseOf(b, 30, false, "SUBMITTED", null, at(11));

        assertThat(jdbc.queryForList(
                "SELECT id FROM cases WHERE reporter_id = ? ORDER BY created_at DESC", Long.class, a))
                .containsExactly(newer, older);
    }

    @Test
    void ownershipScopedLookupHidesOtherReportersCases() {
        long a = user("scope-a@x.com"), b = user("scope-b@x.com");
        long id = caseOf(a, 30, false, "SUBMITTED", null, at(9));
        String sql = "SELECT count(*) FROM cases WHERE id = ? AND reporter_id = ?";
        assertThat(jdbc.queryForObject(sql, Integer.class, id, b)).isZero();
        assertThat(jdbc.queryForObject(sql, Integer.class, id, a)).isOne();
    }

    @Test
    void reviewQueueShowsMinorsFirstThenOldest() {
        long r = user("queue@x.com");
        long adult = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        long minorOlder = caseOf(r, 12, true, "UNDER_REVIEW", null, at(10));
        long minorNewer = caseOf(r, 15, true, "SUBMITTED", null, at(11));
        caseOf(r, 8, true, "APPROVED", "MISSING", at(8));

        assertThat(jdbc.queryForList("""
                SELECT id FROM cases WHERE review_status IN ('SUBMITTED', 'UNDER_REVIEW')
                ORDER BY priority_minor DESC, submitted_at""", Long.class))
                .containsExactly(minorOlder, minorNewer, adult);
    }

    @Test
    void publicFilterReturnsOnlyApprovedCasesWithMatchingOutcome() {
        long r = user("public@x.com");
        long live = caseOf(r, 30, false, "APPROVED", "MISSING", at(9));
        caseOf(r, 30, false, "APPROVED", "FOUND_SAFE", at(10));
        caseOf(r, 30, false, "TAKEN_DOWN", "MISSING", at(11));
        caseOf(r, 30, false, "SUBMITTED", null, at(12));

        assertThat(jdbc.queryForList("""
                SELECT id FROM cases WHERE review_status = 'APPROVED' AND case_status IN ('MISSING')""",
                Long.class)).containsExactly(live);
    }

    @Test
    void associatesLiveFilesWithTheirCase() {
        long r = user("files@x.com");
        long c1 = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        long c2 = caseOf(r, 30, false, "SUBMITTED", null, at(10));
        insertAttachedFile(r, c1, "REPORT", "PRIVATE", "f1", false);
        insertAttachedFile(r, c1, "PHOTO", "PUBLIC", "f2", false);
        insertAttachedFile(r, c1, "PHOTO", "PUBLIC", "f3", true);   // soft-deleted
        insertAttachedFile(r, c2, "REPORT", "PRIVATE", "f4", false);

        assertThat(jdbc.queryForList(
                "SELECT storage_key FROM case_files WHERE case_id = ? AND deleted_at IS NULL", String.class, c1))
                .containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    void detectsWhetherAReportIsAttached() {
        long r = user("report@x.com");
        long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        String sql = """
                SELECT EXISTS (SELECT 1 FROM case_files
                               WHERE case_id = ? AND purpose = 'REPORT' AND deleted_at IS NULL)""";
        insertAttachedFile(r, c, "PHOTO", "PUBLIC", "p1", false);
        assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isFalse();
        insertAttachedFile(r, c, "REPORT", "PRIVATE", "r1", false);
        assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isTrue();
        jdbc.update("UPDATE case_files SET deleted_at = ? WHERE storage_key = 'r1'", at(12));
        assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isFalse();
    }

    @Test
    void findsUnattachedUploadsByUploader() {
        long a = user("up-a@x.com"), b = user("up-b@x.com");
        long c = caseOf(a, 30, false, "SUBMITTED", null, at(9));
        insertFile(a, null, "REPORT", "PRIVATE", "staged-a");
        insertAttachedFile(a, c, "REPORT", "PRIVATE", "attached-a", false);
        insertFile(b, null, "REPORT", "PRIVATE", "staged-b");

        assertThat(jdbc.queryForList(
                "SELECT storage_key FROM case_files WHERE uploaded_by = ? AND case_id IS NULL", String.class, a))
                .containsExactly("staged-a");
    }

    @Test
    void attachmentTimestampMovesWithTheCaseLink() {
        long r = user("attach@x.com");
        long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        insertFile(r, null, "REPORT", "PRIVATE", "half");
        // linking a case without stamping attached_at (or vice versa) is rejected
        assertThatThrownBy(() -> jdbc.update("UPDATE case_files SET case_id = ? WHERE storage_key = 'half'", c))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnAttachmentTimestampWithoutACaseLink() {
        long r = user("timestamp@x.com");
        insertFile(r, null, "REPORT", "PRIVATE", "timestamp-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE case_files SET attached_at = ? WHERE storage_key = 'timestamp-only'", at(10)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("case_files_attachment_check");
    }

    @Test
    void keepsConsentHistoryInAcceptanceOrderAndAttributed() {
        long r = user("consent@x.com");
        long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        jdbc.update("""
                INSERT INTO case_consents (case_id, user_id, consent_type, consent_version, source, accepted_at)
                VALUES (?, ?, 'SENSITIVE_DATA_COLLECTION', '2026-11-01', 'MOBILE', ?),
                       (?, ?, 'SENSITIVE_DATA_COLLECTION', '2026-10-01', 'WEB', ?)""",
                c, r, at(12), c, r, at(9));

        assertThat(jdbc.queryForList("""
                SELECT consent_version, user_id, source FROM case_consents
                WHERE case_id = ? ORDER BY accepted_at""", c))
                .extracting(row -> row.get("consent_version"), row -> row.get("user_id"), row -> row.get("source"))
                .containsExactly(tuple("2026-10-01", r, "WEB"), tuple("2026-11-01", r, "MOBILE"));
    }

    @Test
    void reportsWhetherConsentWasGiven() {
        long r = user("given@x.com");
        long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
        String sql = """
                SELECT EXISTS (SELECT 1 FROM case_consents
                               WHERE case_id = ? AND consent_type = 'SENSITIVE_DATA_COLLECTION')""";
        assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isFalse();
        insertConsent(c, r, "2026-10-01");
        assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isTrue();
    }

    private void insertAttachedFile(long uploader, long caseId, String purpose, String visibility,
                                    String key, boolean deleted) {
        jdbc.update("""
                INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key,
                    content_type, size_bytes, checksum_sha256, attached_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, 'application/pdf', 1024, ?, CURRENT_TIMESTAMP, ?)""",
                caseId, uploader, purpose, visibility, key, "a".repeat(64),
                deleted ? LocalDateTime.now() : null);
    }
}
