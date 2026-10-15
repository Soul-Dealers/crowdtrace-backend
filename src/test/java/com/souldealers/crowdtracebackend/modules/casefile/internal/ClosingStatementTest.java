package com.souldealers.crowdtracebackend.modules.casefile.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleResponse;
import com.souldealers.crowdtracebackend.modules.casefile.CaseLifecycleService;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.SetClosingStatementRequest;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClosingStatementTest extends CasePostgresTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 2, 1, 12, 30);
    @Autowired private CaseLifecycleService service;
    @Autowired private CaseRecordRepository cases;
    private long reporter;
    private String reporterEmail;

    @BeforeEach
    void actor() {
        reporterEmail = "statement-reporter-" + UUID.randomUUID() + "@example.com";
        reporter = user(reporterEmail);
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void stripsAndReplacesStatementOnEveryResolvedOutcomeAndAuditsWithoutText() {
        for (CaseStatus status : new CaseStatus[]{CaseStatus.FOUND_SAFE, CaseStatus.FOUND_DECEASED, CaseStatus.CLOSED}) {
            CaseRecord before = resolved(status, null);

            CaseLifecycleResponse first = service.setClosingStatement(reporterEmail, before.getId(),
                    new SetClosingStatementRequest(before.getVersion(), "  They are home now.  "));
            assertThat(first.closingStatement()).isEqualTo("They are home now.");
            assertThat(first.caseVersion()).isEqualTo(before.getVersion() + 1);

            CaseRecord withStatement = cases.findById(before.getId()).orElseThrow();
            CaseLifecycleResponse replacement = service.setClosingStatement(reporterEmail, before.getId(),
                    new SetClosingStatementRequest(withStatement.getVersion(), "Family reunited."));
            assertThat(replacement.closingStatement()).isEqualTo("Family reunited.");
            assertThat(cases.findById(before.getId()).orElseThrow().getClosingStatement())
                    .isEqualTo("Family reunited.");
            var audits = jdbc.queryForList("SELECT action, metadata::text AS metadata FROM audit_events WHERE "
                    + "target_type = 'CASE' AND target_id = ? ORDER BY id", before.getId());
            assertThat(audits).hasSize(2).allSatisfy(row -> {
                assertThat(row).containsEntry("action", "CASE.CLOSING_STATEMENT_SET")
                        .containsEntry("metadata", "{}");
                assertThat(row.toString()).doesNotContain("Family reunited", "They are home now");
            });
        }
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void blankTooLongMissingOutcomeAndIdenticalStatementsAreRejected() {
        CaseRecord resolved = resolved(CaseStatus.FOUND_SAFE, "Already found");
        for (String invalid : new String[]{"", " \t\n", " \u2003", "x".repeat(2001)}) {
            assertThatThrownBy(() -> service.setClosingStatement(reporterEmail, resolved.getId(),
                    new SetClosingStatementRequest(resolved.getVersion(), invalid)))
                    .isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> service.setClosingStatement(reporterEmail, resolved.getId(),
                new SetClosingStatementRequest(resolved.getVersion(), "  Already found  ")))
                .isInstanceOf(ConflictException.class);
        CaseRecord missing = resolved(CaseStatus.MISSING, null);
        assertThatThrownBy(() -> service.setClosingStatement(reporterEmail, missing.getId(),
                new SetClosingStatementRequest(missing.getVersion(), "Not resolved")))
                .isInstanceOf(ConflictException.class);
        assertThat(auditCount(resolved.getId())).isZero();
    }

    private CaseRecord resolved(CaseStatus status, String statement) {
        return cases.saveAndFlush(CaseRecord.builder().reporterId(reporter).fullName("Test Person").age(24)
                .gender(com.souldealers.crowdtracebackend.modules.casefile.Gender.MALE)
                .lastSeenDate(LocalDate.of(2026, 10, 1))
                .region(com.souldealers.crowdtracebackend.modules.casefile.GhanaRegion.GREATER_ACCRA)
                .lastSeenLocation("Accra").physicalDescription("Description").clothing("Clothes")
                .circumstances("Circumstances").publicContactNumber("+233200000000")
                .reviewStatus(ReviewStatus.APPROVED).caseStatus(status).closingStatement(statement)
                .approvedAt(NOW.minusDays(2)).resolvedAt(status == CaseStatus.MISSING ? null : NOW.minusDays(1))
                .closedAt(status == CaseStatus.CLOSED ? NOW.minusHours(3) : null)
                .submittedAt(NOW.minusDays(3)).createdAt(NOW.minusDays(3)).updatedAt(NOW.minusDays(3)).build());
    }

    private int auditCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE target_type = 'CASE' AND target_id = ?",
                Integer.class, caseId);
    }
}
