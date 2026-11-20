package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

/** Users, cases, comments and reports for the comment moderation tests, seeded straight into the database. */
@AutoConfigureMockMvc
abstract class ModerationTestSupport extends CasePostgresTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    protected long moderator(String label) {
        long id = user(email(label));
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", id);
        return id;
    }

    protected String emailOf(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    protected String bearer(long userId) {
        return "Bearer " + jwt.generateToken(new SecurityUser(users.findById(userId).orElseThrow()));
    }

    protected long liveCase(long reporterId) {
        return caseWith(reporterId, ReviewStatus.APPROVED);
    }

    protected long caseWith(long reporterId, ReviewStatus reviewStatus) {
        boolean down = reviewStatus == ReviewStatus.TAKEN_DOWN;
        return cases.saveAndFlush(aCase(reporterId).reviewStatus(reviewStatus).caseStatus(CaseStatus.MISSING)
                .approvedAt(at(9)).takedownReason(down ? "Requested" : null).takenDownBy(down ? reporterId : null)
                .takenDownAt(down ? at(10) : null).submittedAt(at(8)).createdAt(at(8)).updatedAt(at(8)).build())
                .getId();
    }

    protected long comment(long caseId, long authorId, String body) {
        return jdbc.queryForObject("INSERT INTO comments (case_id, author_id, body, created_at) VALUES (?, ?, ?, ?) "
                + "RETURNING id", Long.class, caseId, authorId, body, at(9));
    }

    /** An open report; also bumps the comment's report count, as the report endpoint does. */
    protected long report(long commentId, long reporterId, String reason, LocalDateTime createdAt) {
        jdbc.update("UPDATE comments SET report_count = report_count + 1 WHERE id = ?", commentId);
        return jdbc.queryForObject("INSERT INTO content_reports (comment_id, reporter_id, reason, details, created_at) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, commentId, reporterId, reason,
                "OTHER".equals(reason) ? "something else" : null, createdAt);
    }

    protected static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
