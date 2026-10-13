package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.notification.CaseReviewDecidedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@WithMockUser(authorities = "MODERATOR")
class ReviewNotificationEventTest extends ReviewDecisionTestSupport {
    private String moderatorEmail;

    @BeforeEach
    void prepareModerator() {
        moderatorEmail = email("moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @Test
    void anApprovalNotifiesTheReporterOnceWithIdsOnly() {
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.approve(moderatorEmail, before.getId(),
                new ApproveCaseRequest(before.getVersion(), "Private moderator note"));

        assertThat(probe.deliveredFor(before.getId())).containsExactly(new CaseReviewDecidedEvent(
                "case-review:" + response.reviewId(), reporter, before.getId(), response.reviewId(),
                CaseReviewDecidedEvent.Decision.APPROVED, DECIDED_AT));
    }

    @Test
    void aRejectionNotifiesTheReporterOnceWithIdsOnly() {
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.reject(moderatorEmail, before.getId(),
                new RejectCaseRequest(before.getVersion(), "Private moderator note"));

        assertThat(probe.deliveredFor(before.getId())).containsExactly(new CaseReviewDecidedEvent(
                "case-review:" + response.reviewId(), reporter, before.getId(), response.reviewId(),
                CaseReviewDecidedEvent.Decision.REJECTED, DECIDED_AT));
    }

    @Test
    void theEventIsDeliveredOnlyAfterTheDecisionCommits() {
        CaseRecord before = pendingCaseWithReport();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reviewService.approve(moderatorEmail, before.getId(), new ApproveCaseRequest(before.getVersion(), null));
            assertThat(probe.deliveredFor(before.getId())).isEmpty();
        });

        assertThat(probe.deliveredFor(before.getId())).hasSize(1);
    }

    @Test
    void aRolledBackDecisionDeliversNothing() {
        CaseRecord before = pendingCaseWithReport();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reviewService.reject(moderatorEmail, before.getId(), new RejectCaseRequest(before.getVersion(), "No"));
            status.setRollbackOnly();
        });

        assertUndecided(before);
    }

    @Test
    void thePayloadCarriesNoNotesOrPersonalData() {
        assertThat(Arrays.stream(CaseReviewDecidedEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("eventId", "recipientId", "caseId", "reviewId", "decision", "occurredAt");
    }
}
