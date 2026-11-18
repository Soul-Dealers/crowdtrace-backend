package com.souldealers.crowdtracebackend.modules.governance.internal;

import com.souldealers.crowdtracebackend.modules.governance.CommentRemovedEvent;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells reporters their report led to a removal, once the removal has committed. A failure never undoes the removal:
 * it leaves the event publication FAILED for the notification retry job, and a retry only adds what is missing.
 */
@Component
@RequiredArgsConstructor
class ReporterNotifier {
    private final InAppNotifications notifications;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void commentRemoved(CommentRemovedEvent event) {
        if (!event.reporterIds().isEmpty()) {
            notifications.notifyCommentRemoved(event.eventId(), event.reporterIds(), event.caseId());
        }
    }
}
