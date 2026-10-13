package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.UserRoles;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP surface of the review queue and decisions; fixtures are committed because detail reads in its own snapshot. */
@AutoConfigureMockMvc
class ReviewEndpointTest extends ReviewDecisionTestSupport {
    private static final String BASE = "/api/v1/admin/review/cases";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    private String token(String email) {
        return "Bearer " + jwt.generateToken(new SecurityUser(users.findByEmail(email).orElseThrow()));
    }

    private String actorToken(UserRoles role) {
        String email = email(role.name().toLowerCase());
        actor(email, role);
        return token(email);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }

    private static String approveBody(long version) {
        return "{\"expectedCaseVersion\":" + version + "}";
    }

    @Test
    void guestGets401OnEveryEndpoint() throws Exception {
        CaseRecord c = pendingCaseWithReport();
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + c.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + c.getId() + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content(approveBody(c.getVersion()))).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + c.getId() + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":0,\"reviewNotes\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void registeredUserGets403OnEveryEndpointAndNothingChanges() throws Exception {
        CaseRecord c = pendingCaseWithReport();
        String t = actorToken(UserRoles.REGISTERED_USER);
        mvc.perform(as(get(BASE), t)).andExpect(status().isForbidden());
        mvc.perform(as(get(BASE + "/" + c.getId()), t)).andExpect(status().isForbidden());
        mvc.perform(as(post(BASE + "/" + c.getId() + "/approve"), t).content(approveBody(c.getVersion())))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(BASE + "/" + c.getId() + "/reject"), t)
                .content("{\"expectedCaseVersion\":" + c.getVersion() + ",\"reviewNotes\":\"no\"}"))
                .andExpect(status().isForbidden());
        assertUndecided(c);
    }

    @Test
    void moderatorAndSuperAdminCanListAndOpen() throws Exception {
        CaseRecord c = pendingCaseWithReport();
        for (UserRoles role : new UserRoles[]{UserRoles.MODERATOR, UserRoles.SUPER_ADMIN}) {
            String t = actorToken(role);
            mvc.perform(as(get(BASE).param("size", "50"), t)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id", hasItem(c.getId().intValue())));
            mvc.perform(as(get(BASE + "/" + c.getId()), t)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.caseDetails.id").value(c.getId()))
                    .andExpect(jsonPath("$.data.caseDetails.version").value(c.getVersion()))
                    .andExpect(jsonPath("$.data.caseDetails.files").doesNotExist())
                    .andExpect(jsonPath("$.data.evidence").isArray());
        }
    }

    @Test
    void listFiltersBindIsoDateTimes() throws Exception {
        pendingCaseWithReport();
        String t = actorToken(UserRoles.MODERATOR);
        mvc.perform(as(get(BASE).param("submittedFrom", "2000-01-01T00:00:00")
                .param("submittedTo", "2100-01-01T00:00:00").param("priorityMinor", "false"), t))
                .andExpect(status().isOk());
        mvc.perform(as(get(BASE).param("submittedFrom", "yesterday"), t)).andExpect(status().isBadRequest());
        mvc.perform(as(get(BASE).param("status", "APPROVED"), t)).andExpect(status().isBadRequest());
    }

    @Test
    void moderatorApprovesAndRejectsWithTheActorFromTheTokenNotTheBody() throws Exception {
        String modEmail = email("mod");
        long mod = actor(modEmail, UserRoles.MODERATOR);
        String t = token(modEmail);
        CaseRecord toApprove = pendingCaseWithReport();
        mvc.perform(as(post(BASE + "/" + toApprove.getId() + "/approve"), t)
                .content("{\"expectedCaseVersion\":" + toApprove.getVersion()
                        + ",\"reviewerId\":999999,\"reviewNotes\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.caseId").value(toApprove.getId()))
                .andExpect(jsonPath("$.data.reviewerId").value(mod))
                .andExpect(jsonPath("$.data.reviewNotes").value("ok"));
        assertThat(cases.findById(toApprove.getId()).orElseThrow().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);

        CaseRecord toReject = pendingCaseWithReport();
        mvc.perform(as(post(BASE + "/" + toReject.getId() + "/reject"), t)
                .content("{\"expectedCaseVersion\":" + toReject.getVersion() + ",\"reviewNotes\":\"Fake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewerId").value(mod));
        assertThat(cases.findById(toReject.getId()).orElseThrow().getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void invalidBodiesAre400AndChangeNothing() throws Exception {
        CaseRecord c = pendingCaseWithReport();
        String t = actorToken(UserRoles.MODERATOR);
        mvc.perform(as(post(BASE + "/" + c.getId() + "/approve"), t).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(BASE + "/" + c.getId() + "/approve"), t).content("{\"expectedCaseVersion\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(BASE + "/" + c.getId() + "/reject"), t)
                .content("{\"expectedCaseVersion\":" + c.getVersion() + ",\"reviewNotes\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(BASE + "/" + c.getId() + "/reject"), t)
                .content("{\"expectedCaseVersion\":" + c.getVersion() + "}"))
                .andExpect(status().isBadRequest());
        assertUndecided(c);
    }

    @Test
    void errorCodesFollowTheDecisionTable() throws Exception {
        String t = actorToken(UserRoles.MODERATOR);
        mvc.perform(as(get(BASE + "/999999999"), t)).andExpect(status().isNotFound());
        mvc.perform(as(post(BASE + "/999999999/approve"), t).content(approveBody(0)))
                .andExpect(status().isNotFound());

        CaseRecord stale = pendingCaseWithReport();
        mvc.perform(as(post(BASE + "/" + stale.getId() + "/approve"), t).content(approveBody(stale.getVersion() + 5)))
                .andExpect(status().isConflict());

        CaseRecord decided = pendingCaseWithReport();
        mvc.perform(as(post(BASE + "/" + decided.getId() + "/approve"), t).content(approveBody(decided.getVersion())))
                .andExpect(status().isOk());
        mvc.perform(as(post(BASE + "/" + decided.getId() + "/approve"), t).content(approveBody(decided.getVersion())))
                .andExpect(status().isConflict());

        String modEmail = email("selfmod");
        long self = actor(modEmail, UserRoles.MODERATOR);
        CaseRecord own = pendingCaseWithReport(self);
        mvc.perform(as(post(BASE + "/" + own.getId() + "/approve"), token(modEmail)).content(approveBody(own.getVersion())))
                .andExpect(status().isForbidden());
    }
}
