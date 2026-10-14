package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/** The recorder fails: the case, its review row and the reporter event all roll back. */
@WithMockUser(authorities = "MODERATOR")
class ReviewAuditFailureTest extends ReviewDecisionTestSupport {

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void aFailedAuditRollsBackTheApproval() {
        String email = failingModerator();
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.approve(email, before.getId(),
                new ApproveCaseRequest(before.getVersion(), null))).isInstanceOf(AuditRecordingException.class);

        assertUndecided(before);
    }

    @Test
    void aFailedAuditRollsBackTheRejection() {
        String email = failingModerator();
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.reject(email, before.getId(),
                new RejectCaseRequest(before.getVersion(), "Not enough"))).isInstanceOf(AuditRecordingException.class);

        assertUndecided(before);
    }

    private String failingModerator() {
        doThrow(new AuditRecordingException("audit storage unavailable"))
                .when(auditRecorder).record(any(AuditActor.class), any(), anyLong(), any(AuditMetadata.class));
        String email = email("moderator");
        actor(email, UserRoles.MODERATOR);
        return email;
    }
}
