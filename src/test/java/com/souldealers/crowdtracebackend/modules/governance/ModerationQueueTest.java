package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModerationQueueTest extends ModerationTestSupport {
    private static final String QUEUE = "/api/v1/admin/moderation/comments";

    private long author;
    private long r1;
    private long r2;
    private long r3;
    private long liveCase;
    private String moderatorToken;

    @BeforeEach
    void setUp() {
        author = user(email("author"));
        jdbc.update("UPDATE users SET display_name = 'Kwame_Tips' WHERE id = ?", author);
        r1 = user(email("r1"));
        r2 = user(email("r2"));
        r3 = user(email("r3"));
        liveCase = liveCase(author);
        moderatorToken = bearer(moderator("mod"));
    }

    @Test
    void queueGroupsByCommentOldestFirst() throws Exception {
        long a = comment(liveCase, author, "comment A");
        long b = comment(liveCase, author, "comment B");
        report(a, r1, "SPAM", at(12));
        report(a, r2, "SPAM", at(13));
        report(a, r3, "HARASSMENT", at(14));
        report(b, r1, "SPAM", at(11));
        mvc.perform(get(QUEUE).header("Authorization", moderatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].commentId").value(b))
                .andExpect(jsonPath("$.data.content[0].openReports").value(1))
                .andExpect(jsonPath("$.data.content[1].commentId").value(a))
                .andExpect(jsonPath("$.data.content[1].openReports").value(3))
                .andExpect(jsonPath("$.data.content[1].oldestOpenAt").value("2026-10-01T12:00:00"))
                .andExpect(jsonPath("$.data.content[1].newestOpenAt").value("2026-10-01T14:00:00"))
                .andExpect(jsonPath("$.data.content[1].caseId").value(liveCase))
                .andExpect(jsonPath("$.data.content[1].caseName").value("Kofi Mensah"))
                .andExpect(jsonPath("$.data.content[1].casePublic").value(true))
                .andExpect(jsonPath("$.data.content[1].bodyExcerpt").value("comment A"))
                .andExpect(jsonPath("$.data.content[1].author.displayName").value("Kwame_Tips"))
                .andExpect(jsonPath("$.data.content[1].authorDeleted").value(false));
    }

    @Test
    void queueCountsReasons() throws Exception {
        long a = comment(liveCase, author, "x".repeat(300));
        report(a, r1, "SPAM", at(12));
        report(a, r2, "SPAM", at(13));
        report(a, r3, "OTHER", at(14));
        mvc.perform(get(QUEUE).header("Authorization", moderatorToken))
                .andExpect(jsonPath("$.data.content[0].reasons.SPAM").value(2))
                .andExpect(jsonPath("$.data.content[0].reasons.OTHER").value(1))
                .andExpect(jsonPath("$.data.content[0].reasons.HARASSMENT").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].bodyExcerpt").value("x".repeat(200)));
    }

    @Test
    void queueExcludesResolved() throws Exception {
        long a = comment(liveCase, author, "kept");
        long moderatorId = moderator("other-mod");
        jdbc.update("INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution, moderator_id, "
                + "created_at, resolved_at) VALUES (?, ?, 'SPAM', 'RESOLVED', 'DISMISSED', ?, ?, ?)",
                a, r1, moderatorId, at(10), at(11));
        mvc.perform(get(QUEUE).header("Authorization", moderatorToken))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void queueIncludesTakenDownCase() throws Exception {
        long hidden = comment(caseWith(author, ReviewStatus.TAKEN_DOWN), author, "on a hidden case");
        jdbc.update("UPDATE comments SET body = '[deleted]', deleted_at = now() WHERE id = ?", hidden);
        report(hidden, r1, "SPAM", at(12));
        mvc.perform(get(QUEUE).header("Authorization", moderatorToken))
                .andExpect(jsonPath("$.data.content[0].commentId").value(hidden))
                .andExpect(jsonPath("$.data.content[0].casePublic").value(false))
                .andExpect(jsonPath("$.data.content[0].authorDeleted").value(true))
                .andExpect(jsonPath("$.data.content[0].author").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].bodyExcerpt").value(nullValue()));
    }

    @Test
    void queueSizeAbove50Gets400() throws Exception {
        mvc.perform(get(QUEUE).param("size", "51").header("Authorization", moderatorToken))
                .andExpect(status().isBadRequest());
        mvc.perform(get(QUEUE).param("size", "50").header("Authorization", moderatorToken))
                .andExpect(status().isOk());
    }

    @Test
    void registeredUserGets403() throws Exception {
        long a = comment(liveCase, author, "x");
        mvc.perform(get(QUEUE).header("Authorization", bearer(r1))).andExpect(status().isForbidden());
        mvc.perform(get(QUEUE + "/" + a).header("Authorization", bearer(r1))).andExpect(status().isForbidden());
        mvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
    }

    @Test
    void detailShowsAllReportsAndRemovedBody() throws Exception {
        long a = comment(liveCase, author, "the original words");
        long moderatorId = moderator("earlier-mod");
        jdbc.update("INSERT INTO content_reports (comment_id, reporter_id, reason, status, resolution, moderator_id, "
                + "resolution_notes, created_at, resolved_at) VALUES (?, ?, 'SPAM', 'RESOLVED', 'DISMISSED', ?, "
                + "'fine', ?, ?)", a, r1, moderatorId, at(10), at(11));
        report(a, r2, "OTHER", at(12));
        jdbc.update("UPDATE comments SET moderation_status = 'REMOVED', removed_at = now() WHERE id = ?", a);
        mvc.perform(get(QUEUE + "/" + a).header("Authorization", moderatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comment.body").value("the original words"))
                .andExpect(jsonPath("$.data.comment.removed").value(true))
                .andExpect(jsonPath("$.data.comment.authorId").value(author))
                .andExpect(jsonPath("$.data.caseName").value("Kofi Mensah"))
                .andExpect(jsonPath("$.data.casePublic").value(true))
                .andExpect(jsonPath("$.data.reports.length()").value(2))
                .andExpect(jsonPath("$.data.reports[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.reports[0].resolution").value("DISMISSED"))
                .andExpect(jsonPath("$.data.reports[0].moderatorId").value(moderatorId))
                .andExpect(jsonPath("$.data.reports[0].resolutionNotes").value("fine"))
                .andExpect(jsonPath("$.data.reports[1].reason").value("OTHER"))
                .andExpect(jsonPath("$.data.reports[1].details").value("something else"))
                .andExpect(jsonPath("$.data.reports[1].status").value("OPEN"));
        mvc.perform(get(QUEUE + "/999999999").header("Authorization", moderatorToken))
                .andExpect(status().isNotFound());
    }
}
