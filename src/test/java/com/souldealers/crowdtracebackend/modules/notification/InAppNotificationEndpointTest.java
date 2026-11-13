package com.souldealers.crowdtracebackend.modules.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requests must really commit: fan-out runs after commit. Every fixture uses fresh users and its own case. */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InAppNotificationEndpointTest extends CasePostgresTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LIST = "/api/user/notifications";
    private static final String UNREAD = "/api/user/notifications/unread-count";
    private static final String[] NOTIFICATION_FIELDS =
            {"id", "type", "caseId", "commentId", "caseStatus", "read", "createdAt"};

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private String reporterEmail;
    private long reporterId;
    private String followerEmail;
    private long followerId;
    private CaseRecord liveCase;

    @BeforeEach
    void setUp() {
        reporterEmail = email("reporter");
        reporterId = user(reporterEmail);
        followerEmail = email("follower");
        followerId = user(followerEmail);
        liveCase = cases.saveAndFlush(aCase(reporterId).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).submittedAt(at(8)).createdAt(at(8))
                .updatedAt(at(8)).build());
    }

    @Test
    void guestsCannotReadCountOrMark() throws Exception {
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mvc.perform(get(UNREAD)).andExpect(status().isUnauthorized());
        mvc.perform(post(LIST + "/1/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCommentNotifiesFollowersButNotItsAuthor() throws Exception {
        String authorEmail = email("author");
        long authorId = user(authorEmail);
        String bystanderEmail = email("bystander");
        user(bystanderEmail);
        follow(followerId);
        follow(authorId);

        String posted = mvc.perform(as(post("/api/user/cases/" + liveCase.getId() + "/comments"), authorEmail)
                        .content("{\"body\":\"Saw her near Kaneshie market\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long commentId = JSON.readTree(posted).at("/data/id").asLong();

        String list = mvc.perform(as(get(LIST), followerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].type").value("NEW_COMMENT"))
                .andExpect(jsonPath("$.data.content[0].caseId").value(liveCase.getId()))
                .andExpect(jsonPath("$.data.content[0].commentId").value(commentId))
                .andExpect(jsonPath("$.data.content[0].caseStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].read").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(fields(JSON.readTree(list).at("/data/content/0"))).containsExactlyInAnyOrder(NOTIFICATION_FIELDS);
        assertThat(list).doesNotContain("Kaneshie").doesNotContain(authorEmail);

        mvc.perform(as(get(UNREAD), authorEmail)).andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(as(get(UNREAD), bystanderEmail)).andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void aStatusChangeNotifiesFollowersButNotTheReporterWhoMadeIt() throws Exception {
        follow(followerId);
        follow(reporterId);

        mvc.perform(as(patch("/api/user/cases/" + liveCase.getId() + "/status"), reporterEmail)
                        .content("{\"expectedCaseVersion\":" + liveCase.getVersion() + ",\"caseStatus\":\"FOUND_SAFE\"}"))
                .andExpect(status().isOk());

        mvc.perform(as(get(LIST), followerEmail))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].type").value("CASE_STATUS_CHANGED"))
                .andExpect(jsonPath("$.data.content[0].caseId").value(liveCase.getId()))
                .andExpect(jsonPath("$.data.content[0].caseStatus").value("FOUND_SAFE"))
                .andExpect(jsonPath("$.data.content[0].commentId").value(nullValue()));
        mvc.perform(as(get(UNREAD), reporterEmail)).andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void markingReadLowersTheUnreadCountOnce() throws Exception {
        long first = notification(followerId, "e1", 1);
        notification(followerId, "e2", 2);
        notification(followerId, "e3", 3);
        mvc.perform(as(get(UNREAD), followerEmail)).andExpect(jsonPath("$.data.unreadCount").value(3));

        mvc.perform(as(post(LIST + "/" + first + "/read"), followerEmail)).andExpect(status().isNoContent());
        mvc.perform(as(post(LIST + "/" + first + "/read"), followerEmail)).andExpect(status().isNoContent());

        mvc.perform(as(get(UNREAD), followerEmail)).andExpect(jsonPath("$.data.unreadCount").value(2));
        mvc.perform(as(get(LIST), followerEmail))
                .andExpect(jsonPath("$.data.content[2].id").value(first))
                .andExpect(jsonPath("$.data.content[2].read").value(true));
    }

    @Test
    void usersSeeAndMarkOnlyTheirOwnNotifications() throws Exception {
        long theirs = notification(followerId, "e1", 1);
        String otherEmail = email("other");
        user(otherEmail);

        mvc.perform(as(get(LIST), otherEmail)).andExpect(jsonPath("$.data.content.length()").value(0));
        mvc.perform(as(post(LIST + "/" + theirs + "/read"), otherEmail)).andExpect(status().isNotFound());
        mvc.perform(as(post(LIST + "/999999999/read"), otherEmail)).andExpect(status().isNotFound());
        mvc.perform(as(get(UNREAD), followerEmail)).andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    @Test
    void listsNewestFirstAcrossPages() throws Exception {
        long older = notification(followerId, "e1", 1);
        long newer = notification(followerId, "e2", 2);
        long newest = notification(followerId, "e3", 3);

        String page1 = mvc.perform(as(get(LIST).param("size", "2"), followerEmail))
                .andExpect(jsonPath("$.data.content[0].id").value(newest))
                .andExpect(jsonPath("$.data.content[1].id").value(newer))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(get(LIST).param("size", "2")
                        .param("cursor", JSON.readTree(page1).at("/data/nextCursor").asText()), followerEmail))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(older))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void rejectsInvalidPagingInput() throws Exception {
        mvc.perform(as(get(LIST).param("size", "0"), followerEmail)).andExpect(status().isBadRequest());
        mvc.perform(as(get(LIST).param("size", "51"), followerEmail)).andExpect(status().isBadRequest());
        mvc.perform(as(get(LIST).param("cursor", "nope"), followerEmail)).andExpect(status().isBadRequest());
    }

    @Test
    void oneEventNotifiesARecipientAtMostOnce() {
        notification(followerId, "e1", 1);
        assertThatThrownBy(() -> notification(followerId, "e1", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void follow(long userId) {
        jdbc.update("INSERT INTO case_follows (user_id, case_id) VALUES (?, ?)", userId, liveCase.getId());
    }

    private long notification(long recipientId, String eventId, int hour) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (recipient_id, case_id, type, case_status, event_id, created_at)
                VALUES (?, ?, 'CASE_STATUS_CHANGED', 'FOUND_SAFE', ?, ?) RETURNING id""",
                Long.class, recipientId, liveCase.getId(), eventId, at(hour));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                new SecurityUser(users.findByEmail(email).orElseThrow()))).contentType(MediaType.APPLICATION_JSON);
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
