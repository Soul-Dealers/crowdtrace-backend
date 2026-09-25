package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "rate-limit.enabled=true",
        "rate-limit.policies.test-policy.limit=3",
        "rate-limit.policies.test-policy.window=10m",
        "rate-limit.policies.brief-policy.limit=1",
        "rate-limit.policies.brief-policy.window=1s"
})
@ActiveProfiles("test")
@Testcontainers
@Import(PostgresRateLimiterTest.RollbackProbeConfig.class)
class PostgresRateLimiterTest {

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

    /**
     * Stands in for AuthServiceImpl.verifyOtp: a transactional method that charges
     * the limiter and then throws, exactly as a failed OTP guess does.
     */
    @Component
    static class RollbackProbe {

        private final RateLimiter rateLimiter;

        RollbackProbe(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
        }

        @Transactional
        public void chargeThenFail(String subject) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
            throw new IllegalStateException("the request failed, as a wrong OTP does");
        }
    }

    @TestConfiguration
    static class RollbackProbeConfig {
        @Bean
        RollbackProbe rollbackProbe(RateLimiter rateLimiter) {
            return new RollbackProbe(rateLimiter);
        }
    }

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RollbackProbe rollbackProbe;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    private NotificationService notificationService;

    private String subject;

    @BeforeEach
    void freshSubjectPerTest() {
        subject = "user-" + System.nanoTime() + "@example.com";
    }

    @Test
    void theCounterSurvivesARolledBackRequest() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> rollbackProbe.chargeThenFail(subject))
                    .isInstanceOf(IllegalStateException.class);
        }

        // If the increment joined the caller's transaction, all three were rolled
        // back, this call is the first the bucket has seen, and the cap never fires.
        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject).denied())
                .isTrue();
    }

    @Test
    void requestsUpToTheLimitAreAllowed() {
        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject).allowed()).isTrue();
        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject).allowed()).isTrue();
        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject).allowed()).isTrue();
    }

    @Test
    void theRequestAfterTheLimitIsDenied() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        RateLimitDecision decision =
                rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);

        assertThat(decision.denied()).isTrue();
        assertThat(decision.policyName()).isEqualTo("test-policy");
    }

    @Test
    void retryAfterIsNeverZero() {
        rateLimiter.record(RateLimitScope.IDENTITY, "brief-policy", subject);

        RateLimitDecision decision =
                rateLimiter.record(RateLimitScope.IDENTITY, "brief-policy", subject);

        // A bucket denied in the last milliseconds of its window rounds to zero,
        // and Retry-After: 0 tells a client to retry immediately — a hot loop.
        assertThat(decision.denied()).isTrue();
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void differentSubjectsDoNotShareABucket() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", "other@example.com").allowed())
                .isTrue();
    }

    @Test
    void theSameSubjectInADifferentScopeDoesNotShareABucket() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        assertThat(rateLimiter.record(RateLimitScope.IP, "test-policy", subject).allowed()).isTrue();
    }

    @Test
    void peekReportsDenialWithoutCharging() {
        // limit=3, so three requests sit AT the limit and are still allowed.
        // The fourth is the one that crosses it; peek can only report a denial
        // once the bucket is actually over. See requestsUpToTheLimitAreAllowed.
        for (int i = 0; i < 4; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", subject).denied()).isTrue();
        assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", subject).denied()).isTrue();

        // Denial alone cannot prove peek is free: an over-limit bucket reports denied
        // whether or not peek charges it. Prove it on a bucket with headroom, where a
        // charging peek would consume the quota this last record still needs.
        String quiet = "quiet-" + subject;
        rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", quiet);
        rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", quiet);

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", quiet).allowed()).isTrue();
        }

        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", quiet).allowed()).isTrue();
    }

    @Test
    void peekOnAnUntouchedSubjectAllows() {
        assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", subject).allowed()).isTrue();
    }

    @Test
    void resetRefundsTheBucket() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        rateLimiter.reset(RateLimitScope.IDENTITY, "test-policy", subject);

        assertThat(rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject).allowed()).isTrue();
    }

    @Test
    void theSubjectIsNotStoredInPlaintext() {
        rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);

        // The table must not be minable for who uses the service.
        assertThat(rawSubjectKeysAsText()).noneMatch(stored -> stored.contains(subject));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> rawSubjectKeysAsText() {
        return entityManager
                .createNativeQuery("SELECT encode(subject_key, 'escape') FROM rate_limit_bucket")
                .getResultList();
    }
}
