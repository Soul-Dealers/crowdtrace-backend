package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.governance.internal.ReviewDetailAudit;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every moderator read of a case's sensitive details leaves one audit row naming who read it. */
@WithMockUser(authorities = "MODERATOR")
class ReviewDetailAuditTest extends ReviewDecisionTestSupport {

    @Test
    void eachDetailLoadAppendsOneSensitiveViewEventWithNoMetadata() {
        String email = email("viewer");
        long moderator = actor(email, UserRoles.MODERATOR);
        CaseRecord c = pendingCaseWithReport();

        reviewService.getCaseDetail(email, c.getId());
        reviewService.getCaseDetail(email, c.getId());

        assertThat(jdbc.queryForList("SELECT actor_id, actor_role, metadata::text AS metadata FROM audit_events "
                + "WHERE action = 'CASE.SENSITIVE_DETAILS_VIEWED' AND target_type = 'CASE' AND target_id = ?",
                c.getId())).hasSize(2).allSatisfy(row -> {
                    assertThat(row.get("actor_id")).isEqualTo(moderator);
                    assertThat(row.get("actor_role")).isEqualTo("MODERATOR");
                    assertThat(row.get("metadata")).isEqualTo("{}");
                });
    }

    @Test
    void anUnknownCaseWritesNoAudit() {
        String email = email("viewer");
        actor(email, UserRoles.MODERATOR);

        assertThatThrownBy(() -> reviewService.getCaseDetail(email, 999_999L)).isInstanceOf(NotFoundException.class);

        assertThat(auditCount(999_999L)).isZero();
    }

    @Test
    void theDetailViewIsAnAuditedOperation() throws NoSuchMethodException {
        assertThat(ReviewDetailAudit.class.getMethod("recordView", String.class, Long.class)
                .getAnnotation(AuditedOperation.class).value())
                .containsExactly(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED);
    }
}
