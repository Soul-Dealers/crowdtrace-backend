package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.governance.internal.ReviewServiceImpl;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.ConflictException;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditedOperation;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewAuditTest extends ReviewDecisionTestSupport {

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void approvalRecordsOneCaseApprovedEventWithOnlyTheReviewId() {
        String email = email("moderator");
        long moderator = actor(email, UserRoles.MODERATOR);
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.approve(email, before.getId(),
                new ApproveCaseRequest(before.getVersion(), "Private moderator note"));

        assertSingleEvent(before.getId(), "CASE.APPROVED", moderator, "MODERATOR", response.reviewId());
    }

    @Test
    @WithMockUser(authorities = "SUPER_ADMIN")
    void rejectionRecordsOneCaseRejectedEventAsTheSuperAdmin() {
        String email = email("super-admin");
        long admin = actor(email, UserRoles.SUPER_ADMIN);
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.reject(email, before.getId(),
                new RejectCaseRequest(before.getVersion(), "Private moderator note"));

        assertSingleEvent(before.getId(), "CASE.REJECTED", admin, "SUPER_ADMIN", response.reviewId());
    }

    @Test
    @WithMockUser(authorities = "MODERATOR")
    void aRefusedTransitionWritesNoReviewAuditOrEvent() {
        String email = email("moderator");
        actor(email, UserRoles.MODERATOR);
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.approve(email, before.getId(),
                new ApproveCaseRequest(before.getVersion() + 1, null))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reviewService.reject(email, before.getId(),
                new RejectCaseRequest(before.getVersion() + 1, "Stale"))).isInstanceOf(ConflictException.class);

        assertUndecided(before);
    }

    @Test
    void enforcementSitsOnTheConcreteMethodsTheAspectMatches() throws Exception {
        Method approve = ReviewServiceImpl.class.getMethod("approve", String.class, Long.class,
                ApproveCaseRequest.class);
        Method reject = ReviewServiceImpl.class.getMethod("reject", String.class, Long.class,
                RejectCaseRequest.class);

        assertThat(approve.getAnnotation(AuditedOperation.class).value()).containsExactly(AuditAction.CASE_APPROVED);
        assertThat(reject.getAnnotation(AuditedOperation.class).value()).containsExactly(AuditAction.CASE_REJECTED);
        assertThat(approve.getAnnotation(Transactional.class)).isNotNull();
        assertThat(reject.getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertSingleEvent(long caseId, String action, long actorId, String role, long reviewId) {
        var rows = jdbc.queryForList("SELECT actor_id, actor_role, action, target_type, metadata::text AS metadata "
                + "FROM audit_events WHERE target_type = 'CASE' AND target_id = ?", caseId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor_id", actorId).containsEntry("actor_role", role)
                    .containsEntry("action", action).containsEntry("target_type", "CASE");
            assertThat(row.get("metadata")).isEqualTo("{\"reviewId\": " + reviewId + "}");
        });
    }
}
