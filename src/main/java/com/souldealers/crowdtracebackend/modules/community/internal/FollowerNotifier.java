package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.community.CommentPostedEvent;
import com.souldealers.crowdtracebackend.modules.community.internal.repository.CaseFollowRepository;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Tells a case's followers about new comments and status changes once the change has committed. A failure is logged
 * and never undoes the change itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class FollowerNotifier {
    private final CaseFollowRepository follows;
    private final InAppNotifications notifications;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void commentPosted(CommentPostedEvent event) {
        try {
            List<Long> recipients = followersExcept(event.caseId(), event.authorId());
            if (!recipients.isEmpty()) {
                notifications.notifyNewComment(event.eventId(), recipients, event.caseId(), event.commentId());
            }
        } catch (RuntimeException exception) {
            log.warn("Comment notifications failed for event {} ({})", event.eventId(),
                    exception.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void statusChanged(CaseStatusChangedEvent event) {
        try {
            // A reporter who changes their own case's status does not need telling.
            Long self = event.actorKind() == CaseStatusChangedEvent.ActorKind.REPORTER ? event.reporterId() : null;
            List<Long> recipients = followersExcept(event.caseId(), self);
            if (!recipients.isEmpty()) {
                notifications.notifyStatusChanged(event.eventId(), recipients, event.caseId(), event.toStatus());
            }
        } catch (RuntimeException exception) {
            log.warn("Status notifications failed for event {} ({})", event.eventId(),
                    exception.getClass().getSimpleName());
        }
    }

    private List<Long> followersExcept(long caseId, Long excluded) {
        return follows.findFollowerIds(caseId).stream().filter(id -> !id.equals(excluded)).toList();
    }
}
