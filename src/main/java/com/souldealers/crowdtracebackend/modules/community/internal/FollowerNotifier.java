package com.souldealers.crowdtracebackend.modules.community.internal;

import com.souldealers.crowdtracebackend.modules.community.internal.repository.CaseFollowRepository;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Tells a case's followers about status changes once the change has committed. A failure never undoes the change:
 * it leaves the event publication FAILED for the notification retry job, and a retry only adds what is missing.
 */
@Component
@RequiredArgsConstructor
class FollowerNotifier {
    private final CaseFollowRepository follows;
    private final InAppNotifications notifications;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void statusChanged(CaseStatusChangedEvent event) {
        // A reporter who changes their own case's status does not need telling.
        Long self = event.actorKind() == CaseStatusChangedEvent.ActorKind.REPORTER ? event.reporterId() : null;
        List<Long> recipients = followersExcept(event.caseId(), self);
        if (!recipients.isEmpty()) {
            notifications.notifyStatusChanged(event.eventId(), recipients, event.caseId(), event.toStatus());
        }
    }

    private List<Long> followersExcept(long caseId, Long excluded) {
        return follows.findFollowerIds(caseId).stream().filter(id -> !id.equals(excluded)).toList();
    }
}
