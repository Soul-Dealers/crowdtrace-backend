package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@ActiveProfiles("test")
@Testcontainers
class TokenRevocationRaceTest {

    private static final int CONCURRENCY = 8;

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
    }

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @Transactional
    void nativeUpsertReturnsOneThenZeroForTheSameHash() {
        String tokenHash = "a".repeat(64);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        assertThat(revokedTokenRepository.insertIgnoringConflict(tokenHash, expiresAt))
                .isOne();
        assertThat(revokedTokenRepository.insertIgnoringConflict(tokenHash, expiresAt))
                .isZero();
    }

    @Test
    void concurrentRevocationOfTheSameTokenIsIdempotent() throws Exception {
        String token = "race-token-value";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        long before = revokedTokenRepository.count();

        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        List<Callable<Throwable>> callers = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            callers.add(() -> {
                try {
                    barrier.await();
                    tokenRevocationService.revoke(token, expiresAt);
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });
        }

        List<Future<Throwable>> results;
        try {
            results = pool.invokeAll(callers, 15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        for (Future<Throwable> result : results) {
            assertThat(result.isCancelled())
                    .as("every revocation caller must finish before the timeout")
                    .isFalse();
            assertThat(result.get())
                    .as("no caller may fail when revoking a token another caller already revoked")
                    .isNull();
        }

        assertThat(revokedTokenRepository.count())
                .as("exactly one row must exist for the token")
                .isEqualTo(before + 1);
        assertThat(tokenRevocationService.isRevoked(token)).isTrue();
    }

    @Test
    void revokingSequentiallyTwiceIsAlsoSafe() {
        String token = "sequential-token-value";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        long before = revokedTokenRepository.count();

        tokenRevocationService.revoke(token, expiresAt);
        tokenRevocationService.revoke(token, expiresAt);

        assertThat(revokedTokenRepository.count()).isEqualTo(before + 1);
        assertThat(tokenRevocationService.isRevoked(token)).isTrue();
    }
}
