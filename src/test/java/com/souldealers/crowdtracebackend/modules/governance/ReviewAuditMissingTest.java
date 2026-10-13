package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.audit.AuditMissingException;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recorder silently records nothing. Only the aspect on the concrete method can notice, so a rollback here
 * proves enforcement runs through the production proxy.
 */
@WithMockUser(authorities = "MODERATOR")
class ReviewAuditMissingTest extends ReviewDecisionTestSupport {

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void aMissingAuditRecordRollsBackTheApproval() {
        String email = moderator();
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.approve(email, before.getId(),
                new ApproveCaseRequest(before.getVersion(), null))).isInstanceOf(AuditMissingException.class);

        assertUndecided(before);
    }

    @Test
    void aMissingAuditRecordRollsBackTheRejection() {
        String email = moderator();
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.reject(email, before.getId(),
                new RejectCaseRequest(before.getVersion(), "Not enough"))).isInstanceOf(AuditMissingException.class);

        assertUndecided(before);
    }

    private String moderator() {
        String email = email("moderator");
        actor(email, UserRoles.MODERATOR);
        return email;
    }
}
