package com.souldealers.crowdtracebackend.modules.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs without a test transaction so after-commit listeners fire. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@WithMockUser(authorities = "MODERATOR")
class ModerationNotificationTest extends ModerationTestSupport {
    @Autowired private ModerationService moderation;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private long liveCase;
    private long author;
    private long r1;
    private long r2;
    private String moderatorEmail;

    @BeforeEach
    void setUp() {
        author = user(email("author"));
        liveCase = liveCase(author);
        r1 = user(email("r1"));
        r2 = user(email("r2"));
        moderatorEmail = emailOf(moderator("mod"));
    }

    @Test
    void removalNotifiesEachReporterOnce() {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        report(c, r2, "HARASSMENT", at(11));
        moderation.remove(moderatorEmail, c, new RemoveCommentRequest("Hate speech"));
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE type = 'COMMENT_REMOVED' "
                + "AND event_id = ?", Long.class, "comment-removed:" + c)).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void keepSendsNoNotification() {
        long c = comment(liveCase, author, "fine");
        report(c, r1, "SPAM", at(10));
        moderation.keep(moderatorEmail, c, new KeepCommentRequest("Within the rules"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?", Integer.class, r1))
                .isZero();
    }

    @Test
    void redeliveryDoesNotDuplicate() {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        moderation.remove(moderatorEmail, c, new RemoveCommentRequest("gone"));
        // A replay, with a duplicated recipient for good measure.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publishEvent(CommentRemovedEvent.of(liveCase, c, List.of(r1, r1))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?", Integer.class, r1))
                .isOne();
    }

    @Test
    void notificationHasNoCommentOrNotes() {
        long c = comment(liveCase, author, "abuse");
        report(c, r1, "SPAM", at(10));
        moderation.remove(moderatorEmail, c, new RemoveCommentRequest("Secret moderator reasoning"));
        assertThat(jdbc.queryForMap("SELECT case_id, comment_id, case_status FROM notifications WHERE recipient_id = ?",
                r1)).containsEntry("case_id", liveCase).containsEntry("comment_id", null)
                .containsEntry("case_status", null);
    }
}
