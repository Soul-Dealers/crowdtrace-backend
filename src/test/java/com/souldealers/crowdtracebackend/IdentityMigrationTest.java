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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migrations run against PostgreSQL, not H2.
 *
 * <p>They are written for PostgreSQL — V8 relies on an ON CONFLICT upsert and V10 on a
 * partial unique index, neither of which H2 parses. Validating them on H2 proved the
 * wrong thing: it rejected V10 with a syntax error while the statement is correct for
 * every environment that actually runs it.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class IdentityMigrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesTheInitialIdentitySchema() {
        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("verification_requests")).isTrue();

        assertThat(columnsFor("users")).containsExactlyInAnyOrder(
                "id", "email", "password_hash", "display_name", "role", "account_status",
                "created_at", "updated_at", "deleted_at", "credentials_version", "badge_type");
        assertThat(columnsFor("verification_requests")).containsExactlyInAnyOrder(
                "id", "user_id", "verification_type", "evidence_reference", "status",
                "reviewer_id", "review_notes", "created_at", "reviewed_at");

        assertThat(indexExists("idx_users_email")).isTrue();
        assertThat(indexExists("idx_users_role")).isTrue();
        assertThat(indexExists("idx_verification_requests_queue")).isTrue();
        assertThat(indexExists("uq_verification_requests_active_user")).isTrue();
        assertThat(indexExists("uq_verification_requests_active_type")).isFalse();
        assertThat(indexExists("idx_users_badge_type")).isTrue();
    }

    /** The column is the display source of truth, so the database pins its vocabulary. */
    @Test
    void restrictsUsersBadgeTypeToTheKnownBadges() {
        jdbcTemplate.update("""
                insert into users (email, password_hash, display_name, role, account_status,
                                   created_at, updated_at, credentials_version)
                values ('badge-check@example.com', 'hash', 'Badge Check', 'REGISTERED_USER', 'ACTIVE',
                        now(), now(), 0)
                """);

        assertThat(jdbcTemplate.update(
                "update users set badge_type = 'NGO' where email = 'badge-check@example.com'")).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update users set badge_type = 'DETECTIVE' where email = 'badge-check@example.com'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_badge_type_check");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables " +
                "where lower(table_schema) = 'public' and lower(table_name) = lower(?)",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private List<String> columnsFor(String tableName) {
        return jdbcTemplate.queryForList(
                "select column_name from information_schema.columns " +
                "where lower(table_schema) = 'public' and lower(table_name) = lower(?) " +
                        "order by ordinal_position",
                String.class,
                tableName);
    }

    private boolean indexExists(String indexName) {
        // pg_indexes, not information_schema.indexes: the latter is an H2 extension
        // and is not part of the SQL standard PostgreSQL implements.
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes " +
                        "where schemaname = 'public' and lower(indexname) = lower(?)",
                Integer.class,
                indexName);
        return count != null && count == 1;
    }
}
