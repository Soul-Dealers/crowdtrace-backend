package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.identity.AccountContacts;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.shared.EmailDeliveryException;
import com.souldealers.crowdtracebackend.shared.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Emails the original reporter when a moderator changes their case's status. Runs after commit on the async
 * executor, so the provider never delays the lifecycle request. Reporters are not emailed about their own changes.
 * <p>
 * Each event is claimed in reporter_status_emails inside this listener's transaction: a retried event finds the
 * claim and sends nothing, and a failed send rolls the claim back and rethrows, leaving the event publication
 * FAILED for {@link NotificationRetryJob}. Logs and rethrown messages carry the event id only, never the
 * address or message.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ReporterStatusEmailer {
    private final AccountContacts contacts;
    private final ReporterStatusEmailComposer composer;
    private final EmailService email;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @ApplicationModuleListener
    public void statusChanged(CaseStatusChangedEvent event) {
        if (event.actorKind() == CaseStatusChangedEvent.ActorKind.REPORTER) return;
        if (!claim(event.eventId())) {
            log.info("Reporter status email already sent: event={}", event.eventId());
            return;
        }
        try {
            Optional<String> to = contacts.emailOf(event.reporterId());
            if (to.isEmpty()) {
                log.info("Reporter status email skipped, no active account: event={}", event.eventId());
                return;
            }
            ReporterStatusEmailComposer.Email message = composer.compose(event.caseId(), event.toStatus());
            // Covers a send the provider accepted but we never heard back about; the claim covers everything else.
            email.send(to.get(), message.subject(), message.html(), "reporter-status-email/" + event.eventId());
            log.info("Reporter status email sent: event={}", event.eventId());
        } catch (RuntimeException e) {
            // Only our own exception's message is known to be address-free.
            String reason = e instanceof EmailDeliveryException ? e.getMessage() : "";
            log.warn("Reporter status email failed: event={} error={} {}", event.eventId(),
                    e.getClass().getSimpleName(), reason);
            // The registry logs the message of what we throw, so throw one we wrote.
            throw new EmailDeliveryException("Reporter status email failed: event=" + event.eventId()
                    + " error=" + e.getClass().getSimpleName());
        }
    }

    /** True if this call took the event; false if an earlier delivery already did. Waits on a concurrent claim. */
    private boolean claim(String eventId) {
        return jdbc.update("INSERT INTO reporter_status_emails (event_id, sent_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, LocalDateTime.now(clock)) == 1;
    }
}
