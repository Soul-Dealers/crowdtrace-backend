package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "rate-limit.enabled=true",
        "rate-limit.policies.identity-otp-attempt.limit=3",
        "rate-limit.policies.identity-otp-attempt.window=10m",
        "rate-limit.policies.identity-otp-send.limit=20",
        "rate-limit.policies.identity-otp-send.window=1h",
        "rate-limit.policies.ip-otp-attempt.limit=1000",
        "rate-limit.policies.ip-otp-attempt.window=10m",
        "rate-limit.policies.ip-otp-send.limit=1000",
        "rate-limit.policies.ip-otp-send.window=10m",
        "rate-limit.policies.ip-signup.limit=1000",
        "rate-limit.policies.ip-signup.window=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class OtpAttemptCapTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    private String signUp() throws Exception {
        String email = "otp-" + System.nanoTime() + "@example.com";

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"," 
                                + "\"displayName\":\"Test User\"," 
                                + "\"password\":\"Str0ngPassw0rd!\"}"))
                .andReturn();

        return email;
    }

    private String capturedCodeFor(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeastOnce())
                .sendOtpEmail(eq(email), code.capture(), anyString(), any(OtpType.class));
        return code.getValue();
    }

    private int verifyOtp(String email, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int resendOtp(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"type\":\"CREATE\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void guessesBeyondTheCapAreRejectedWith429() throws Exception {
        String email = signUp();

        for (int i = 0; i < 3; i++) {
            assertThat(verifyOtp(email, "000000")).isNotEqualTo(429);
        }

        assertThat(verifyOtp(email, "000000")).isEqualTo(429);
    }

    @Test
    void theCounterSurvivesTheRolledBackFailedGuesses() throws Exception {
        String email = signUp();

        for (int i = 0; i < 3; i++) {
            verifyOtp(email, "000000");
        }

        assertThat(verifyOtp(email, "000000")).isEqualTo(429);
    }

    @Test
    void resendDoesNotResetTheAttemptCounter() throws Exception {
        String email = signUp();

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        resendOtp(email);

        assertThat(verifyOtp(email, "000000")).isEqualTo(429);
    }

    @Test
    void aBlockedIdentityIsNotSentFreshCodes() throws Exception {
        String email = signUp();

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        assertThat(resendOtp(email)).isEqualTo(429);
    }

    @Test
    void exceedingTheCapBurnsTheOutstandingCode() throws Exception {
        String email = signUp();
        String realCode = capturedCodeFor(email);

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        assertThat(verifyOtp(email, realCode)).isEqualTo(429);
    }

    @Test
    void theCorrectCodeStillWorksBelowTheCap() throws Exception {
        String email = signUp();
        String realCode = capturedCodeFor(email);

        verifyOtp(email, "000000");

        assertThat(verifyOtp(email, realCode)).isEqualTo(200);
    }

    @Test
    void aDeniedAttemptReturnsProblemJsonWithRetryAfter() throws Exception {
        String email = signUp();

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        var response = mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(Integer.parseInt(response.getHeader("Retry-After")))
                .isGreaterThanOrEqualTo(1);
    }
}
