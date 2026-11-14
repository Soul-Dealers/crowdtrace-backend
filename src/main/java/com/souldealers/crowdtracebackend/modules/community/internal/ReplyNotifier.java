package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseQueryService;
import com.souldealers.crowdtracebackend.modules.community.CommentPostedEvent;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CommentRepository;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * After a comment commits: a top-level comment tells the case's reporter; a reply tells everyone above it in its
 * thread, once each. Followers hear only about status changes. A failure is logged and never undoes the comment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ReplyNotifier {
    private final CaseQueryService caseQueries;
    private final CommentRepository comments;
    private final InAppNotifications notifications;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void commentPosted(CommentPostedEvent event) {
        try {
            if (event.parentCommentId() == null) {
                caseQueries.findReporterId(event.caseId())
                        .filter(reporter -> reporter != event.authorId())
                        .ifPresent(reporter -> notifications.notifyNewComment(event.eventId(), reporter,
                                event.caseId(), event.commentId()));
                return;
            }
            List<Long> recipients = comments.findThreadAuthorIdsFrom(event.parentCommentId(), event.authorId());
            if (!recipients.isEmpty()) {
                notifications.notifyReply(event.eventId(), recipients, event.caseId(), event.commentId());
            }
        } catch (RuntimeException exception) {
            log.warn("Comment notifications failed for event {} ({})", event.eventId(),
                    exception.getClass().getSimpleName());
        }
    }
}
