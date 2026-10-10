package com.souldealers.crowdtracebackend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises V16's legacy backfill and revocation attribution constraints on PostgreSQL. */
@Testcontainers
class VerificationRevocationMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer LEGACY_WITHOUT_REVIEWER_POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void backfillsLegacyRevocationsAndRejectsIncompleteAttribution() {
        Flyway preRevocationMigration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("15"))
                .load();
        preRevocationMigration.migrate();
        JdbcTemplate jdbc = jdbc();

        long applicant = user(jdbc, "legacy-applicant@example.com");
        long revoker = user(jdbc, "legacy-reviewer@example.com");
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 2, 9, 30);
        long legacyRevocation = verificationRequest(jdbc, applicant, revoker, "REVOKED", createdAt, null,
                "Last decision notes");
        long applicantWithReviewTime = user(jdbc, "legacy-reviewed-applicant@example.com");
        LocalDateTime reviewedAt = createdAt.plusHours(3);
        long legacyRevocationWithReviewTime = verificationRequest(jdbc, applicantWithReviewTime, revoker,
                "REVOKED", createdAt, reviewedAt, "Reviewed legacy notes");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        var backfilled = jdbc.queryForMap("""
                SELECT reviewer_id, review_notes, reviewed_at, revoked_by, revoked_at, revocation_notes
                FROM verification_requests WHERE id = ?
                """, legacyRevocation);
        assertThat(((Number) backfilled.get("reviewer_id")).longValue()).isEqualTo(revoker);
        assertThat(backfilled.get("review_notes")).isEqualTo("Last decision notes");
        assertThat(((Number) backfilled.get("revoked_by")).longValue()).isEqualTo(revoker);
        assertThat(localDateTime(backfilled.get("revoked_at"))).isEqualTo(createdAt);
        assertThat(backfilled.get("revocation_notes")).isEqualTo("Last decision notes");
        assertThat(backfilled.get("reviewed_at")).isNull();
        var backfilledWithReviewTime = jdbc.queryForMap("""
                SELECT reviewer_id, review_notes, reviewed_at, revoked_by, revoked_at, revocation_notes
                FROM verification_requests WHERE id = ?
                """, legacyRevocationWithReviewTime);
        assertThat(((Number) backfilledWithReviewTime.get("reviewer_id")).longValue()).isEqualTo(revoker);
        assertThat(backfilledWithReviewTime.get("review_notes")).isEqualTo("Reviewed legacy notes");
        assertThat(((Number) backfilledWithReviewTime.get("revoked_by")).longValue()).isEqualTo(revoker);
        assertThat(localDateTime(backfilledWithReviewTime.get("reviewed_at"))).isEqualTo(reviewedAt);
        assertThat(localDateTime(backfilledWithReviewTime.get("revoked_at"))).isEqualTo(reviewedAt);
        assertThat(backfilledWithReviewTime.get("revocation_notes")).isEqualTo("Reviewed legacy notes");

        long pendingUser = user(jdbc, "pending-applicant@example.com");
        long pendingRequest = verificationRequest(jdbc, pendingUser, null, "PENDING", createdAt, null, null);
        List<String> revocationColumns = List.of("revoked_by", "revoked_at", "revocation_notes");
        for (int mask = 1; mask < 8; mask++) {
            int fields = mask;
            String assignments = java.util.stream.IntStream.range(0, revocationColumns.size())
                    .filter(bit -> (fields & (1 << bit)) != 0)
                    .mapToObj(bit -> revocationColumns.get(bit) + " = " + valueFor(bit, revoker, createdAt))
                    .collect(java.util.stream.Collectors.joining(", "));
            assertConstraint("verification_requests_revocation_check",
                    () -> jdbc.update("UPDATE verification_requests SET " + assignments + " WHERE id = ?", pendingRequest));
        }

        long missingRevokerUser = user(jdbc, "missing-revoker-applicant@example.com");
        assertConstraint("verification_requests_revocation_check", () -> revokedRequest(
                jdbc, missingRevokerUser, null, createdAt, createdAt));

        long missingTimeUser = user(jdbc, "missing-time-applicant@example.com");
        assertConstraint("verification_requests_revocation_check", () -> revokedRequest(
                jdbc, missingTimeUser, revoker, null, createdAt));

        long unknownRevokerUser = user(jdbc, "unknown-revoker-applicant@example.com");
        assertThatThrownBy(() -> revokedRequest(jdbc, unknownRevokerUser, 999_999L, createdAt, createdAt))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_verification_requests_revoked_by");
    }

    @Test
    void refusesToInventARevokerForAnInconsistentLegacyRow() {
        Flyway.configure()
                .dataSource(LEGACY_WITHOUT_REVIEWER_POSTGRES.getJdbcUrl(),
                        LEGACY_WITHOUT_REVIEWER_POSTGRES.getUsername(),
                        LEGACY_WITHOUT_REVIEWER_POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbc(LEGACY_WITHOUT_REVIEWER_POSTGRES);
        long applicant = user(jdbc, "legacy-without-reviewer@example.com");
        verificationRequest(jdbc, applicant, null, "REVOKED",
                LocalDateTime.of(2026, 8, 2, 9, 30), null, "Legacy notes without an actor");

        Flyway v16 = Flyway.configure()
                .dataSource(LEGACY_WITHOUT_REVIEWER_POSTGRES.getJdbcUrl(),
                        LEGACY_WITHOUT_REVIEWER_POSTGRES.getUsername(),
                        LEGACY_WITHOUT_REVIEWER_POSTGRES.getPassword())
                .load();
        assertThatThrownBy(v16::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("verification_requests_revocation_check");

        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1
                """, String.class))
                .isEqualTo("15");
    }

    private static String valueFor(int bit, long revoker, LocalDateTime createdAt) {
        return switch (bit) {
            case 0 -> Long.toString(revoker);
            case 1 -> "TIMESTAMP '" + createdAt + "'";
            case 2 -> "'revoked'";
            default -> throw new IllegalArgumentException("Unknown revocation field");
        };
    }

    private static void assertConstraint(String constraint, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static JdbcTemplate jdbc() {
        return jdbc(POSTGRES);
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static long user(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    private static LocalDateTime localDateTime(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    private static long verificationRequest(JdbcTemplate jdbc, long user, Long reviewer, String status,
                                            LocalDateTime createdAt, LocalDateTime reviewedAt, String reviewNotes) {
        return jdbc.queryForObject("""
                INSERT INTO verification_requests (user_id, verification_type, evidence_reference, status,
                    reviewer_id, review_notes, created_at, reviewed_at)
                VALUES (?, 'POLICE', 'evidence', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, user, status, reviewer, reviewNotes, createdAt, reviewedAt);
    }

    private static long revokedRequest(JdbcTemplate jdbc, long user, Long revokedBy,
                                       LocalDateTime revokedAt, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO verification_requests (user_id, verification_type, evidence_reference, status,
                    created_at, revoked_by, revoked_at)
                VALUES (?, 'POLICE', 'evidence', 'REVOKED', ?, ?, ?) RETURNING id
                """, Long.class, user, createdAt, revokedBy, revokedAt);
    }
}
