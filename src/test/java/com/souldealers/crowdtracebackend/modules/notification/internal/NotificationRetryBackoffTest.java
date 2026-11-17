package com.souldealers.crowdtracebackend.modules.notification.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The retry schedule: one minute, then two, then four, and nothing once the attempts run out. */
class NotificationRetryBackoffTest {
    private static final Instant PUBLISHED = Instant.parse("2026-11-16T12:00:00Z");

    private final NotificationRetryJob job = new NotificationRetryJob(mock(IncompleteEventPublications.class),
            mock(JdbcTemplate.class), mock(DataSource.class), Clock.systemUTC(), new SimpleMeterRegistry(),
            4, Duration.ofMinutes(1), Duration.ofMinutes(15));

    @Test
    void theFirstRetryWaitsOneMinuteFromPublication() {
        EventPublication failed = failed(1, null);

        assertThat(job.due(failed, PUBLISHED.plusSeconds(59))).isFalse();
        assertThat(job.due(failed, PUBLISHED.plusSeconds(60))).isTrue();
    }

    @Test
    void eachRetryDoublesTheWaitFromTheLastOne() {
        Instant lastRetry = PUBLISHED.plusSeconds(600);

        assertThat(job.due(failed(2, lastRetry), lastRetry.plusSeconds(119))).isFalse();
        assertThat(job.due(failed(2, lastRetry), lastRetry.plusSeconds(120))).isTrue();
        assertThat(job.due(failed(3, lastRetry), lastRetry.plusSeconds(239))).isFalse();
        assertThat(job.due(failed(3, lastRetry), lastRetry.plusSeconds(240))).isTrue();
    }

    @Test
    void aPublicationOutOfAttemptsIsNeverDue() {
        assertThat(job.due(failed(4, PUBLISHED), PUBLISHED.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void onlyFailedPublicationsAreDue() {
        EventPublication processing = failed(1, null);
        when(processing.getStatus()).thenReturn(EventPublication.Status.PROCESSING);

        assertThat(job.due(processing, PUBLISHED.plus(Duration.ofDays(1)))).isFalse();
    }

    private static EventPublication failed(int attempts, Instant lastResubmission) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(EventPublication.Status.FAILED);
        when(publication.getCompletionAttempts()).thenReturn(attempts);
        when(publication.getPublicationDate()).thenReturn(PUBLISHED);
        when(publication.getLastResubmissionDate()).thenReturn(lastResubmission);
        return publication;
    }
}
