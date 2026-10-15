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

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the lifecycle shape checks on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class CaseLifecycleMigrationTest {

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
    void acceptsLiveResolvedClosedAndFullyAttributedTakenDownShapes() {
        long reporter = user("lifecycle-valid@example.com");

        assertThat(insertCase(reporter, "APPROVED", "MISSING", null, null, null, null, null)).isPositive();
        assertThat(insertCase(reporter, "APPROVED", "FOUND_SAFE", "2026-10-01 10:00:00", null,
                null, null, null)).isPositive();
        assertThat(insertCase(reporter, "APPROVED", "FOUND_DECEASED", "2026-10-01 10:00:00", null,
                "Family has been notified", null, null)).isPositive();
        assertThat(insertCase(reporter, "APPROVED", "CLOSED", "2026-10-01 10:00:00",
                "2026-10-01 11:00:00", "Case closed", null, null)).isPositive();
        assertThat(insertCase(reporter, "TAKEN_DOWN", "MISSING", null, null, null,
                "Verified hoax report", reporter)).isPositive();
    }

    @Test
    void rejectsTakenDownWithoutReason() {
        assertRejected(reporter -> insertCase(reporter, "TAKEN_DOWN", "MISSING", null, null,
                null, null, reporter));
    }

    @Test
    void rejectsTakenDownWithoutActor() {
        assertRejected(reporter -> insertCase(reporter, "TAKEN_DOWN", "MISSING", null, null,
                null, "Verified hoax report", null));
    }

    @Test
    void rejectsTakenDownWithoutTime() {
        long reporter = user("lifecycle-takedown-time@example.com");
        long id = insertCase(reporter, "APPROVED", "MISSING", null, null, null, null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET review_status = 'TAKEN_DOWN', " +
                "takedown_reason = 'Verified hoax report', taken_down_by = ?, taken_down_at = NULL WHERE id = ?",
                reporter, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialTakedownAttribution() {
        long reporter = user("lifecycle-partial-takedown@example.com");
        long id = insertCase(reporter, "APPROVED", "MISSING", null, null, null, null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET takedown_reason = 'Verified hoax report' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTakenDownWithoutOutcome() {
        assertRejected(reporter -> insertCase(reporter, "TAKEN_DOWN", null, null, null,
                null, "Verified hoax report", reporter));
    }

    @Test
    void rejectsResolvedOutcomeWithoutResolvedAt() {
        assertRejected(reporter -> insertCase(reporter, "APPROVED", "FOUND_SAFE", null, null,
                null, null, null));
    }

    @Test
    void rejectsClosedAtForANonClosedOutcome() {
        assertRejected(reporter -> insertCase(reporter, "APPROVED", "MISSING", null,
                "2026-10-01 11:00:00", null, null, null));
    }

    @Test
    void rejectsResolvedAtWhenOutcomeIsNull() {
        assertRejected(reporter -> insertCase(reporter, "SUBMITTED", null, "2026-10-01 10:00:00",
                null, null, null, null));
    }

    @Test
    void rejectsClosedAtWhenOutcomeIsNull() {
        assertRejected(reporter -> insertCase(reporter, "SUBMITTED", null, null,
                "2026-10-01 11:00:00", null, null, null));
    }

    @Test
    void rejectsClosingStatementForMissingCase() {
        assertRejected(reporter -> insertCase(reporter, "APPROVED", "MISSING", null, null,
                "Still looking", null, null));
    }

    @Test
    void rejectsClosingStatementWhenOutcomeIsNull() {
        assertRejected(reporter -> insertCase(reporter, "SUBMITTED", null, null, null,
                "Still looking", null, null));
    }

    @Test
    void rejectsBlankClosingStatement() {
        assertRejected(reporter -> insertCase(reporter, "APPROVED", "FOUND_SAFE", "2026-10-01 10:00:00",
                null, "   ", null, null));
    }

    @Test
    void rejectsClosingStatementLongerThanTwoThousandCharacters() {
        long reporter = user("lifecycle-long-statement@example.com");
        long id = insertCase(reporter, "APPROVED", "FOUND_SAFE", "2026-10-01 10:00:00", null,
                null, null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE cases SET closing_statement = ? WHERE id = ?",
                "x".repeat(2001), id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertRejected(Consumer<Long> insert) {
        long reporter = user("lifecycle-invalid-" + System.nanoTime() + "@example.com");
        assertThatThrownBy(() -> insert.accept(reporter)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    private long insertCase(long reporterId, String reviewStatus, String caseStatus,
                            String resolvedAt, String closedAt, String statement,
                            String takedownReason, Long takenDownBy) {
        return jdbc.queryForObject("""
                INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                    last_seen_location, physical_description, clothing, circumstances,
                    public_contact_number, review_status, case_status, resolved_at, closed_at,
                    closing_statement, takedown_reason, taken_down_by, taken_down_at, submitted_at)
                VALUES (?, 'Kofi Mensah', 12, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA',
                    'Madina market', 'Slim, 1.4m', 'Blue school uniform', 'Did not return from school',
                    '+233200000000', ?, ?, ?::timestamp, ?::timestamp, ?, ?, ?,
                    CASE WHEN ?::bigint IS NULL THEN NULL ELSE TIMESTAMP '2026-10-01 12:00:00' END, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, reporterId, reviewStatus, caseStatus, resolvedAt, closedAt, statement,
                takedownReason, takenDownBy, takenDownBy);
    }
}
