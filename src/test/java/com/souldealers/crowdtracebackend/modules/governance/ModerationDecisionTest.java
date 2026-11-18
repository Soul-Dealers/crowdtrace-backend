package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RecordApplicationEvents
@TestPropertySource(properties = "rate-limit.enabled=true")
class ModerationDecisionTest extends ModerationTestSupport {
    private static final String BASE = "/api/v1/admin/moderation/comments/";

    @Autowired private ApplicationEvents events;

    private long author;
    private long r1;
    private long r2;
    private long r3;
    private long moderatorId;
    private long liveCase;

    @BeforeEach
    void setUp() {
        author = user(email("author"));
        r1 = user(email("r1"));
        r2 = user(email("r2"));
        r3 = user(email("r3"));
        moderatorId = moderator("mod");
        liveCase = liveCase(author);
    }

    @Test
    void removeResolvesAllOpenReportsWithOneAuditEvent() throws Exception {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        report(c, r2, "HARASSMENT", at(11));
        report(c, r3, "SPAM", at(12));
        remove(c, "  Targets a family member  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentId").value(c))
                .andExpect(jsonPath("$.data.resolution").value("REMOVED"))
                .andExpect(jsonPath("$.data.resolvedReports").value(3));
        assertThat(jdbc.queryForList("SELECT status, resolution, moderator_id, resolution_notes FROM content_reports "
                + "WHERE comment_id = ?", c)).hasSize(3).allSatisfy(row -> assertThat(row)
                .containsEntry("status", "RESOLVED").containsEntry("resolution", "REMOVED")
                .containsEntry("moderator_id", moderatorId)
                .containsEntry("resolution_notes", "Targets a family member"));
        assertThat(jdbc.queryForMap("SELECT moderation_status, report_count, body FROM comments WHERE id = ?", c))
                .containsEntry("moderation_status", "REMOVED").containsEntry("report_count", 0)
                .containsEntry("body", "abuse");
        assertThat(jdbc.queryForList("SELECT action, actor_role, metadata::text AS metadata FROM audit_events "
                + "WHERE target_type = 'COMMENT' AND target_id = ?", c)).singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("action", "COMMENT.REMOVED").containsEntry("actor_role", "MODERATOR");
                    assertThat((String) row.get("metadata")).contains("\"resolvedReports\": 3");
                });
    }

    @Test
    void removeLeavesEarlierDismissalsUntouched() throws Exception {
        long c = comment(liveCase, author, "abuse");
        long earlier = moderator("earlier");
        long dismissed = jdbc.queryForObject("INSERT INTO content_reports (comment_id, reporter_id, reason, status, "
                + "resolution, moderator_id, resolution_notes, created_at, resolved_at) VALUES (?, ?, 'SPAM', "
                + "'RESOLVED', 'DISMISSED', ?, 'fine then', ?, ?) RETURNING id", Long.class, c, r1, earlier, at(9),
                at(10));
        report(c, r2, "SPAM", at(11));
        remove(c, "Now it is abuse").andExpect(jsonPath("$.data.resolvedReports").value(1));
        assertThat(jdbc.queryForMap("SELECT resolution, moderator_id, resolution_notes FROM content_reports "
                + "WHERE id = ?", dismissed)).containsEntry("resolution", "DISMISSED")
                .containsEntry("moderator_id", earlier).containsEntry("resolution_notes", "fine then");
    }

    @Test
    void keepDismissesAllWithOneAuditEventPerReport() throws Exception {
        long c = comment(liveCase, author, "fair criticism");
        long a = report(c, r1, "SPAM", at(10));
        long b = report(c, r2, "MISINFORMATION", at(11));
        keep(c, "{\"notes\":\"Within the rules\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("DISMISSED"))
                .andExpect(jsonPath("$.data.resolvedReports").value(2));
        assertThat(jdbc.queryForList("SELECT target_id, metadata::text AS metadata FROM audit_events "
                + "WHERE action = 'CONTENT_REPORT.DISMISSED' ORDER BY target_id")).hasSize(2)
                .satisfiesExactly(
                        row -> assertThat(row.get("target_id")).isEqualTo(a),
                        row -> assertThat(row.get("target_id")).isEqualTo(b))
                .allSatisfy(row -> assertThat((String) row.get("metadata")).contains("\"commentId\": " + c));
        assertThat(jdbc.queryForMap("SELECT moderation_status, report_count FROM comments WHERE id = ?", c))
                .containsEntry("moderation_status", "VISIBLE").containsEntry("report_count", 0);
        assertThat(jdbc.queryForList("SELECT status, resolution, resolution_notes FROM content_reports "
                + "WHERE comment_id = ?", c)).allSatisfy(row -> assertThat(row).containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "DISMISSED").containsEntry("resolution_notes", "Within the rules"));
        assertThat(events.stream(CommentRemovedEvent.class)).isEmpty();
    }

    @Test
    void keepNotesOptional() throws Exception {
        long c = comment(liveCase, author, "fine");
        report(c, r1, "SPAM", at(10));
        keep(c, "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT resolution_notes FROM content_reports WHERE comment_id = ?",
                String.class, c)).isNull();
    }

    @Test
    void removeNotesRequired400() throws Exception {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        remove(c, "   ").andExpect(status().isBadRequest());
        remove(c, null).andExpect(status().isBadRequest());
        remove(c, "x".repeat(2001)).andExpect(status().isBadRequest());
        keep(c, "{\"notes\":\"" + "x".repeat(2001) + "\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM content_reports WHERE comment_id = ?", String.class, c))
                .isEqualTo("OPEN");
    }

    @Test
    void ownCommentGets403ForBoth() throws Exception {
        long c = comment(liveCase, moderatorId, "my own words");
        report(c, r1, "SPAM", at(10));
        remove(c, "no").andExpect(status().isForbidden());
        keep(c, "{}").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM content_reports WHERE comment_id = ?", String.class, c))
                .isEqualTo("OPEN");
    }

    @Test
    void noOpenReportsGets409() throws Exception {
        long c = comment(liveCase, author, "never reported");
        remove(c, "why").andExpect(status().isConflict());
        keep(c, "{}").andExpect(status().isConflict());
        mvcPost(BASE + "999999999/remove", "{\"notes\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void alreadyRemovedGets409() throws Exception {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        remove(c, "once").andExpect(status().isOk());
        report(c, r2, "SPAM", at(12));
        remove(c, "twice").andExpect(status().isConflict());
    }

    @Test
    void reportAfterKeepCreatesNewOpenRow201() throws Exception {
        long c = comment(liveCase, author, "borderline");
        mvc.perform(post("/api/user/comments/" + c + "/reports").header("Authorization", bearer(r1))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isCreated());
        keep(c, "{}").andExpect(status().isOk());
        mvc.perform(post("/api/user/comments/" + c + "/reports").header("Authorization", bearer(r1))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_reports WHERE comment_id = ? AND status = 'OPEN'",
                Integer.class, c)).isOne();
        assertThat(jdbc.queryForObject("SELECT report_count FROM comments WHERE id = ?", Integer.class, c)).isOne();
    }

    @Test
    void removeEventCarriesDistinctReporterIds() throws Exception {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        report(c, r2, "SPAM", at(11));
        remove(c, "gone").andExpect(status().isOk());
        assertThat(events.stream(CommentRemovedEvent.class)).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("comment-removed:" + c);
            assertThat(event.caseId()).isEqualTo(liveCase);
            assertThat(event.commentId()).isEqualTo(c);
            assertThat(event.reporterIds()).containsExactlyInAnyOrder(r1, r2);
        });
    }

    @Test
    void decisionsWorkOnTakenDownCase() throws Exception {
        long hiddenCase = caseWith(author, ReviewStatus.TAKEN_DOWN);
        long a = comment(hiddenCase, author, "a");
        long b = comment(hiddenCase, author, "b");
        report(a, r1, "SPAM", at(10));
        report(b, r1, "SPAM", at(10));
        remove(a, "gone").andExpect(status().isOk());
        keep(b, "{}").andExpect(status().isOk());
    }

    private ResultActions remove(long commentId, String notes) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("notes", notes);
        return mvcPost(BASE + commentId + "/remove", new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(body));
    }

    private ResultActions keep(long commentId, String json) throws Exception {
        return mvcPost(BASE + commentId + "/keep", json);
    }

    private ResultActions mvcPost(String path, String json) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(moderatorId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
