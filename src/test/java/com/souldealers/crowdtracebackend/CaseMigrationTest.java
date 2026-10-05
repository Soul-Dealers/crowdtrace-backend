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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the case schema constraints and indexes on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class CaseMigrationTest {

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

    private long user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""", Long.class, email);
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

    @Test
    void createsTheFourCaseTables() {
        assertThat(List.of("cases", "case_sensitive_details", "case_files", "case_consents"))
                .allMatch(this::tableExists);
    }

    @Test
    void newCaseDefaultsToUnflaggedAndHasNoPublicOutcome() {
        long id = caseFor(user("a@x.com"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT priority_minor, duplicate_flag, case_status, version FROM cases WHERE id = ?", id);
        assertThat(row).containsEntry("priority_minor", false).containsEntry("duplicate_flag", false)
                .containsEntry("case_status", null).containsEntry("version", 0L);
    }

    @Test
    void rejectsUnknownEnumValues() {
        long id = caseFor(user("b@x.com"));
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET review_status = 'PUBLISHED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET region = 'ACCRA' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET age = 131 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusesStayConsistent() {
        long id = caseFor(user("c@x.com"));
        // approved without an outcome
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET review_status = 'APPROVED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // pending with an outcome
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET case_status = 'MISSING' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // approval sets both; takedown may keep the outcome
        jdbc.update("UPDATE cases SET review_status = 'APPROVED', case_status = 'MISSING' WHERE id = ?", id);
        jdbc.update("UPDATE cases SET review_status = 'TAKEN_DOWN' WHERE id = ?", id);
    }

    @Test
    void rejectsACaseForAnUnknownReporter() {
        assertThatThrownBy(() -> caseFor(999_999L)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cannotHardDeleteAReporter() {
        long reporter = user("d@x.com");
        caseFor(reporter);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", reporter))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAReporterRelationshipOnSensitiveDetails() {
        long id = caseFor(user("e@x.com"));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO case_sensitive_details (case_id, reporter_relationship) VALUES (?, NULL)", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO case_sensitive_details (case_id, reporter_relationship) VALUES (?, 'Mother')", id);
    }

    @Test
    void rejectsAPublicReport() {
        long uploader = user("f@x.com");
        assertThatThrownBy(() -> insertFile(uploader, null, "REPORT", "PUBLIC", "k1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFile(uploader, null, "PHOTO", "PRIVATE", "k2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAnUploaderEvenBeforeAttachment() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO case_files (uploaded_by, purpose, visibility, storage_key, content_type,
                    size_bytes, checksum_sha256) VALUES (NULL, 'REPORT', 'PRIVATE', 'k3', 'application/pdf', 10, ?)""",
                "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storageKeysAreUnique() {
        long uploader = user("g@x.com");
        insertFile(uploader, null, "REPORT", "PRIVATE", "dup");
        assertThatThrownBy(() -> insertFile(uploader, null, "REPORT", "PRIVATE", "dup"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void consentVersionIsRecordedOncePerCase() {
        long reporter = user("h@x.com");
        long id = caseFor(reporter);
        insertConsent(id, reporter, "2026-10-01");
        insertConsent(id, reporter, "2026-11-01");   // history: new version is allowed
        assertThatThrownBy(() -> insertConsent(id, reporter, "2026-10-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsTheQueryIndexes() {
        assertThat(indexesOn("cases")).contains(
                "idx_cases_reporter", "idx_cases_review_queue", "idx_cases_public_status");
        assertThat(indexesOn("case_files")).contains(
                "uq_case_files_storage_key", "idx_case_files_case", "idx_case_files_unattached");
        assertThat(indexesOn("case_consents")).contains("idx_case_consents_case", "idx_case_consents_user");
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

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = ?)", Boolean.class, table));
    }

    private List<String> indexesOn(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }
}
