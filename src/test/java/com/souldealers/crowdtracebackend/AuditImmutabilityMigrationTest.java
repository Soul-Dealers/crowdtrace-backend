package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises append-only audit event triggers on Flyway-migrated PostgreSQL. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditImmutabilityMigrationTest {

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
    @Order(1)
    void rejectsTruncateWhenAuditTableIsEmpty() {
        assertThat(countEvents()).isZero();

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE audit_events"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_events is append-only");

        assertThat(countEvents()).isZero();
    }

    @Test
    @Order(2)
    void rejectsTruncateWhenAuditTableHasRows() {
        long eventId = insertSystemEvent(101);

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE audit_events"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_events is append-only");

        assertThat(eventExists(eventId)).isTrue();
    }

    @Test
    @Order(3)
    void rejectsChangedAndNoOpUpdatesAndDeletes() {
        long changedUpdateId = insertSystemEvent(102);
        long noOpUpdateId = insertSystemEvent(103);
        long deleteId = insertSystemEvent(104);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET target_id = 999 WHERE id = ?", changedUpdateId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_events is append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET target_id = target_id WHERE id = ?", noOpUpdateId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_events is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", deleteId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_events is append-only");

        assertThat(eventExists(changedUpdateId)).isTrue();
        assertThat(eventExists(noOpUpdateId)).isTrue();
        assertThat(eventExists(deleteId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT target_id FROM audit_events WHERE id = ?", Long.class, changedUpdateId))
                .isEqualTo(102L);
    }

    @Test
    @Order(4)
    void actorUserCannotBeDeletedWhileAuditEventReferencesThem() {
        long actorId = user("audit-actor@x.com");
        long eventId = jdbc.queryForObject("""
                INSERT INTO audit_events (actor_id, actor_role, action, target_type, target_id)
                VALUES (?, 'REGISTERED_USER', 'CASE.SUBMITTED', 'CASE', 105)
                RETURNING id""", Long.class, actorId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", actorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(eventExists(eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, actorId))
                .isEqualTo(1);
    }

    private long insertSystemEvent(long targetId) {
        return jdbc.queryForObject("""
                INSERT INTO audit_events (actor_role, action, target_type, target_id)
                VALUES ('SYSTEM', 'CASE.RECORDED', 'CASE', ?)
                RETURNING id""", Long.class, targetId);
    }

    private long user(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""", Long.class, email);
    }

    private int countEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class);
    }

    private boolean eventExists(long eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE id = ?", Integer.class, eventId) == 1;
    }
}
