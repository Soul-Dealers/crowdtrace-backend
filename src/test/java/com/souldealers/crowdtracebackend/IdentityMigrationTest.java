package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
class IdentityMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesTheInitialIdentitySchema() {
        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("verification_requests")).isTrue();

        assertThat(columnsFor("users")).containsExactlyInAnyOrder(
                "id", "email", "password_hash", "display_name", "role", "account_status",
                "created_at", "updated_at", "deleted_at", "credentials_version");
        assertThat(columnsFor("verification_requests")).containsExactlyInAnyOrder(
                "id", "user_id", "verification_type", "evidence_reference", "status",
                "reviewer_id", "review_notes", "created_at", "reviewed_at");

        assertThat(indexExists("idx_users_email")).isTrue();
        assertThat(indexExists("idx_users_role")).isTrue();
        assertThat(indexExists("idx_verification_requests_queue")).isTrue();
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
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.indexes " +
                        "where lower(index_name) = lower(?)",
                Integer.class,
                indexName);
        return count != null && count == 1;
    }
}
