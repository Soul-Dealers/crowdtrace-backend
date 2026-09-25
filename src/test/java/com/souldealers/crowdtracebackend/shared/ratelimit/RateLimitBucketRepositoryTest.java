package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "cors.allowed-origins=http://localhost",
        "rate-limit.enabled=true"
})
@ActiveProfiles("test")
@Testcontainers
class RateLimitBucketRepositoryTest {

    private static final int CONCURRENCY = 12;
    private static final String SCOPE = "IDENTITY";
    private static final String ACTION = "otp-attempt";

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
    private RateLimitBucketRepository repository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    private NotificationService notificationService;

    private byte[] key;

    @BeforeEach
    void freshKeyPerTest() {
        key = ("subject-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theFirstChargeStartsTheWindowAtOne() {
        RateLimitBucketRepository.BucketState state = repository.charge(SCOPE, ACTION, key, 600);

        assertThat(state.requestCount()).isEqualTo(1);
        assertThat(state.windowEndsAt()).isAfter(Instant.now());
    }

    @Test
    void chargesAccumulateWithinTheWindow() {
        repository.charge(SCOPE, ACTION, key, 600);
        repository.charge(SCOPE, ACTION, key, 600);

        assertThat(repository.charge(SCOPE, ACTION, key, 600).requestCount()).isEqualTo(3);
    }

    @Test
    void anExpiredWindowResetsTheCountToOne() throws InterruptedException {
        repository.charge(SCOPE, ACTION, key, 1);
        repository.charge(SCOPE, ACTION, key, 1);

        TimeUnit.MILLISECONDS.sleep(1_200);

        assertThat(repository.charge(SCOPE, ACTION, key, 1).requestCount()).isEqualTo(1);
    }

    @Test
    void aDeniedRequestDoesNotExtendTheWindow() {
        Instant firstEnd = repository.charge(SCOPE, ACTION, key, 600).windowEndsAt();

        for (int i = 0; i < 20; i++) {
            repository.charge(SCOPE, ACTION, key, 600);
        }

        // Continuing to hammer a blocked bucket must not push the reset further out,
        // or an attacker can hold a victim in permanent lockout.
        assertThat(repository.charge(SCOPE, ACTION, key, 600).windowEndsAt())
                .isEqualTo(firstEnd);
    }

    @Test
    void differentActionsAreIsolated() {
        repository.charge(SCOPE, ACTION, key, 600);
        repository.charge(SCOPE, ACTION, key, 600);

        assertThat(repository.charge(SCOPE, "otp-send", key, 600).requestCount()).isEqualTo(1);
    }

    @Test
    void differentScopesAreIsolated() {
        repository.charge(SCOPE, ACTION, key, 600);

        assertThat(repository.charge("IP", ACTION, key, 600).requestCount()).isEqualTo(1);
    }

    @Test
    void concurrentChargesEachGetADistinctCount() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return repository.charge(SCOPE, ACTION, key, 600).requestCount();
                });
            }

            List<Integer> counts = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                counts.add(future.get(20, TimeUnit.SECONDS));
            }

            // No lost updates: the upsert is atomic, so 12 concurrent callers see
            // 12 distinct counts, not a scramble of repeated values.
            assertThat(counts).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void peekReportsStateWithoutCharging() {
        repository.charge(SCOPE, ACTION, key, 600);

        assertThat(repository.peek(SCOPE, ACTION, key))
                .get()
                .extracting(RateLimitBucketRepository.BucketState::requestCount)
                .isEqualTo(1);

        assertThat(repository.peek(SCOPE, ACTION, key))
                .get()
                .extracting(RateLimitBucketRepository.BucketState::requestCount)
                .isEqualTo(1);
    }

    @Test
    void peekOnAnUnknownSubjectIsEmpty() {
        assertThat(repository.peek(SCOPE, ACTION, key)).isEmpty();
    }

    @Test
    void resetClearsTheBucket() {
        repository.charge(SCOPE, ACTION, key, 600);
        repository.charge(SCOPE, ACTION, key, 600);

        repository.reset(SCOPE, ACTION, key);

        assertThat(repository.charge(SCOPE, ACTION, key, 600).requestCount()).isEqualTo(1);
    }

    @Test
    void purgeRemovesOnlyBucketsPastTheirPurgeTime() {
        byte[] staleKey = ("stale-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        repository.charge(SCOPE, ACTION, staleKey, 600);
        repository.charge(SCOPE, ACTION, key, 600);

        // purge_after is deliberately window + 1 hour, so nothing written by charge()
        // is purgeable yet. Age one row directly rather than sleeping past an hour.
        entityManager.createNativeQuery("""
                        UPDATE rate_limit_bucket SET purge_after = now() - interval '1 minute'
                        WHERE scope = :scope AND action = :action AND subject_key = :subjectKey
                        """)
                .setParameter("scope", SCOPE)
                .setParameter("action", ACTION)
                .setParameter("subjectKey", staleKey)
                .executeUpdate();

        assertThat(repository.purgeExpired()).isEqualTo(1);
        assertThat(repository.peek(SCOPE, ACTION, staleKey)).isEmpty();
        assertThat(repository.peek(SCOPE, ACTION, key)).isPresent();
    }
}
