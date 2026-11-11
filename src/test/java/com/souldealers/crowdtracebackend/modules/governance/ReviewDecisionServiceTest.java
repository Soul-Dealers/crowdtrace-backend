package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import com.souldealers.crowdtracebackend.shared.UnauthorizedException;
import com.souldealers.crowdtracebackend.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(authorities = "MODERATOR")
class ReviewDecisionServiceTest extends ReviewDecisionTestSupport {
    private String moderatorEmail;
    private long moderator;

    @BeforeEach
    void prepareModerator() {
        moderatorEmail = email("moderator");
        moderator = actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @Test
    void approvalReturnsTheDecisionAndAppendsOneReviewAtTheDecisionInstant() {
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.approve(moderatorEmail, before.getId(),
                new ApproveCaseRequest(before.getVersion(), "  Looks complete  "));

        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(after.getCaseStatus()).isEqualTo(CaseStatus.MISSING);
        assertThat(response.reviewId()).isNotNull();
        assertThat(response).isEqualTo(new CaseReviewDecisionResponse(response.reviewId(), before.getId(),
                ReviewStatus.APPROVED, after.getVersion(), moderator, "Looks complete", DECIDED_AT));
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(reviewRow(response.reviewId())).containsEntry("case_id", before.getId())
                .containsEntry("reviewer_id", moderator).containsEntry("decision", "APPROVED")
                .containsEntry("review_notes", "Looks complete")
                .containsEntry("created_at", java.sql.Timestamp.valueOf(DECIDED_AT));
    }

    @Test
    void approvalNotesAreOptionalAndBlankBecomesNull() {
        for (String notes : Arrays.asList(null, "", "   ", " \t\n")) {
            CaseRecord before = pendingCaseWithReport();

            CaseReviewDecisionResponse response = reviewService.approve(moderatorEmail, before.getId(),
                    new ApproveCaseRequest(before.getVersion(), notes));

            assertThat(response.reviewNotes()).isNull();
            assertThat(reviewRow(response.reviewId()).get("review_notes")).isNull();
        }
    }

    @Test
    void rejectionReturnsTheDecisionAndKeepsTheCasePrivate() {
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.reject(moderatorEmail, before.getId(),
                new RejectCaseRequest(before.getVersion(), " Missing a recent photo "));

        CaseRecord after = cases.findById(before.getId()).orElseThrow();
        assertThat(after.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(after.getCaseStatus()).isNull();
        assertThat(after.getApprovedAt()).isNull();
        assertThat(response).isEqualTo(new CaseReviewDecisionResponse(response.reviewId(), before.getId(),
                ReviewStatus.REJECTED, after.getVersion(), moderator, "Missing a recent photo", DECIDED_AT));
        assertThat(reviewRow(response.reviewId())).containsEntry("decision", "REJECTED")
                .containsEntry("review_notes", "Missing a recent photo");
    }

    @Test
    void rejectionRequiresNotesThatAreNotBlankAfterUnicodeTrim() {
        CaseRecord before = pendingCaseWithReport();

        for (String notes : Arrays.asList(null, "", "   ", "  \t\n")) {
            assertThatThrownBy(() -> reviewService.reject(moderatorEmail, before.getId(),
                    new RejectCaseRequest(before.getVersion(), notes)))
                    .isInstanceOf(ValidationException.class);
        }
        assertUndecided(before);
    }

    @Test
    void notesAreCappedAtTwoThousandCharacters() {
        CaseRecord before = pendingCaseWithReport();
        String tooLong = "x".repeat(2001);

        assertThatThrownBy(() -> reviewService.reject(moderatorEmail, before.getId(),
                new RejectCaseRequest(before.getVersion(), tooLong))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reviewService.approve(moderatorEmail, before.getId(),
                new ApproveCaseRequest(before.getVersion(), tooLong))).isInstanceOf(ValidationException.class);
        assertUndecided(before);

        CaseReviewDecisionResponse response = reviewService.reject(moderatorEmail, before.getId(),
                new RejectCaseRequest(before.getVersion(), "x".repeat(2000)));
        assertThat(response.reviewNotes()).hasSize(2000);
    }

    @Test
    void aMissingRequestOrVersionIsInvalid() {
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.approve(moderatorEmail, before.getId(), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reviewService.reject(moderatorEmail, before.getId(), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reviewService.approve(moderatorEmail, before.getId(),
                new ApproveCaseRequest(null, null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reviewService.reject(moderatorEmail, before.getId(),
                new RejectCaseRequest(null, "Needs more detail"))).isInstanceOf(ValidationException.class);
        assertUndecided(before);
    }

    @Test
    void aModeratorCannotDecideACaseTheyReported() {
        CaseRecord own = pendingCaseWithReport(moderator);

        assertThatThrownBy(() -> reviewService.approve(moderatorEmail, own.getId(),
                new ApproveCaseRequest(own.getVersion(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reviewService.reject(moderatorEmail, own.getId(),
                new RejectCaseRequest(own.getVersion(), "Not enough"))).isInstanceOf(AccessDeniedException.class);
        assertUndecided(own);
    }

    @Test
    @WithMockUser(authorities = "SUPER_ADMIN")
    void aSuperAdminCannotDecideACaseTheyReportedEither() {
        String adminEmail = email("super-admin");
        long admin = actor(adminEmail, UserRoles.SUPER_ADMIN);
        CaseRecord own = pendingCaseWithReport(admin);

        assertThatThrownBy(() -> reviewService.approve(adminEmail, own.getId(),
                new ApproveCaseRequest(own.getVersion(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reviewService.reject(adminEmail, own.getId(),
                new RejectCaseRequest(own.getVersion(), "Not enough"))).isInstanceOf(AccessDeniedException.class);
        assertUndecided(own);
    }

    @Test
    @WithMockUser(authorities = "SUPER_ADMIN")
    void aSuperAdminCanDecideSomeoneElsesCase() {
        String adminEmail = email("super-admin");
        long admin = actor(adminEmail, UserRoles.SUPER_ADMIN);
        CaseRecord before = pendingCaseWithReport();

        CaseReviewDecisionResponse response = reviewService.approve(adminEmail, before.getId(),
                new ApproveCaseRequest(before.getVersion(), null));

        assertThat(response.reviewerId()).isEqualTo(admin);
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThatThrownBy(() -> reviewService.approve(moderatorEmail, Long.MAX_VALUE,
                new ApproveCaseRequest(0L, null))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reviewService.reject(moderatorEmail, Long.MAX_VALUE,
                new RejectCaseRequest(0L, "Not enough"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anUnknownActorIsUnauthorized() {
        CaseRecord before = pendingCaseWithReport();

        assertThatThrownBy(() -> reviewService.approve(email("ghost"), before.getId(),
                new ApproveCaseRequest(before.getVersion(), null))).isInstanceOf(UnauthorizedException.class);
        assertUndecided(before);
    }

    @Test
    @WithMockUser(authorities = "REGISTERED_USER")
    void aRegisteredUserIsDeniedBeforeAnythingHappens() {
        CaseRecord before = pendingCaseWithReport();
        String userEmail = email("user");
        actor(userEmail, UserRoles.REGISTERED_USER);

        assertThatThrownBy(() -> reviewService.approve(userEmail, before.getId(),
                new ApproveCaseRequest(before.getVersion(), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reviewService.reject(userEmail, before.getId(),
                new RejectCaseRequest(before.getVersion(), "No"))).isInstanceOf(AccessDeniedException.class);
        assertUndecided(before);
    }

    private Map<String, Object> reviewRow(long reviewId) {
        return jdbc.queryForMap("SELECT case_id, reviewer_id, decision, review_notes, created_at FROM case_reviews "
                + "WHERE id = ?", reviewId);
    }
}
