package com.souldealers.crowdtracebackend.modules.governance;

import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/** The recorder fails: the comment, its reports and the event all roll back. Runs without a test transaction. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@WithMockUser(authorities = "MODERATOR")
class ModerationAuditFailureTest extends ModerationTestSupport {
    @MockitoBean private AuditRecorder auditRecorder;
    @Autowired private ModerationService moderation;

    private long comment;
    private String moderatorEmail;

    @BeforeEach
    void setUp() {
        doThrow(new AuditRecordingException("audit storage unavailable"))
                .when(auditRecorder).record(any(AuditActor.class), any(), anyLong(), any(AuditMetadata.class));
        long author = user(email("author"));
        comment = comment(liveCase(author), author, "abuse");
        report(comment, user(email("r1")), "SPAM", at(10));
        report(comment, user(email("r2")), "SPAM", at(11));
        moderatorEmail = emailOf(moderator("mod"));
    }

    @Test
    void auditFailureRollsBackRemoval() {
        assertThatThrownBy(() -> moderation.remove(moderatorEmail, comment, new RemoveCommentRequest("gone")))
                .isInstanceOf(AuditRecordingException.class);
        assertUndecided();
    }

    @Test
    void auditFailureRollsBackKeep() {
        assertThatThrownBy(() -> moderation.keep(moderatorEmail, comment, new KeepCommentRequest(null)))
                .isInstanceOf(AuditRecordingException.class);
        assertUndecided();
    }

    private void assertUndecided() {
        assertThat(jdbc.queryForMap("SELECT moderation_status, report_count FROM comments WHERE id = ?", comment))
                .containsEntry("moderation_status", "VISIBLE").containsEntry("report_count", 2);
        assertThat(jdbc.queryForList("SELECT status FROM content_reports WHERE comment_id = ?", String.class, comment))
                .containsOnly("OPEN").hasSize(2);
    }
}
