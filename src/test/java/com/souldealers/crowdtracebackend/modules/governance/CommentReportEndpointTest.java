package com.souldealers.crowdtracebackend.modules.governance;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "rate-limit.enabled=true")
class CommentReportEndpointTest extends CasePostgresTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private long authorId;
    private String authorEmail;
    private String reporterEmail;
    private long reporterId;
    private long liveCase;
    private long comment;

    @BeforeEach
    void setUp() {
        authorEmail = email("author");
        authorId = user(authorEmail);
        reporterEmail = email("reporter");
        reporterId = user(reporterEmail);
        liveCase = caseWith(ReviewStatus.APPROVED);
        comment = comment(liveCase);
    }

    @Test
    void guestGets401() throws Exception {
        mvc.perform(post(path(comment)).contentType(MediaType.APPLICATION_JSON).content(body("SPAM", null)))
                .andExpect(status().isUnauthorized());
        assertThat(reports(comment)).isZero();
    }

    @Test
    void reportCreates201AndIncrementsCount() throws Exception {
        report(reporterEmail, comment, "HARASSMENT", "  name-calling  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportId").isNumber())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        assertThat(reportCount(comment)).isOne();
        assertThat(jdbc.queryForMap("SELECT reporter_id, reason, details, status FROM content_reports "
                + "WHERE comment_id = ?", comment))
                .containsEntry("reporter_id", reporterId).containsEntry("reason", "HARASSMENT")
                .containsEntry("details", "name-calling").containsEntry("status", "OPEN");
    }

    @Test
    void duplicateReturns200SameIdCountUnchanged() throws Exception {
        long first = reportId(report(reporterEmail, comment, "SPAM", null).andExpect(status().isCreated()));
        long second = reportId(report(reporterEmail, comment, "PRIVACY", null).andExpect(status().isOk()));
        assertThat(second).isEqualTo(first);
        assertThat(reportCount(comment)).isOne();
        assertThat(reports(comment)).isOne();
    }

    @Test
    void ownCommentGets403() throws Exception {
        report(authorEmail, comment, "SPAM", null).andExpect(status().isForbidden());
        assertThat(reports(comment)).isZero();
    }

    @Test
    void removedDeletedNonPublicGet404() throws Exception {
        long removed = comment(liveCase);
        jdbc.update("UPDATE comments SET moderation_status='REMOVED', removed_at=now() WHERE id=?", removed);
        long deleted = comment(liveCase);
        jdbc.update("UPDATE comments SET body='[deleted]', deleted_at=now() WHERE id=?", deleted);
        long hidden = comment(caseWith(ReviewStatus.TAKEN_DOWN));
        for (long id : new long[]{removed, deleted, hidden, 999_999_999L}) {
            report(reporterEmail, id, "SPAM", null).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_reports", Integer.class)).isZero();
    }

    @Test
    void otherWithoutDetailsGets400() throws Exception {
        report(reporterEmail, comment, "OTHER", null).andExpect(status().isBadRequest());
        report(reporterEmail, comment, "OTHER", "   ").andExpect(status().isBadRequest());
        report(reporterEmail, comment, null, null).andExpect(status().isBadRequest());
        report(reporterEmail, comment, "OTHER", "Shares a home address").andExpect(status().isCreated());
    }

    @Test
    void detailsOver1000Gets400() throws Exception {
        report(reporterEmail, comment, "SPAM", "x".repeat(1001)).andExpect(status().isBadRequest());
        assertThat(reports(comment)).isZero();
    }

    @Test
    void eleventhReportInAnHourGets429() throws Exception {
        for (int i = 0; i < 10; i++) {
            report(reporterEmail, comment(liveCase), "SPAM", null).andExpect(status().is2xxSuccessful());
        }
        report(reporterEmail, comment, "SPAM", null).andExpect(status().isTooManyRequests());
        assertThat(reports(comment)).isZero();
    }

    @Test
    void refusedAttemptsDoNotConsumeQuota() throws Exception {
        for (int i = 0; i < 10; i++) {
            report(reporterEmail, 999_999_999L, "SPAM", null).andExpect(status().isNotFound());
        }
        report(reporterEmail, comment, "SPAM", null).andExpect(status().isCreated());
    }

    private ResultActions report(String email, long commentId, String reason, String details) throws Exception {
        return mvc.perform(post(path(commentId)).header("Authorization", "Bearer " + jwt.generateToken(
                        new SecurityUser(users.findByEmail(email).orElseThrow())))
                .contentType(MediaType.APPLICATION_JSON).content(body(reason, details)));
    }

    private static long reportId(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/reportId").asLong();
    }

    private static String body(String reason, String details) throws Exception {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("reason", reason);
        fields.put("details", details);
        return JSON.writeValueAsString(fields);
    }

    private long comment(long caseId) {
        return jdbc.queryForObject("INSERT INTO comments (case_id, author_id, body) VALUES (?, ?, 'rude') "
                + "RETURNING id", Long.class, caseId, authorId);
    }

    private int reportCount(long commentId) {
        return jdbc.queryForObject("SELECT report_count FROM comments WHERE id = ?", Integer.class, commentId);
    }

    private int reports(long commentId) {
        return jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE comment_id = ?", Integer.class,
                commentId);
    }

    private long caseWith(ReviewStatus reviewStatus) {
        boolean down = reviewStatus == ReviewStatus.TAKEN_DOWN;
        return cases.saveAndFlush(aCase(authorId).reviewStatus(reviewStatus).caseStatus(CaseStatus.MISSING)
                .approvedAt(at(9)).takedownReason(down ? "Requested" : null).takenDownBy(down ? authorId : null)
                .takenDownAt(down ? at(10) : null).submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build())
                .getId();
    }

    private static String path(long commentId) { return "/api/user/comments/" + commentId + "/reports"; }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
