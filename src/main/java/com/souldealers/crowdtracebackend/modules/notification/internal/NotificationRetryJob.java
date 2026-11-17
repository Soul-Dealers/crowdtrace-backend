package com.souldealers.crowdtracebackend.modules.notification.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retries notification and email deliveries whose after-commit listener failed. Spring Modulith keeps one
 * event_publication row per listener; a listener that throws leaves its row FAILED. Every listener is idempotent
 * (in-app rows are unique per event and recipient, emails are claimed per event), so a retry only does what is
 * still missing.
 * <p>
 * A FAILED row is retried after a backoff that doubles with each attempt, up to {@code max-attempts} in all.
 * A row stuck mid-delivery past {@code stuck-after} (the app died during it) is failed first, then retried the
 * same way. A row out of attempts stays FAILED: it is counted in {@value #EXHAUSTED_GAUGE} and logged by
 * publication id, listener and event type only, never the event's content.
 * <p>
 * One sweep runs at a time across all instances, under a Postgres advisory lock; otherwise two instances could
 * resubmit the same row and skip its backoff and attempt cap.
 */
@Component
@Slf4j
class NotificationRetryJob {
    static final String EXHAUSTED_GAUGE = "notification.deliveries.exhausted";
    /** Advisory lock key for the sweep: the ASCII of "ntfretry". */
    private static final long SWEEP_LOCK = 0x6E7466_7265747279L;

    private final IncompleteEventPublications publications;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;
    private final Duration stuckAfter;
    private final AtomicLong exhausted = new AtomicLong();

    NotificationRetryJob(IncompleteEventPublications publications, JdbcTemplate jdbc, DataSource dataSource, Clock clock,
                         MeterRegistry meters,
                         @Value("${notification.retry.max-attempts:5}") int maxAttempts,
                         @Value("${notification.retry.backoff:PT1M}") Duration backoff,
                         @Value("${notification.retry.stuck-after:PT15M}") Duration stuckAfter) {
        this.publications = publications;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.stuckAfter = stuckAfter;
        Gauge.builder(EXHAUSTED_GAUGE, exhausted, AtomicLong::get)
                .description("Notification and email deliveries that failed every retry")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${notification.retry.interval:PT1M}",
            initialDelayString = "${notification.retry.interval:PT1M}")
    void run() {
        // The lock lives on its own connection for the whole sweep; the sweep's queries use the pool as usual.
        try (Connection lock = dataSource.getConnection()) {
            if (!advisory(lock, "SELECT pg_try_advisory_lock(?)")) {
                log.debug("Notification retry sweep skipped, another instance holds it");
                return;
            }
            try {
                sweep();
            } finally {
                advisory(lock, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException e) {
            log.warn("Notification retry sweep could not take its lock: {}", e.getClass().getSimpleName());
        }
    }

    private void sweep() {
        failStuck();
        Instant now = clock.instant();
        publications.resubmitIncompletePublications(publication -> due(publication, now));
        reportExhausted();
    }

    private static boolean advisory(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, SWEEP_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** Modulith only resubmits FAILED rows, so a row left mid-delivery by a crash is failed here first. */
    private void failStuck() {
        int stuck = jdbc.update("""
                UPDATE event_publication SET status = 'FAILED'
                WHERE status IN ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')
                  AND COALESCE(last_resubmission_date, publication_date) < ?
                """, Timestamp.from(clock.instant().minus(stuckAfter)));
        if (stuck > 0) log.warn("Notification deliveries stuck mid-delivery, marked failed: count={}", stuck);
    }

    /** completion_attempts starts at 1 and grows by one per resubmission. */
    boolean due(EventPublication publication, Instant now) {
        if (publication.getStatus() != EventPublication.Status.FAILED) return false;
        int attempts = publication.getCompletionAttempts();
        if (attempts >= maxAttempts) return false;
        Instant last = publication.getLastResubmissionDate() != null
                ? publication.getLastResubmissionDate() : publication.getPublicationDate();
        return !last.plus(backoff.multipliedBy(1L << (attempts - 1))).isAfter(now);
    }

    private void reportExhausted() {
        var rows = jdbc.queryForList("""
                SELECT id, listener_id, event_type FROM event_publication
                WHERE status = 'FAILED' AND completion_attempts >= ?
                """, maxAttempts);
        if (rows.size() != exhausted.getAndSet(rows.size()) && !rows.isEmpty()) {
            rows.forEach(row -> log.error("Notification delivery exhausted retries: publication={} listener={} event={}",
                    row.get("id"), row.get("listener_id"), row.get("event_type")));
        }
    }
}
