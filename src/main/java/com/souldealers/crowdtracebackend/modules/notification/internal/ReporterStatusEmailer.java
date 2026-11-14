package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.identity.AccountContacts;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.shared.EmailDeliveryException;
import com.souldealers.crowdtracebackend.shared.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Emails the original reporter when a moderator changes their case's status. Runs after commit on the async
 * executor, so the provider never delays the lifecycle request. Reporters are not emailed about their own changes.
 * Logs carry the event id only, never the address.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ReporterStatusEmailer {
    private final AccountContacts contacts;
    private final ReporterStatusEmailComposer composer;
    private final EmailService email;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void statusChanged(CaseStatusChangedEvent event) {
        if (event.actorKind() == CaseStatusChangedEvent.ActorKind.REPORTER) return;
        try {
            Optional<String> to = contacts.emailOf(event.reporterId());
            if (to.isEmpty()) {
                log.info("Reporter status email skipped, no active account: event={}", event.eventId());
                return;
            }
            ReporterStatusEmailComposer.Email message = composer.compose(event.caseId(), event.toStatus());
            email.send(to.get(), message.subject(), message.html());
            log.info("Reporter status email sent: event={}", event.eventId());
        } catch (RuntimeException e) {
            // Only our own exception's message is known to be address-free.
            String reason = e instanceof EmailDeliveryException ? e.getMessage() : "";
            log.warn("Reporter status email failed: event={} error={} {}", event.eventId(),
                    e.getClass().getSimpleName(), reason);
        }
    }
}
