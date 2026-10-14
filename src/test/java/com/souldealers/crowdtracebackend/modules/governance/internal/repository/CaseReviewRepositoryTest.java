package com.souldealers.crowdtracebackend.modules.governance.internal.repository;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.governance.internal.model.CaseReview;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@Testcontainers
class CaseReviewRepositoryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 11, 9, 0);

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

    @Autowired CaseReviewRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void appendsApprovedAndRejectedReviewsWithHumanSourceAndSuppliedUtcTime() {
        long reviewer = user();
        long caseId = caseFor(user());

        CaseReview approved = append(CaseReview.create(caseId, reviewer, ReviewStatus.APPROVED, null, T0));
        CaseReview rejected = append(CaseReview.create(caseId, reviewer, ReviewStatus.REJECTED,
                "Insufficient detail", T0.plusMinutes(5)));

        assertThat(approved.id()).isNotNull();
        assertThat(approved.decision()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(approved.reviewNotes()).isNull();
        assertThat(rejected.reviewNotes()).isEqualTo("Insufficient detail");
        var row = jdbc.queryForMap(
                "SELECT review_source, created_at, decision FROM case_reviews WHERE id = ?", approved.id());
        assertThat(row.get("review_source")).isEqualTo("HUMAN");
        assertThat(row.get("decision")).isEqualTo("APPROVED");
        assertThat(((java.sql.Timestamp) row.get("created_at")).toLocalDateTime()).isEqualTo(T0);
        assertThat(approved.source()).isEqualTo("HUMAN");
    }

    @Test
    void writerRejectsAnEntityThatAlreadyHasAnId() {
        long caseId = caseFor(user());
        CaseReview saved = append(CaseReview.create(caseId, user(), ReviewStatus.APPROVED, null, T0));

        assertThatThrownBy(() -> append(saved)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryOnlyAcceptsFinalDecisions() {
        assertThatThrownBy(() -> CaseReview.create(1L, 1L, ReviewStatus.UNDER_REVIEW, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyIsOrderedByCreatedAtThenId() {
        long caseId = caseFor(user());
        long reviewer = user();
        CaseReview later = append(CaseReview.create(caseId, reviewer, ReviewStatus.APPROVED, null, T0.plusHours(1)));
        CaseReview tieFirst = append(CaseReview.create(caseId, reviewer, ReviewStatus.REJECTED, "a", T0));
        CaseReview tieSecond = append(CaseReview.create(caseId, reviewer, ReviewStatus.REJECTED, "b", T0));
        append(CaseReview.create(caseFor(user()), reviewer, ReviewStatus.APPROVED, null, T0));

        List<CaseReview> history = transactions.execute(s -> repository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId));

        assertThat(history).extracting(CaseReview::id)
                .containsExactly(tieFirst.id(), tieSecond.id(), later.id());
    }

    private CaseReview append(CaseReview review) {
        return transactions.execute(s -> repository.saveAndFlush(review));
    }

    private long user() {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, role, account_status)
                VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""",
                Long.class, "review-" + UUID.randomUUID() + "@example.test");
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
