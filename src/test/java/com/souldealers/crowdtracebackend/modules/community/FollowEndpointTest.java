package com.souldealers.crowdtracebackend.modules.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class FollowEndpointTest extends CasePostgresTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private String followerEmail;
    private long followerId;
    private long liveCase;

    @BeforeEach
    void setUp() {
        followerEmail = email("follower");
        followerId = user(followerEmail);
        liveCase = approvedCase();
    }

    @Test
    void guestsCannotFollowUnfollowOrList() throws Exception {
        mvc.perform(post(followPath(liveCase))).andExpect(status().isUnauthorized());
        mvc.perform(delete(followPath(liveCase))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/follows")).andExpect(status().isUnauthorized());
        assertThat(count(liveCase)).isZero();
    }

    @Test
    void followingTwiceKeepsOneRow() throws Exception {
        mvc.perform(as(post(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        LocalDateTime firstFollowedAt = followedAt(liveCase);
        mvc.perform(as(post(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        assertThat(count(liveCase)).isEqualTo(1);
        assertThat(followedAt(liveCase)).isEqualTo(firstFollowedAt);
    }

    @Test
    void unfollowingTwiceIsHarmless() throws Exception {
        mvc.perform(as(post(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        mvc.perform(as(delete(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        mvc.perform(as(delete(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        assertThat(count(liveCase)).isZero();
    }

    @Test
    void casesThatAreNotPublicCannotBeFollowed() throws Exception {
        long submitted = caseWith(ReviewStatus.SUBMITTED);
        long rejected = caseWith(ReviewStatus.REJECTED);
        long takenDown = caseWith(ReviewStatus.TAKEN_DOWN);
        for (long caseId : new long[]{submitted, rejected, takenDown, 999_999_999L}) {
            mvc.perform(as(post(followPath(caseId)), followerEmail)).andExpect(status().isNotFound());
        }
        assertThat(count(submitted) + count(rejected) + count(takenDown)).isZero();
    }

    @Test
    void databaseRejectsDuplicateFollows() {
        jdbc.update("INSERT INTO case_follows (user_id, case_id) VALUES (?, ?)", followerId, liveCase);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO case_follows (user_id, case_id) VALUES (?, ?)",
                followerId, liveCase)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usersListOnlyTheirOwnFollowsNewestFirstAcrossPages() throws Exception {
        long older = approvedCase();
        long newer = approvedCase();
        long newest = approvedCase();
        follow(followerId, older, 1);
        follow(followerId, newer, 2);
        follow(followerId, newest, 3);
        String otherEmail = email("other");
        follow(user(otherEmail), liveCase, 4);

        String page1 = mvc.perform(as(get("/api/user/follows").param("size", "2"), followerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].followedCase.id").value(newest))
                .andExpect(jsonPath("$.data.content[1].followedCase.id").value(newer))
                .andExpect(jsonPath("$.data.content[0].followedAt").exists())
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = JSON.readTree(page1).at("/data/nextCursor").asText();
        mvc.perform(as(get("/api/user/follows").param("size", "2").param("cursor", cursor), followerEmail))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].followedCase.id").value(older))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));

        mvc.perform(as(get("/api/user/follows"), otherEmail))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].followedCase.id").value(liveCase));
    }

    @Test
    void takenDownCasesLeaveTheListButCanStillBeUnfollowed() throws Exception {
        mvc.perform(as(post(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        jdbc.update("""
                UPDATE cases SET review_status = 'TAKEN_DOWN', takedown_reason = 'Requested', taken_down_by = ?,
                taken_down_at = CURRENT_TIMESTAMP WHERE id = ?""", followerId, liveCase);

        mvc.perform(as(get("/api/user/follows"), followerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
        mvc.perform(as(delete(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        assertThat(count(liveCase)).isZero();
    }

    @Test
    void aPageOfHiddenCasesStillLeadsToTheNextPage() throws Exception {
        long visible = approvedCase();
        follow(followerId, visible, 1);
        follow(followerId, caseWith(ReviewStatus.TAKEN_DOWN), 2);

        String page1 = mvc.perform(as(get("/api/user/follows").param("size", "1"), followerEmail))
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(get("/api/user/follows").param("size", "1")
                        .param("cursor", JSON.readTree(page1).at("/data/nextCursor").asText()), followerEmail))
                .andExpect(jsonPath("$.data.content[0].followedCase.id").value(visible))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void unfollowingRemovesOnlyTheCallersFollow() throws Exception {
        long otherId = user(email("other"));
        follow(otherId, liveCase, 1);
        mvc.perform(as(delete(followPath(liveCase)), followerEmail)).andExpect(status().isNoContent());
        assertThat(count(liveCase)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidPagingInput() throws Exception {
        mvc.perform(as(get("/api/user/follows").param("size", "0"), followerEmail)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/user/follows").param("size", "51"), followerEmail)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/user/follows").param("cursor", "nope"), followerEmail))
                .andExpect(status().isBadRequest());
    }

    private long approvedCase() {
        return cases.saveAndFlush(aCase(followerId).reviewStatus(ReviewStatus.APPROVED).caseStatus(CaseStatus.MISSING)
                .approvedAt(at(9)).submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build()).getId();
    }

    private long caseWith(ReviewStatus reviewStatus) {
        boolean takenDown = reviewStatus == ReviewStatus.TAKEN_DOWN;
        return cases.saveAndFlush(aCase(followerId).reviewStatus(reviewStatus)
                .caseStatus(takenDown ? CaseStatus.MISSING : null)
                .approvedAt(takenDown ? at(9) : null)
                .takedownReason(takenDown ? "Requested" : null)
                .takenDownBy(takenDown ? followerId : null)
                .takenDownAt(takenDown ? at(10) : null)
                .submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build()).getId();
    }

    private void follow(long userId, long caseId, int hour) {
        jdbc.update("INSERT INTO case_follows (user_id, case_id, created_at) VALUES (?, ?, ?)", userId, caseId, at(hour));
    }

    private LocalDateTime followedAt(long caseId) {
        return jdbc.queryForObject("SELECT created_at FROM case_follows WHERE case_id = ?", LocalDateTime.class, caseId);
    }

    private int count(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM case_follows WHERE case_id = ?", Integer.class, caseId);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                new SecurityUser(users.findByEmail(email).orElseThrow())));
    }

    private static String followPath(long caseId) { return "/api/user/cases/" + caseId + "/follow"; }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
