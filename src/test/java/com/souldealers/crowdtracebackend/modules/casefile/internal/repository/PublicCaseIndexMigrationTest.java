package com.souldealers.crowdtracebackend.modules.casefile.internal.repository;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCaseIndexMigrationTest extends CasePostgresTestSupport {

    @Test
    void publicSearchIndexesExist() {
        List<String> names = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'cases'", String.class);
        assertThat(names).contains("idx_cases_public_recent", "idx_cases_public_region", "idx_cases_public_outcome",
                "idx_cases_public_age", "idx_cases_public_name_trgm").doesNotContain("idx_cases_public_status");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .isOne();
    }
}
