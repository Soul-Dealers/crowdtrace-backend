package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "rate-limit.enabled=true",
        "rate-limit.policies.identity-otp-send.limit=2",
        "rate-limit.policies.identity-otp-send.window=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RateLimitEnumerationParityTest {

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

    private MvcResult resetRequestFor(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    @Test
    void anUnknownAddressIsThrottledIdenticallyToAKnownOne() throws Exception {
        String absent = "absent-" + System.nanoTime() + "@example.com";

        assertThat(resetRequestFor(absent).getResponse().getStatus()).isEqualTo(200);
        assertThat(resetRequestFor(absent).getResponse().getStatus()).isEqualTo(200);

        MvcResult denied = resetRequestFor(absent);

        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void aMalformedOtpTypeDoesNotDrainTheSendQuota() throws Exception {
        String email = "typed-" + System.nanoTime() + "@example.com";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/resend-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"type\":\"NOT_A_TYPE\"}"));
        }

        assertThat(mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"type\":\"CREATE\"}"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
