package com.souldealers.crowdtracebackend.modules.community;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CommentEndpointTest extends CasePostgresTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private String authorEmail;
    private long authorId;
    private long liveCase;

    @BeforeEach
    void setUp() {
        authorEmail = email("author");
        authorId = user(authorEmail);
        jdbc.update("UPDATE users SET display_name = 'Kwame_Tips' WHERE id = ?", authorId);
        liveCase = approvedCase(CaseStatus.MISSING);
    }

    @Test
    void guestCannotPost() throws Exception {
        mvc.perform(post(postPath(liveCase)).contentType(MediaType.APPLICATION_JSON).content(body("Saw him")))
                .andExpect(status().isUnauthorized());
        assertThat(count(liveCase)).isZero();
    }

    @Test
    void registeredUserPostsUnderTheirPseudonymWithoutPrivateFields() throws Exception {
        String response = mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("  Saw him at Madina  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.body").value("Saw him at Madina"))
                .andExpect(jsonPath("$.data.author.displayName").value("Kwame_Tips"))
                .andExpect(jsonPath("$.data.author.verified").value(false))
                .andExpect(jsonPath("$.data.author.badgeType").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = JSON.readTree(response).get("data");
        assertThat(fields(data)).containsExactlyInAnyOrder("id", "author", "body", "createdAt");
        assertThat(fields(data.get("author"))).containsExactlyInAnyOrder("displayName", "avatarSeed", "verified",
                "badgeType");
        assertThat(response).doesNotContain(authorEmail);
        Long storedAuthor = jdbc.queryForObject("SELECT author_id FROM comments WHERE id = ?", Long.class,
                data.get("id").asLong());
        assertThat(storedAuthor).isEqualTo(authorId);
    }

    @Test
    void verifiedAuthorShowsTheirBadge() throws Exception {
        jdbc.update("UPDATE users SET badge_type = 'NGO' WHERE id = ?", authorId);
        mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("Shared with our network")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.author.verified").value(true))
                .andExpect(jsonPath("$.data.author.badgeType").value("NGO"));
    }

    @Test
    void resolvedApprovedCasesStillTakeComments() throws Exception {
        long found = approvedCase(CaseStatus.FOUND_SAFE);
        mvc.perform(as(post(postPath(found)), authorEmail).content(body("So glad she is home")))
                .andExpect(status().isCreated());
    }

    @Test
    void casesThatAreNotPublicHaveNoThread() throws Exception {
        long submitted = caseWith(ReviewStatus.SUBMITTED);
        long takenDown = caseWith(ReviewStatus.TAKEN_DOWN);
        for (long caseId : new long[]{submitted, takenDown, 999_999_999L}) {
            mvc.perform(as(post(postPath(caseId)), authorEmail).content(body("Hello")))
                    .andExpect(status().isNotFound());
            mvc.perform(get(listPath(caseId))).andExpect(status().isNotFound());
        }
        assertThat(count(submitted) + count(takenDown)).isZero();
    }

    @Test
    void rejectsBlankAndOverlongBodies() throws Exception {
        mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("   ")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("x".repeat(2001))))
                .andExpect(status().isBadRequest());
        assertThat(count(liveCase)).isZero();
    }

    @Test
    void guestReadsVisibleCommentsNewestFirstAcrossPages() throws Exception {
        long first = insert(liveCase, "first", 1, false);
        long second = insert(liveCase, "second", 2, false);
        insert(liveCase, "removed", 3, true);
        long third = insert(liveCase, "third", 4, false);
        insert(approvedCase(CaseStatus.MISSING), "other case", 5, false);

        String page1 = mvc.perform(get(listPath(liveCase)).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(third))
                .andExpect(jsonPath("$.data.content[1].id").value(second))
                .andExpect(jsonPath("$.data.content[0].author.displayName").value("Kwame_Tips"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(page1).doesNotContain(authorEmail).doesNotContain("removed");
        assertThat(fields(JSON.readTree(page1).at("/data/content/0"))).containsExactlyInAnyOrder("id", "author",
                "body", "createdAt");

        String cursor = JSON.readTree(page1).at("/data/nextCursor").asText();
        mvc.perform(get(listPath(liveCase)).param("size", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(first))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void cursorFromAPostedCommentIsValid() throws Exception {
        mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("one"))).andExpect(status().isCreated());
        mvc.perform(as(post(postPath(liveCase)), authorEmail).content(body("two"))).andExpect(status().isCreated());
        String page = mvc.perform(get(listPath(liveCase)).param("size", "1"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get(listPath(liveCase)).param("size", "1")
                        .param("cursor", JSON.readTree(page).at("/data/nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    void rejectsInvalidPagingInput() throws Exception {
        mvc.perform(get(listPath(liveCase)).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(listPath(liveCase)).param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get(listPath(liveCase)).param("cursor", "not-a-cursor")).andExpect(status().isBadRequest());
    }

    private long insert(long caseId, String text, int minute, boolean removed) {
        return jdbc.queryForObject("""
                INSERT INTO comments (case_id, author_id, body, moderation_status, created_at, removed_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, caseId, authorId, text,
                removed ? "REMOVED" : "VISIBLE", at(10).plusMinutes(minute), removed ? at(11) : null);
    }

    private long approvedCase(CaseStatus status) {
        return cases.saveAndFlush(aCase(authorId).reviewStatus(ReviewStatus.APPROVED).caseStatus(status)
                .approvedAt(at(9)).resolvedAt(status == CaseStatus.MISSING ? null : at(9))
                .submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build()).getId();
    }

    private long caseWith(ReviewStatus reviewStatus) {
        return cases.saveAndFlush(aCase(authorId).reviewStatus(reviewStatus)
                .caseStatus(reviewStatus == ReviewStatus.TAKEN_DOWN ? CaseStatus.MISSING : null)
                .approvedAt(reviewStatus == ReviewStatus.TAKEN_DOWN ? at(9) : null)
                .takedownReason(reviewStatus == ReviewStatus.TAKEN_DOWN ? "Requested" : null)
                .takenDownBy(reviewStatus == ReviewStatus.TAKEN_DOWN ? authorId : null)
                .takenDownAt(reviewStatus == ReviewStatus.TAKEN_DOWN ? at(10) : null)
                .submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build()).getId();
    }

    private int count(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM comments WHERE case_id = ?", Integer.class, caseId);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                new SecurityUser(users.findByEmail(email).orElseThrow()))).contentType(MediaType.APPLICATION_JSON);
    }

    private static String body(String text) throws Exception {
        return JSON.writeValueAsString(new CreateCommentRequest(text));
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String postPath(long caseId) { return "/api/user/cases/" + caseId + "/comments"; }

    private static String listPath(long caseId) { return "/api/public/cases/" + caseId + "/comments"; }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
