package com.souldealers.crowdtracebackend.modules.notification.internal;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.community.CommentPostedEvent;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.modules.notification.CaseStatusChangedEvent;
import com.souldealers.crowdtracebackend.modules.notification.InAppNotifications;
import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import com.souldealers.crowdtracebackend.shared.EmailDeliveryException;
import com.souldealers.crowdtracebackend.shared.JwtService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CT-029: duplicate events, retries, exhaustion and crash recovery, against real commits and the real registry. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"notification.retry.backoff=PT0S", "notification.retry.max-attempts=3",
        "notification.retry.stuck-after=PT10M"})
class NotificationRetryTest extends CasePostgresTestSupport {
    private static final String EMAILER = "%ReporterStatusEmailer%";
    private static final String FOLLOWERS = "%FollowerNotifier%";

    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate tx;
    @Autowired private NotificationRetryJob job;
    @Autowired private MeterRegistry meters;
    @Autowired private CaseRecordRepository cases;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private InAppNotifications notifications;

    private String reporterEmail;
    private long reporterId;
    private long followerId;
    private CaseRecord liveCase;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM event_publication");
        reporterEmail = email("reporter");
        reporterId = user(reporterEmail);
        followerId = user(email("follower"));
        liveCase = cases.saveAndFlush(aCase(reporterId).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).submittedAt(at(8)).createdAt(at(8))
                .updatedAt(at(8)).build());
        jdbc.update("INSERT INTO case_follows (user_id, case_id) VALUES (?, ?)", followerId, liveCase.getId());
    }

    @Test
    void aRepeatedStatusEventEmailsAndNotifiesOnce() {
        CaseStatusChangedEvent event = statusEvent();

        publish(event);
        verify(emailService, timeout(5_000)).send(eq(reporterEmail), anyString(), anyString(), anyString());
        publish(event);

        verify(emailService, after(500).times(1)).send(anyString(), anyString(), anyString(), anyString());
        assertThat(notificationsFor(followerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporter_status_emails WHERE event_id = ?",
                Integer.class, event.eventId())).isEqualTo(1);
    }

    @Test
    void theEmailCarriesTheEventAsItsProviderIdempotencyKey() {
        CaseStatusChangedEvent event = statusEvent();

        publish(event);

        verify(emailService, timeout(5_000)).send(eq(reporterEmail), anyString(), anyString(),
                eq("reporter-status-email/" + event.eventId()));
    }

    @Test
    void aRepeatedCommentEventNotifiesOnce() {
        long author = user(email("author"));
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments (case_id, author_id, body) VALUES (?, ?, 'Seen near Madina') RETURNING id",
                Long.class, liveCase.getId(), author);
        CommentPostedEvent event = CommentPostedEvent.of(liveCase.getId(), commentId, null, author);

        publish(event);
        publish(event);

        assertThat(notificationsFor(reporterId)).isEqualTo(1);
        assertThat(statuses("%ReplyNotifier%")).as("completed publications are deleted").isEmpty();
    }

    @Test
    void aFailedEmailIsRetriedAndSentOnce(CapturedOutput output) {
        doThrow(new EmailDeliveryException("Email provider returned 503")).doNothing()
                .when(emailService).send(anyString(), anyString(), anyString(), anyString());
        CaseStatusChangedEvent event = statusEvent();

        publish(event);
        awaitStatus(EMAILER, "FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporter_status_emails WHERE event_id = ?",
                Integer.class, event.eventId())).as("a failed send rolls its claim back").isZero();

        job.run();

        verify(emailService, timeout(5_000).times(2)).send(eq(reporterEmail), anyString(), anyString(), anyString());
        awaitGone(EMAILER);
        publish(event);
        verify(emailService, after(500).times(2)).send(anyString(), anyString(), anyString(), anyString());
        assertThat(output.getAll()).doesNotContain(reporterEmail);
    }

    @Test
    void aFailedInAppFanOutLeavesTheRequestAloneAndIsRetried() throws Exception {
        doThrow(new IllegalStateException("database blip")).doCallRealMethod()
                .when(notifications).notifyStatusChanged(anyString(), anyCollection(), anyLong(), any());
        String moderatorEmail = email("moderator");
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", user(moderatorEmail));

        mvc.perform(patch("/api/v1/admin/cases/" + liveCase.getId() + "/status")
                        .header("Authorization", "Bearer " + jwt.generateToken(
                                new SecurityUser(users.findByEmail(moderatorEmail).orElseThrow())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCaseVersion\":" + liveCase.getVersion() + ",\"caseStatus\":\"FOUND_SAFE\"}"))
                .andExpect(status().isOk());
        awaitStatus(FOLLOWERS, "FAILED");
        assertThat(notificationsFor(followerId)).isZero();

        job.run();

        awaitGone(FOLLOWERS);
        assertThat(notificationsFor(followerId)).isEqualTo(1);
    }

    @Test
    void anEmailThatKeepsFailingStopsAtTheCapAndIsCountedWithoutContent(CapturedOutput output) {
        doThrow(new EmailDeliveryException("Email provider returned 503"))
                .when(emailService).send(anyString(), anyString(), anyString(), anyString());

        publish(statusEvent());
        awaitStatus(EMAILER, "FAILED");
        for (int run = 0; run < 4; run++) {
            job.run();
            awaitStatus(EMAILER, "FAILED");
        }

        verify(emailService, after(500).times(3)).send(anyString(), anyString(), anyString(), anyString());
        assertThat(meters.get(NotificationRetryJob.EXHAUSTED_GAUGE).gauge().value()).isEqualTo(1.0);
        String line = output.getAll().lines().filter(l -> l.contains("exhausted retries")).findFirst().orElseThrow();
        assertThat(line).contains("ReporterStatusEmailer").contains("CaseStatusChangedEvent")
                .doesNotContain(reporterEmail);
    }

    @Test
    void aDeliveryStrandedByACrashIsRecovered() {
        doThrow(new EmailDeliveryException("Email provider returned 503")).doNothing()
                .when(emailService).send(anyString(), anyString(), anyString(), anyString());
        publish(statusEvent());
        awaitStatus(EMAILER, "FAILED");
        // As if the app died mid-delivery long ago.
        jdbc.update("UPDATE event_publication SET status = 'RESUBMITTED', last_resubmission_date = now() - interval '1 hour'"
                + " WHERE listener_id LIKE ?", EMAILER);

        job.run();

        verify(emailService, timeout(5_000).times(2)).send(eq(reporterEmail), anyString(), anyString(), anyString());
        awaitGone(EMAILER);
    }

    @Test
    void aSweepWaitsWhileAnotherInstanceHoldsTheLock() throws Exception {
        doThrow(new EmailDeliveryException("Email provider returned 503")).doNothing()
                .when(emailService).send(anyString(), anyString(), anyString(), anyString());
        publish(statusEvent());
        awaitStatus(EMAILER, "FAILED");

        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("SELECT pg_advisory_lock(" + 0x6E7466_7265747279L + ")");
            job.run();
            verify(emailService, after(500).times(1)).send(anyString(), anyString(), anyString(), anyString());
            assertThat(statuses(EMAILER)).containsExactly("FAILED");
        }

        job.run();
        verify(emailService, timeout(5_000).times(2)).send(eq(reporterEmail), anyString(), anyString(), anyString());
    }

    private CaseStatusChangedEvent statusEvent() {
        long auditId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        return CaseStatusChangedEvent.of(auditId, liveCase.getId(), reporterId, NotifiedCaseStatus.MISSING,
                NotifiedCaseStatus.FOUND_SAFE, CaseStatusChangedEvent.ActorKind.ADMIN, LocalDateTime.now());
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }

    private int notificationsFor(long recipient) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND case_id = ?",
                Integer.class, recipient, liveCase.getId());
    }

    private void awaitStatus(String listener, String expected) {
        await(() -> statuses(listener).equals(List.of(expected)), listener + " " + expected);
    }

    private void awaitGone(String listener) {
        await(() -> statuses(listener).isEmpty(), listener + " completed");
    }

    private List<String> statuses(String listener) {
        return jdbc.queryForList("SELECT status FROM event_publication WHERE listener_id LIKE ?", String.class,
                listener);
    }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }

    private static void await(java.util.function.BooleanSupplier condition, String what) {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
