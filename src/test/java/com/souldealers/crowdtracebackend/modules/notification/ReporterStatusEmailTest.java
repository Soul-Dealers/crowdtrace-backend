package com.souldealers.crowdtracebackend.modules.notification;

import com.souldealers.crowdtracebackend.modules.casefile.CaseStatus;
import com.souldealers.crowdtracebackend.modules.casefile.ReviewStatus;
import com.souldealers.crowdtracebackend.modules.casefile.internal.model.CaseRecord;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CasePostgresTestSupport;
import com.souldealers.crowdtracebackend.modules.casefile.internal.repository.CaseRecordRepository;
import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.EmailDeliveryException;
import com.souldealers.crowdtracebackend.shared.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requests must really commit: the email goes out after commit, on another thread. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReporterStatusEmailTest extends CasePostgresTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CaseRecordRepository cases;

    private String reporterEmail;
    private String moderatorEmail;
    private CaseRecord liveCase;

    @BeforeEach
    void setUp() {
        reporterEmail = email("reporter");
        long reporterId = user(reporterEmail);
        moderatorEmail = email("moderator");
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", user(moderatorEmail));
        liveCase = cases.saveAndFlush(aCase(reporterId).reviewStatus(ReviewStatus.APPROVED)
                .caseStatus(CaseStatus.MISSING).approvedAt(at(9)).submittedAt(at(8)).createdAt(at(8))
                .updatedAt(at(8)).build());
    }

    @Test
    void aModeratorStatusChangeEmailsOnlyTheReporter() throws Exception {
        String followerEmail = email("follower");
        jdbc.update("INSERT INTO case_follows (user_id, case_id) VALUES (?, ?)", user(followerEmail), liveCase.getId());

        adminChange("FOUND_SAFE");

        verify(emailService, timeout(5_000)).send(eq(reporterEmail), anyString(),
                contains("/cases/" + liveCase.getId()));
        verify(emailService, after(300).never()).send(eq(followerEmail), anyString(), anyString());
        verify(emailService, never()).send(eq(moderatorEmail), anyString(), anyString());
    }

    @Test
    void theReportersOwnChangeSendsNoEmail() throws Exception {
        mvc.perform(as(patch("/api/user/cases/" + liveCase.getId() + "/status"), reporterEmail)
                        .content(body("FOUND_SAFE")))
                .andExpect(status().isOk());

        verify(emailService, after(500).never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aSlowProviderDoesNotHoldUpTheRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> release.await(10, TimeUnit.SECONDS))
                .when(emailService).send(anyString(), anyString(), anyString());
        try {
            long started = System.nanoTime();
            adminChange("FOUND_SAFE");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
            verify(emailService, timeout(5_000)).send(eq(reporterEmail), anyString(), anyString());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aProviderFailureIsLoggedWithoutTheAddress(CapturedOutput output) throws Exception {
        doThrow(new EmailDeliveryException("provider returned 503"))
                .when(emailService).send(anyString(), anyString(), anyString());

        adminChange("FOUND_SAFE");

        verify(emailService, timeout(5_000)).send(eq(reporterEmail), anyString(), anyString());
        String line = awaitLine(output, "Reporter status email failed");
        assertThat(line).contains("case-status:").contains("EmailDeliveryException").contains("provider returned 503").doesNotContain(reporterEmail);
    }

    private void adminChange(String target) throws Exception {
        mvc.perform(as(patch("/api/v1/admin/cases/" + liveCase.getId() + "/status"), moderatorEmail)
                        .content(body(target)))
                .andExpect(status().isOk());
    }

    private String body(String target) {
        return "{\"expectedCaseVersion\":" + liveCase.getVersion() + ",\"caseStatus\":\"" + target + "\"}";
    }

    private static String awaitLine(CapturedOutput output, String marker) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            for (String line : output.getAll().split("\n")) {
                if (line.contains(marker)) return line;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No log line containing: " + marker);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header("Authorization", "Bearer " + jwt.generateToken(
                new SecurityUser(users.findByEmail(email).orElseThrow()))).contentType(MediaType.APPLICATION_JSON);
    }

    private static String email(String label) { return label + "-" + UUID.randomUUID() + "@example.com"; }
}
