package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.casefile.PublicCaseResponse;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Approval only flips the status: the public view is the existing allowlist, and rejection stays private. */
@WithMockUser(authorities = "MODERATOR")
class ReviewProjectionTest extends ReviewDecisionTestSupport {
    private static final String NOTES = "Internal note for moderators only";

    @Autowired private CaseQueryService caseQueries;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String moderatorEmail;

    @BeforeEach
    void prepareModerator() {
        moderatorEmail = email("projection-moderator");
        actor(moderatorEmail, UserRoles.MODERATOR);
    }

    @Test
    void anApprovedCaseIsPublicWithOnlyTheAllowlistedFields() {
        CaseRecord c = pendingCaseWithReport();
        reviewService.approve(moderatorEmail, c.getId(), new ApproveCaseRequest(c.getVersion(), NOTES));

        String raw = mapper.writeValueAsString(caseQueries.getPublicCase(c.getId()));

        assertThat(mapper.readTree(raw).propertyNames()).containsExactlyInAnyOrder(
                "id", "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
                "physicalDescription", "clothing", "circumstances", "caseStatus", "closingStatement",
                "publicContactNumber", "approvedAt", "resolvedAt");
        assertThat(raw).doesNotContain(NOTES, reporterEmail, moderatorEmail, "reviewStatus", "version");
        assertThat(caseQueries.listPublicCases(PageRequest.of(0, 50)).content())
                .extracting(PublicCaseResponse::id).contains(c.getId());
    }

    @Test
    void aRejectedCaseStaysHiddenFromThePublic() {
        CaseRecord c = pendingCaseWithReport();
        reviewService.reject(moderatorEmail, c.getId(), new RejectCaseRequest(c.getVersion(), NOTES));

        assertThatThrownBy(() -> caseQueries.getPublicCase(c.getId())).isInstanceOf(NotFoundException.class);
        assertThat(caseQueries.listPublicCases(PageRequest.of(0, 50)).content())
                .extracting(PublicCaseResponse::id).doesNotContain(c.getId());
    }
}
