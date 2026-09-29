# OTP & Auth Endpoint Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make 6-digit OTP brute force infeasible and cap outbound email abuse on the six `/api/v1/auth` endpoints, correctly across multiple app instances, without adding infrastructure.

**Architecture:** Two layers over one PostgreSQL table. A `HandlerInterceptor` applies a coarse IP+route limit (fail-open); explicit guard calls in `AuthServiceImpl` apply the real identity+action+purpose control (fail-closed). Both go through a `RateLimiter` whose single atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING` runs in `REQUIRES_NEW`, so a rolled-back request cannot erase its own counter. The OTP attempt cap is the same mechanism with one extra side-effect: exceeding it expires the OTP.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Modulith, Spring Data JPA, Flyway, PostgreSQL 16, JUnit 5, AssertJ, MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-25-otp-rate-limiting-design.md`
**Peer review:** `docs/superpowers/plans/2026-09-25-otp-rate-limiting-review.md`

## Global Constraints

- **Java 21** (`pom.xml:30`). Do not use preview features.
- **No new runtime dependencies.** No Redis, no Bucket4j, no Caffeine. Everything uses the existing datasource, Flyway and JPA.
- **Never store raw IPs or emails** in `rate_limit_bucket`. `subject_key` is an HMAC-SHA-256 under `rate-limit.hmac-secret`, which must be **distinct from `otp.hmac-secret`**.
- **Never log a raw email or raw client IP.** Log the policy name and the outcome only.
- **The counter always commits in `Propagation.REQUIRES_NEW`,** and the limiter **returns a decision, never throws**. Throwing inside that transaction marks it rollback-only and undoes the increment it just recorded. Callers throw, outside the boundary.
- **A denied request must never extend `window_ends_at`.** Extending on denial lets an attacker hold a victim in permanent lockout.
- **All timestamps use the database clock** (`now()`), never an app-server clock. New columns are `TIMESTAMPTZ`.
- **`rate-limit.enabled` defaults to `true`** and is set `false` only in `application-test.yaml`. It is a test-profile affordance, not a production kill switch.
- **Migration numbering continues from `V7`.** This plan adds exactly one migration, `V8__create_rate_limit_bucket.sql`.
- **Only `RateLimitExceededException` and `RateLimitUnavailableException` handlers return `ResponseEntity<ProblemDetail>`.** Do not change the existing handlers or the private `problem(...)` helper.
- **`Retry-After` is the only rate-limit response header.** Do not emit `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` or `RateLimit-Policy` — they disclose which control tripped.
- **`shared` must never import from `modules.identity`.** `CrowdtraceModulesTest` must pass after every task.
- Baseline before this plan: **93 tests, 0 failures**. The suite must be green at every commit.

## Review Focus

Five conditions the spec implies but which no task's happy path exercises. Each has a test assigned to the task that owns the code.

1. **A missing `rate-limit.hmac-secret` must fail startup, not silently weaken the control.** `OtpProperties` already sets this precedent; a limiter that boots with a null key and HMACs everything to a constant would collapse every subject into one bucket. → Task 1.
2. **`Retry-After` must never be `0` or negative.** A bucket denied in the final milliseconds of its window rounds to zero, and `Retry-After: 0` tells a client to retry immediately, producing a hot loop. → Task 3.
3. **An IPv6 client must get a stable, distinct bucket.** `getRemoteAddr()` returns forms like `0:0:0:0:0:0:0:1` and `::1` for the same host; if they key differently, the limit is halved for that client. → Task 5.
4. **An unparseable OTP type on `/resend-otp` must not charge a send bucket.** `resolveOtpType` throws `ValidationException` for garbage input; if the guard runs first, an attacker drains a victim's send quota with requests that never send mail. → Task 6.
5. **A user blocked on OTP attempts must not receive fresh codes.** Resend is a separate bucket, so the attempt lockout does not stop it unless explicitly checked — mailing a code that cannot be used is both a bypass shape and a mail-abuse path. → Task 7.

---

## File Structure

**Created — `shared/ratelimit/` (generic mechanism, knows nothing about identity):**

| File | Responsibility |
|---|---|
| `RateLimit.java` | Method annotation naming an IP-layer policy: `@RateLimit("ip-login")` |
| `RateLimitScope.java` | `enum { IP, IDENTITY }` — the `scope` column |
| `RateLimitDecision.java` | `record(boolean allowed, long retryAfterSeconds, String policyName)` |
| `RateLimitProperties.java` | `@ConfigurationProperties("rate-limit")`; `enabled`, HMAC key, policy map; validated at startup |
| `RateLimitBucketRepository.java` | The one native upsert, plus `peek` and `reset` |
| `RateLimiter.java` | Interface: `record`, `peek`, `reset` |
| `PostgresRateLimiter.java` | HMAC keying, `REQUIRES_NEW`, `enabled` short-circuit |
| `ClientAddressResolver.java` | `getRemoteAddr()` + IPv6 canonicalisation. No header parsing, ever |
| `IpRateLimitInterceptor.java` | Reads `@RateLimit`, charges the IP bucket, fail-open |
| `RateLimitWebConfig.java` | Registers the interceptor |
| `RateLimitBucketCleanupJob.java` | `@Scheduled` purge of `purge_after < now()` |

**Created — `shared/` root (beside the existing shared exceptions):**

| File | Responsibility |
|---|---|
| `RateLimitExceededException.java` | → 429, carries `retryAfterSeconds` and `policyName` |
| `RateLimitUnavailableException.java` | → 503 |

**Created — `modules/identity/internal/ratelimit/` (policy + the OTP side-effect):**

| File | Responsibility |
|---|---|
| `IdentityAction.java` | `enum { LOGIN, OTP_SEND, OTP_ATTEMPT }` and its policy name |
| `IdentityRateLimitGuard.java` | Charges identity buckets; fail-closed; throws on denial |
| `OtpAttemptGuard.java` | Attempt cap + burning the OTP + the resend-lockout check |

**Created — migration:** `src/main/resources/db/migration/V8__create_rate_limit_bucket.sql`

**Modified:**

| File | Change |
|---|---|
| `shared/CustomMessages.java` | Two constants |
| `shared/exception/GlobalExceptionHandler.java` | Two handlers returning `ResponseEntity<ProblemDetail>` |
| `modules/identity/AuthController.java` | `@RateLimit` on all six endpoints |
| `modules/identity/internal/service/AuthServiceImpl.java` | Guard calls before every user lookup |
| `src/main/resources/application.yaml` | `rate-limit` block, `server.forward-headers-strategy: none` |
| `src/main/resources/application-test.yaml` | `rate-limit.enabled: false`, test HMAC secret |
| `src/main/resources/application-dev.yaml` / `-prod.yaml` | HMAC secret wiring |

---

## Task 1: Configuration and policy model

Nothing else can be built until the properties exist, and `rate-limit.enabled: false` must land in the test profile **before** Task 5 registers the interceptor, or the existing 93 tests break.

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitScope.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitDecision.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitProperties.java`
- Modify: `src/main/resources/application.yaml`, `application-test.yaml`, `application-dev.yaml`, `application-prod.yaml`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitPropertiesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RateLimitScope.IP` / `.IDENTITY`; `RateLimitDecision(boolean allowed, long retryAfterSeconds, String policyName)` with a derived `denied()`; `RateLimitProperties` exposing `boolean isEnabled()`, `byte[] hmacKey()`, `Policy policy(String name)`, where `Policy` is `record Policy(int limit, Duration window)`.

- [ ] **Step 1: Write the failing test**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    private static final String VALID_SECRET =
            "dGVzdC1yYXRlLWxpbWl0LWhtYWMtc2VjcmV0LWZvci10ZXN0cy0xMjM0";

    private RateLimitProperties configured() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setHmacSecret(VALID_SECRET);
        properties.setPolicies(Map.of(
                "identity-login", new RateLimitProperties.Policy(5, Duration.ofMinutes(5))));
        return properties;
    }

    @Test
    void rateLimitingIsEnabledUnlessExplicitlyDisabled() {
        assertThat(configured().isEnabled()).isTrue();
    }

    @Test
    void missingHmacSecretFailsStartup() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(Map.of(
                "identity-login", new RateLimitProperties.Policy(5, Duration.ofMinutes(5))));

        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate-limit.hmac-secret");
    }

    @Test
    void aShortHmacSecretIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("c2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void aNonBase64HmacSecretIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("not valid base64 !!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void anUnknownPolicyNameFailsFastRatherThanSilentlyAllowing() {
        assertThatThrownBy(() -> configured().policy("does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void aConfiguredPolicyIsReturned() {
        RateLimitProperties.Policy policy = configured().policy("identity-login");

        assertThat(policy.limit()).isEqualTo(5);
        assertThat(policy.window()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theHmacKeyIsDefensivelyCopied() {
        RateLimitProperties properties = configured();

        byte[] first = properties.hmacKey();
        first[0] = (byte) (first[0] + 1);

        assertThat(properties.hmacKey()).isNotEqualTo(first);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitPropertiesTest`
Expected: FAIL — compilation error, `RateLimitProperties` does not exist.

- [ ] **Step 3: Write `RateLimitScope` and `RateLimitDecision`**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

/** Which keying layer a bucket belongs to. Persisted as the {@code scope} column. */
public enum RateLimitScope {
    /** Coarse abuse guard, keyed on client address. Fails open. */
    IP,
    /** The security control, keyed on the normalised identifier. Fails closed. */
    IDENTITY
}
```

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

/**
 * The outcome of charging a bucket.
 *
 * <p>Deliberately a value, not an exception: the limiter runs in its own
 * transaction, and throwing inside it would mark that transaction rollback-only
 * and undo the very increment being recorded. Callers throw, outside the boundary.
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds, String policyName) {

    public static RateLimitDecision allow(String policyName) {
        return new RateLimitDecision(true, 0, policyName);
    }

    public static RateLimitDecision deny(long retryAfterSeconds, String policyName) {
        return new RateLimitDecision(false, retryAfterSeconds, policyName);
    }

    public boolean denied() {
        return !allowed;
    }
}
```

- [ ] **Step 4: Write `RateLimitProperties`**

Modelled directly on `OtpProperties` (`modules/identity/internal/config/OtpProperties.java`), which validates its key in `@PostConstruct` and refuses to boot when it is absent.

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private static final int MIN_KEY_BYTES = 32;

    /**
     * Test-profile affordance only. The test suite runs on H2, which cannot execute
     * the PostgreSQL upsert this limiter is built on, so the whole mechanism is
     * disabled there and the rate-limit tests opt back in against Testcontainers.
     * Never set false in dev or prod.
     */
    private boolean enabled = true;

    private byte[] hmacKey;

    private Map<String, Policy> policies = Map.of();

    /** A named limit. {@code limit} requests per {@code window}. */
    public record Policy(int limit, Duration window) {}

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = Map.copyOf(policies);
    }

    public void setHmacSecret(String encodedSecret) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "rate-limit.hmac-secret must be valid Base64", exception);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "rate-limit.hmac-secret must decode to at least 32 bytes");
        }

        this.hmacKey = decoded.clone();
    }

    @PostConstruct
    void requireConfigured() {
        if (hmacKey == null) {
            throw new IllegalStateException("rate-limit.hmac-secret must be configured");
        }
        if (policies.isEmpty()) {
            throw new IllegalStateException("rate-limit.policies must not be empty");
        }
    }

    public byte[] hmacKey() {
        if (hmacKey == null) {
            throw new IllegalStateException("rate-limit.hmac-secret must be configured");
        }
        return hmacKey.clone();
    }

    /**
     * Fails fast rather than defaulting. A typo in a policy name must not
     * silently turn a security control into "no limit".
     */
    public Policy policy(String name) {
        Policy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalStateException("No rate-limit policy configured named: " + name);
        }
        return policy;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitPropertiesTest`
Expected: PASS — 7 tests.

- [ ] **Step 6: Add the configuration blocks**

Append to `src/main/resources/application.yaml`:

```yaml
# Rate limiting. IP limits are deliberately coarser than identity limits:
# offices, universities and carrier NAT share one address, so a tight IP limit
# locks out a whole building on one user's typos. The identity layer is the
# actual control.
server:
  # Do NOT enable forwarded-header handling until the load balancer and its
  # CIDRs are documented. Trusting X-Forwarded-For without a trusted-proxy
  # boundary lets any client forge a fresh bucket per request.
  forward-headers-strategy: none

rate-limit:
  enabled: true
  hmac-secret: ${RATE_LIMIT_HMAC_SECRET}
  policies:
    ip-login:          { limit: 60, window: 5m }
    ip-signup:         { limit: 30, window: 1h }
    ip-otp-send:       { limit: 20, window: 1h }
    ip-otp-attempt:    { limit: 60, window: 10m }
    identity-login:    { limit: 5,  window: 5m }
    identity-otp-send: { limit: 3,  window: 1h }
    identity-otp-attempt: { limit: 5, window: 10m }
```

Append to `src/main/resources/application-test.yaml`:

```yaml
# H2 cannot execute the PostgreSQL ON CONFLICT upsert the limiter is built on
# (error 42000-240, measured in the CT-007 review), and Flyway is disabled here
# so V8 never creates the table. The rate-limit tests opt back in with
# rate-limit.enabled=true against a Testcontainers PostgreSQL.
rate-limit:
  enabled: false
  hmac-secret: dGVzdC1yYXRlLWxpbWl0LWhtYWMtc2VjcmV0LWZvci1hdXRvbWF0ZWQtdGVzdHM=
```

Append to `src/main/resources/application-dev.yaml`:

```yaml
rate-limit:
  hmac-secret: ${RATE_LIMIT_HMAC_SECRET:ZGV2LXJhdGUtbGltaXQtaG1hYy1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9u}
```

Leave `application-prod.yaml` without a default, matching how `secret-key` and `otp.hmac-secret` are handled there — a missing `RATE_LIMIT_HMAC_SECRET` must fail the boot.

- [ ] **Step 7: Verify the whole suite still passes**

Run: `./mvnw test`
Expected: PASS — 100 tests (93 baseline + 7 new), 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/main/resources/application.yaml \
        src/main/resources/application-test.yaml \
        src/main/resources/application-dev.yaml
git commit -m "feat(ratelimit): add validated rate-limit configuration

Policy limits live in config so they can be tuned without a deploy, and an
unknown policy name throws rather than defaulting to unlimited.

The HMAC secret is validated at startup like otp.hmac-secret: a limiter that
booted with a null key would hash every subject to the same value and collapse
all traffic into one bucket.

rate-limit.enabled is false in the test profile only. H2 cannot execute the
PostgreSQL upsert the limiter is built on, and Flyway is disabled there, so the
rate-limit tests opt back in against Testcontainers.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Migration and the atomic upsert

Proves the mechanism on real PostgreSQL before anything depends on it.

**Files:**
- Create: `src/main/resources/db/migration/V8__create_rate_limit_bucket.sql`
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitBucketRepository.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitBucketRepositoryTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `RateLimitBucketRepository` with
  `BucketState charge(String scope, String action, byte[] subjectKey, long windowSeconds)`,
  `Optional<BucketState> peek(String scope, String action, byte[] subjectKey)`,
  `void reset(String scope, String action, byte[] subjectKey)`,
  `int purgeExpired()`. `BucketState` is `record BucketState(int requestCount, Instant windowEndsAt)`.

- [ ] **Step 1: Write the migration**

```sql
-- Rate limit counters. Deliberately not a JPA entity: the whole mechanism is a
-- single atomic upsert, and an entity would invite read-modify-write access that
-- races under concurrency.
--
-- subject_key is an HMAC of the client address or normalised email, never the
-- raw value, so this table cannot be mined for who uses the service.
CREATE TABLE rate_limit_bucket (
    scope             VARCHAR(16)  NOT NULL,
    action            VARCHAR(64)  NOT NULL,
    subject_key       BYTEA        NOT NULL,
    request_count     INTEGER      NOT NULL,
    window_started_at TIMESTAMPTZ  NOT NULL,
    window_ends_at    TIMESTAMPTZ  NOT NULL,
    purge_after       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, action, subject_key)
);

CREATE INDEX idx_rate_limit_bucket_purge ON rate_limit_bucket (purge_after);
```

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitBucketRepositoryTest`
Expected: FAIL — compilation error, `RateLimitBucketRepository` does not exist.

- [ ] **Step 4: Write the repository**

Note `purge_after` is set one hour past the window end so a bucket survives long enough for `peek` to report a lockout that has only just lapsed.

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The entire rate-limit mechanism, expressed as one atomic statement.
 *
 * <p>Not a JPA entity by design. Counting correctly under concurrency requires
 * the read and the write to be one statement; an entity would invite
 * find-then-save, which loses updates the moment two replicas race.
 */
@Repository
@RequiredArgsConstructor
public class RateLimitBucketRepository {

    private final EntityManager entityManager;

    /** A bucket's state after a charge. */
    public record BucketState(int requestCount, Instant windowEndsAt) {}

    private static final String CHARGE = """
            INSERT INTO rate_limit_bucket AS b
                   (scope, action, subject_key, request_count,
                    window_started_at, window_ends_at, purge_after)
            VALUES (:scope, :action, :subjectKey, 1,
                    now(),
                    now() + make_interval(secs => :windowSeconds),
                    now() + make_interval(secs => :windowSeconds) + interval '1 hour')
            ON CONFLICT (scope, action, subject_key) DO UPDATE SET
                request_count = CASE WHEN b.window_ends_at <= now()
                                     THEN 1
                                     ELSE b.request_count + 1 END,
                window_started_at = CASE WHEN b.window_ends_at <= now()
                                         THEN now()
                                         ELSE b.window_started_at END,
                window_ends_at = CASE WHEN b.window_ends_at <= now()
                                      THEN now() + make_interval(secs => :windowSeconds)
                                      ELSE b.window_ends_at END,
                purge_after = CASE WHEN b.window_ends_at <= now()
                                   THEN now() + make_interval(secs => :windowSeconds)
                                        + interval '1 hour'
                                   ELSE b.purge_after END
            RETURNING request_count, window_ends_at
            """;

    /**
     * Increments the bucket and returns its new state.
     *
     * <p>A denied request still increments, but deliberately does NOT extend
     * {@code window_ends_at} — see the ELSE branches. Extending on denial would let
     * an attacker hold a victim in permanent lockout by continuing to hammer a
     * blocked bucket.
     */
    @Transactional
    public BucketState charge(String scope, String action, byte[] subjectKey, long windowSeconds) {
        Query query = entityManager.createNativeQuery(CHARGE)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .setParameter("windowSeconds", (double) windowSeconds);

        Object[] row = (Object[]) query.getSingleResult();
        return toState(row);
    }

    /** Reads the bucket without charging it. Used to test a lockout without consuming quota. */
    @Transactional(readOnly = true)
    public Optional<BucketState> peek(String scope, String action, byte[] subjectKey) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT request_count, window_ends_at
                        FROM rate_limit_bucket
                        WHERE scope = :scope AND action = :action AND subject_key = :subjectKey
                        """)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(toState(rows.getFirst()));
    }

    /** Clears a bucket. Used to refund a successful login or OTP verification. */
    @Transactional
    public void reset(String scope, String action, byte[] subjectKey) {
        entityManager.createNativeQuery("""
                        DELETE FROM rate_limit_bucket
                        WHERE scope = :scope AND action = :action AND subject_key = :subjectKey
                        """)
                .setParameter("scope", scope)
                .setParameter("action", action)
                .setParameter("subjectKey", subjectKey)
                .executeUpdate();
    }

    @Transactional
    public int purgeExpired() {
        return entityManager.createNativeQuery(
                        "DELETE FROM rate_limit_bucket WHERE purge_after < now()")
                .executeUpdate();
    }

    private BucketState toState(Object[] row) {
        int count = ((Number) row[0]).intValue();
        Instant endsAt = ((Timestamp) row[1]).toInstant();
        return new BucketState(count, endsAt);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitBucketRepositoryTest`
Expected: PASS — 11 tests. First run pulls the `postgres:16-alpine` image.

If `concurrentChargesEachGetADistinctCount` reports duplicate counts, the upsert is not being executed atomically — check that `charge` was not accidentally split into a select and an update.

- [ ] **Step 6: Verify the whole suite still passes**

Run: `./mvnw test`
Expected: PASS — 111 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V8__create_rate_limit_bucket.sql \
        src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitBucketRepository.java \
        src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitBucketRepositoryTest.java
git commit -m "feat(ratelimit): add rate_limit_bucket and its atomic upsert

Counting correctly across replicas needs the read and the write to be one
statement, so this is a native ON CONFLICT DO UPDATE rather than a JPA entity;
an entity would invite find-then-save, which loses updates under concurrency.

A denied request increments but does not extend window_ends_at. Extending on
denial would let an attacker hold a victim in permanent lockout by continuing to
hammer a blocked bucket, so the test pins that behaviour.

Tests run on Testcontainers PostgreSQL because H2 cannot execute ON CONFLICT.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: The limiter — HMAC keying and an independent transaction

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimiter.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/PostgresRateLimiter.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/PostgresRateLimiterTest.java`

**Interfaces:**
- Consumes: `RateLimitProperties`, `RateLimitScope`, `RateLimitDecision` (Task 1); `RateLimitBucketRepository` (Task 2).
- Produces: `RateLimiter` with
  `RateLimitDecision record(RateLimitScope scope, String policyName, String subject)`,
  `RateLimitDecision peek(RateLimitScope scope, String policyName, String subject)`,
  `void reset(RateLimitScope scope, String policyName, String subject)`.
  The bucket's `action` column **is** the policy name, so there is one name to get wrong instead of two.

- [ ] **Step 1: Write the failing test**

The first test is the most important one in this plan. Everything else can be wrong in ways a reviewer notices; this one fails silently in production while looking correct in isolation.

```java
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
        for (int i = 0; i < 3; i++) {
            rateLimiter.record(RateLimitScope.IDENTITY, "test-policy", subject);
        }

        assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", subject).denied()).isTrue();
        assertThat(rateLimiter.peek(RateLimitScope.IDENTITY, "test-policy", subject).denied()).isTrue();
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PostgresRateLimiterTest`
Expected: FAIL — compilation error, `RateLimiter` does not exist.

- [ ] **Step 3: Write the `RateLimiter` interface**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

/**
 * Charges and inspects rate-limit buckets.
 *
 * <p>Every method returns or completes normally; none throws on denial. The
 * implementation runs in its own transaction, and throwing inside it would mark
 * that transaction rollback-only and undo the increment it just recorded.
 * Deciding what a denial means — 429, 503, or proceed — belongs to the caller.
 *
 * <p>Store failures DO propagate as {@link org.springframework.dao.DataAccessException},
 * because fail-open and fail-closed are caller decisions: the IP layer continues,
 * the identity layer refuses.
 */
public interface RateLimiter {

    /** Increments the bucket and reports whether this request is within the limit. */
    RateLimitDecision record(RateLimitScope scope, String policyName, String subject);

    /** Reports the bucket's current state without charging it. */
    RateLimitDecision peek(RateLimitScope scope, String policyName, String subject);

    /** Clears the bucket, refunding everything charged in the current window. */
    void reset(RateLimitScope scope, String policyName, String subject);
}
```

- [ ] **Step 4: Write `PostgresRateLimiter`**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PostgresRateLimiter implements RateLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final RateLimitBucketRepository repository;
    private final RateLimitProperties properties;

    /**
     * REQUIRES_NEW is load-bearing, not stylistic. AuthServiceImpl.verifyOtp,
     * resetPassword and signUp are @Transactional, and a failed OTP guess throws,
     * rolling that transaction back. If the increment joined it, every failed
     * attempt would erase its own evidence and the cap would never fire.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RateLimitDecision record(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow(policyName);
        }

        RateLimitProperties.Policy policy = properties.policy(policyName);

        RateLimitBucketRepository.BucketState state = repository.charge(
                scope.name(), policyName, subjectKey(subject), policy.window().toSeconds());

        return decide(state.requestCount(), state.windowEndsAt(), policy, policyName);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RateLimitDecision peek(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow(policyName);
        }

        RateLimitProperties.Policy policy = properties.policy(policyName);

        Optional<RateLimitBucketRepository.BucketState> state =
                repository.peek(scope.name(), policyName, subjectKey(subject));

        return state
                .map(found -> decide(found.requestCount(), found.windowEndsAt(), policy, policyName))
                .orElseGet(() -> RateLimitDecision.allow(policyName));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(RateLimitScope scope, String policyName, String subject) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.reset(scope.name(), policyName, subjectKey(subject));
    }

    private RateLimitDecision decide(
            int count, Instant windowEndsAt, RateLimitProperties.Policy policy, String policyName) {

        if (count <= policy.limit()) {
            return RateLimitDecision.allow(policyName);
        }

        // An expired window that peek observed before the next charge resets it
        // would otherwise yield a negative delta.
        long seconds = Duration.between(Instant.now(), windowEndsAt).toSeconds();
        return RateLimitDecision.deny(Math.max(1, seconds + 1), policyName);
    }

    /**
     * Buckets are keyed by HMAC, never by the raw address or email, so the table
     * cannot be mined for who uses the service. The key is deliberately separate
     * from otp.hmac-secret: rotating one must not affect the other.
     */
    private byte[] subjectKey(String subject) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.hmacKey(), HMAC_ALGORITHM));
            return mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Rate-limit keying is unavailable", exception);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PostgresRateLimiterTest`
Expected: PASS — 11 tests.

If `theCounterSurvivesARolledBackRequest` fails, `REQUIRES_NEW` is missing or the limiter is being called through `this` rather than through the proxy.

- [ ] **Step 6: Verify the whole suite still passes**

Run: `./mvnw test`
Expected: PASS — 122 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimiter.java \
        src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit/PostgresRateLimiter.java \
        src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/PostgresRateLimiterTest.java
git commit -m "feat(ratelimit): add the limiter with independent-transaction counting

The counter runs in REQUIRES_NEW because verifyOtp, resetPassword and signUp are
@Transactional and a failed OTP guess throws. Joining the caller's transaction
would let every failed attempt roll back its own increment, so the cap would
never fire while unit tests still passed. A test drives that case directly.

The limiter returns a decision rather than throwing, for the same reason:
throwing inside its own transaction would mark it rollback-only and undo the
increment just recorded.

Subjects are keyed by HMAC under a secret distinct from otp.hmac-secret, and
Retry-After is clamped to at least one second so a bucket denied at the end of
its window cannot tell a client to retry immediately.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Exceptions and the HTTP contract

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/RateLimitExceededException.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/RateLimitUnavailableException.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/exception/RateLimitExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `RateLimitDecision` (Task 1).
- Produces: `RateLimitExceededException(RateLimitDecision)` with `long retryAfterSeconds()` and `String policyName()`; `RateLimitUnavailableException(String message)`; constants `RATE_LIMIT_EXCEEDED_MSG` and `RATE_LIMIT_UNAVAILABLE_MSG`.

- [ ] **Step 1: Write the failing test**

```java
package com.souldealers.crowdtracebackend.shared.exception;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/reset-password");
        request.setMethod("POST");
        return request;
    }

    @Test
    void anExceededLimitBecomes429WithRetryAfter() {
        RateLimitDecision decision = RateLimitDecision.deny(42, "identity-otp-attempt");

        ResponseEntity<ProblemDetail> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(decision), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
    }

    @Test
    void the429BodyIsAProblemDetailWithAStableCode() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(RateLimitDecision.deny(42, "identity-otp-attempt")),
                request());

        ProblemDetail body = response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("code", "RATE_LIMIT_EXCEEDED");
        assertThat(body.getTitle()).isEqualTo("Too many requests");
        assertThat(body.getType().toString())
                .isEqualTo("urn:crowdtrace:problem:rate-limit-exceeded");
    }

    @Test
    void the429RevealsNeitherThePolicyNorItsNumbers() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException(RateLimitDecision.deny(42, "identity-otp-attempt")),
                request());

        // RateLimit-Policy: 5;w=600 is the attempt cap and 3;w=3600 the send bucket,
        // so publishing them tells an attacker which control tripped.
        assertThat(response.getHeaders().headerNames())
                .noneMatch(name -> name.toLowerCase().startsWith("ratelimit-"));
        assertThat(response.getBody().getDetail()).doesNotContain("identity-otp-attempt");
    }

    @Test
    void anUnavailableStoreBecomes503NotA429() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitUnavailable(
                new RateLimitUnavailableException("store unreachable"), request());

        // The client did nothing wrong; 429 would be a lie that also trains
        // clients to back off for the wrong reason.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "RATE_LIMIT_UNAVAILABLE");
    }

    @Test
    void neitherResponseEchoesTheUnderlyingCause() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimitUnavailable(
                new RateLimitUnavailableException("Connection refused to db-primary:5432"), request());

        assertThat(response.getBody().getDetail()).doesNotContain("db-primary");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitExceptionHandlerTest`
Expected: FAIL — compilation error, `RateLimitExceededException` does not exist.

- [ ] **Step 3: Write the exceptions**

```java
package com.souldealers.crowdtracebackend.shared;

import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;

/** Thrown when a caller exceeded a configured limit. Rendered as 429. */
public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(CustomMessages.RATE_LIMIT_EXCEEDED_MSG);
        this.decision = decision;
    }

    public long retryAfterSeconds() {
        return decision.retryAfterSeconds();
    }

    /** Safe to log, never to return: it identifies which control tripped. */
    public String policyName() {
        return decision.policyName();
    }
}
```

```java
package com.souldealers.crowdtracebackend.shared;

/**
 * Thrown when a fail-closed limiter cannot reach its store. Rendered as 503,
 * deliberately not 429 — the caller did nothing wrong.
 */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String message) {
        super(message);
    }

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Add the message constants**

Add to `CustomMessages`, after `LOGOUT_SUCCESS_MSG`:

```java
    public static final String RATE_LIMIT_EXCEEDED_MSG = "Too many requests, try again later";
    public static final String RATE_LIMIT_UNAVAILABLE_MSG =
            "Service temporarily unavailable, try again shortly";
```

- [ ] **Step 5: Add the two handlers**

Add to `GlobalExceptionHandler`, before the private `problem(...)` helper. These are the only handlers in the class that return `ResponseEntity`; leave the others exactly as they are.

```java
    /**
     * The only two handlers here that return ResponseEntity. A bare ProblemDetail
     * cannot carry headers, and a 429 without Retry-After is the most common
     * mistake in rate-limit implementations.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request) {

        // The policy name is safe in logs and must never reach the response.
        log.warn("Rate limit exceeded on {} {} (policy={}, retryAfter={}s)",
                request.getMethod(), request.getRequestURI(),
                exception.policyName(), exception.retryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many requests",
                        RATE_LIMIT_EXCEEDED_MSG,
                        "RATE_LIMIT_EXCEEDED",
                        request));
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitUnavailable(
            RateLimitUnavailableException exception,
            HttpServletRequest request) {

        log.error("Rate limit store unavailable on {} {} (exceptionType={})",
                request.getMethod(), request.getRequestURI(),
                exception.getClass().getName());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service unavailable",
                        RATE_LIMIT_UNAVAILABLE_MSG,
                        "RATE_LIMIT_UNAVAILABLE",
                        request));
    }
```

Add the imports `org.springframework.http.HttpHeaders`, `org.springframework.http.ResponseEntity`,
`com.souldealers.crowdtracebackend.shared.RateLimitExceededException` and
`com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitExceptionHandlerTest`
Expected: PASS — 5 tests.

- [ ] **Step 7: Verify the whole suite still passes**

Run: `./mvnw test`
Expected: PASS — 127 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/RateLimitExceededException.java \
        src/main/java/com/souldealers/crowdtracebackend/shared/RateLimitUnavailableException.java \
        src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java \
        src/main/java/com/souldealers/crowdtracebackend/shared/exception/GlobalExceptionHandler.java \
        src/test/java/com/souldealers/crowdtracebackend/shared/exception/RateLimitExceptionHandlerTest.java
git commit -m "feat(ratelimit): render 429 and 503 as ProblemDetail with Retry-After

A bare ProblemDetail cannot carry response headers, so these two handlers return
ResponseEntity while the rest of the class is unchanged. A 429 without
Retry-After is the usual omission.

No RateLimit-* headers: RateLimit-Policy 5;w=600 is the attempt cap and 3;w=3600
the send bucket, so emitting them would tell an attacker which control tripped
and contradict the deliberately generic body.

A store outage returns 503 rather than 429 because the caller did nothing wrong.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: The IP layer — annotation, address resolution, interceptor

This is the task that would have broken the existing suite. Task 1 already set `rate-limit.enabled: false` in the test profile; confirm that before starting.

**Files:**
- Create: `shared/ratelimit/RateLimit.java`, `ClientAddressResolver.java`, `IpRateLimitInterceptor.java`, `RateLimitWebConfig.java`
- Modify: `modules/identity/AuthController.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/ClientAddressResolverTest.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthEndpointRateLimitAnnotationTest.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/IpRateLimitInterceptorTest.java`

**Interfaces:**
- Consumes: `RateLimiter`, `RateLimitScope`, `RateLimitDecision` (Tasks 1, 3); `RateLimitExceededException` (Task 4).
- Produces: `@RateLimit(String value)` naming a policy; `ClientAddressResolver.resolve(HttpServletRequest)` returning a canonical address string.

- [ ] **Step 1: Write the failing tests**

`ClientAddressResolverTest`:

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {

    private final ClientAddressResolver resolver = new ClientAddressResolver();

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void theSocketAddressIsUsed() {
        assertThat(resolver.resolve(requestFrom("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void forwardedHeadersAreIgnoredEntirely() {
        MockHttpServletRequest request = requestFrom("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Real-IP", "198.51.100.2");
        request.addHeader("Forwarded", "for=198.51.100.3");

        // Trusting these without a trusted-proxy boundary lets any client forge a
        // fresh bucket per request, which is worse than no limit because it looks
        // like one. Forwarding is configured at the container, not parsed here.
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void equivalentIpv6FormsCanonicaliseToOneBucket() {
        String shortForm = resolver.resolve(requestFrom("::1"));
        String longForm = resolver.resolve(requestFrom("0:0:0:0:0:0:0:1"));

        // Otherwise one IPv6 client silently gets two buckets and twice the limit.
        assertThat(shortForm).isEqualTo(longForm);
    }

    @Test
    void distinctIpv6AddressesStayDistinct() {
        assertThat(resolver.resolve(requestFrom("2001:db8::1")))
                .isNotEqualTo(resolver.resolve(requestFrom("2001:db8::2")));
    }

    @Test
    void anUnparseableAddressFallsBackToItsRawForm() {
        assertThat(resolver.resolve(requestFrom("unknown"))).isEqualTo("unknown");
    }

    @Test
    void aMissingAddressYieldsAStablePlaceholder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }
}
```

`AuthEndpointRateLimitAnnotationTest` — the omission guard. Explicit guards are readable but silently skippable; this makes forgetting one a build failure.

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthEndpointRateLimitAnnotationTest {

    @Test
    void everyStateChangingAuthEndpointCarriesARateLimit() {
        List<Method> unprotected = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .filter(method -> !method.isAnnotationPresent(RateLimit.class))
                .toList();

        assertThat(unprotected)
                .as("every POST on AuthController must name an IP rate-limit policy")
                .isEmpty();
    }

    @Test
    void allSixAuthEndpointsAreCovered() {
        long annotated = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RateLimit.class))
                .count();

        assertThat(annotated).isEqualTo(6);
    }
}
```

`IpRateLimitInterceptorTest` — fail-open behaviour, in isolation:

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IpRateLimitInterceptorTest {

    static class AnnotatedHandler {
        @RateLimit("ip-login")
        public void limited() {}

        public void unlimited() {}
    }

    private HandlerMethod handlerFor(String methodName) throws Exception {
        Method method = AnnotatedHandler.class.getMethod(methodName);
        return new HandlerMethod(new AnnotatedHandler(), method);
    }

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final IpRateLimitInterceptor interceptor =
            new IpRateLimitInterceptor(rateLimiter, new ClientAddressResolver());

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void anUnannotatedHandlerIsNotCharged() throws Exception {
        interceptor.preHandle(request(), new MockHttpServletResponse(), handlerFor("unlimited"));

        verify(rateLimiter, never()).record(any(), anyString(), anyString());
    }

    @Test
    void aNonHandlerMethodIsNotCharged() throws Exception {
        assertThat(interceptor.preHandle(
                request(), new MockHttpServletResponse(), new Object())).isTrue();

        verify(rateLimiter, never()).record(any(), anyString(), anyString());
    }

    @Test
    void anAllowedRequestProceeds() throws Exception {
        when(rateLimiter.record(RateLimitScope.IP, "ip-login", "203.0.113.7"))
                .thenReturn(RateLimitDecision.allow("ip-login"));

        assertThat(interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited"))).isTrue();
    }

    @Test
    void aDeniedRequestThrows() throws Exception {
        when(rateLimiter.record(RateLimitScope.IP, "ip-login", "203.0.113.7"))
                .thenReturn(RateLimitDecision.deny(30, "ip-login"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void anUnreachableStoreLetsTheRequestThrough() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        // This layer is a coarse guard, not the control. A limiter outage must not
        // take authentication down for everyone; the identity layer fails closed.
        assertThatCode(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .doesNotThrowAnyException();
    }

    @Test
    void aProgrammingErrorIsNotSwallowed() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("No rate-limit policy configured named: typo"));

        // A blanket catch would disable the limiter for the lifetime of a bug.
        assertThatThrownBy(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handlerFor("limited")))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='ClientAddressResolverTest,AuthEndpointRateLimitAnnotationTest,IpRateLimitInterceptorTest'`
Expected: FAIL — compilation errors, none of these classes exist.

- [ ] **Step 3: Write the annotation and the address resolver**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies the coarse IP-layer limit to a controller method.
 *
 * <p>Carries a policy NAME, not numbers: the limits live in {@code rate-limit.policies}
 * so they can be tuned without a deploy, while the endpoint still shows at a glance
 * that it is protected and by which policy.
 *
 * <p>This is only the coarse guard. The real per-account control lives in the
 * service layer, because the identifier is in the request body and an interceptor
 * cannot read it without consuming the input stream.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** A key in {@code rate-limit.policies}, e.g. {@code "ip-login"}. */
    String value();
}
```

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the client address for IP-layer keying.
 *
 * <p>Deliberately reads only the socket address. Forwarding headers are handled —
 * or, by default, refused — by {@code server.forward-headers-strategy}, which
 * enforces a trusted-proxy boundary. Parsing X-Forwarded-For here would let any
 * client send a different value per request and receive a fresh bucket every time.
 */
@Component
public class ClientAddressResolver {

    private static final String UNKNOWN = "unknown";

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();

        if (remoteAddress == null || remoteAddress.isBlank()) {
            return UNKNOWN;
        }

        return canonicalise(remoteAddress);
    }

    /**
     * "::1" and "0:0:0:0:0:0:0:1" are the same host. Left alone they would key to
     * two buckets, silently doubling that client's allowance.
     */
    private String canonicalise(String address) {
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            return address;
        }
    }
}
```

- [ ] **Step 4: Write the interceptor and its registration**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The coarse abuse guard. Fails OPEN: a limiter outage must not take
 * authentication down for everyone. The identity layer, which is the actual
 * security control, fails closed instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ClientAddressResolver clientAddressResolver;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        String clientAddress = clientAddressResolver.resolve(request);

        final RateLimitDecision decision;
        try {
            decision = rateLimiter.record(RateLimitScope.IP, rateLimit.value(), clientAddress);
        } catch (DataAccessException exception) {
            // Only store-connectivity failures are tolerated. A missing table or a
            // bad query is a programming error and must surface as 500 rather than
            // silently disabling the limiter for the lifetime of the bug.
            log.warn("IP rate limit unavailable, allowing request (policy={}, exceptionType={})",
                    rateLimit.value(), exception.getClass().getName());
            return true;
        }

        if (decision.denied()) {
            throw new RateLimitExceededException(decision);
        }

        return true;
    }
}
```

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final IpRateLimitInterceptor ipRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ipRateLimitInterceptor).addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 5: Annotate the six endpoints**

In `AuthController`, add `import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimit;` and one annotation per POST. Leave `getUsers` unannotated — it is a `@GetMapping` behind `@RequiresSuperAdmin`, and the coverage test only requires POSTs.

```java
    @PostMapping("/signup")
    @RateLimit("ip-signup")
    public ApiResponse<GenericResponseMessage> signUpUser(@Valid @RequestBody SignUpRequest request){

    @PostMapping("/login")
    @RateLimit("ip-login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request){

    @PostMapping("/verify-otp")
    @RateLimit("ip-otp-attempt")
    public ApiResponse<GenericResponseMessage> verifyOtp(@Valid @RequestBody VerifyOtpDto request) {

    @PostMapping("/resend-otp")
    @RateLimit("ip-otp-send")
    public GenericResponseMessage resendOtp (@Valid @RequestBody ResendOtpRequest request){

    @PostMapping("/request-password-reset")
    @RateLimit("ip-otp-send")
    public GenericResponseMessage requestPasswordReset(@Valid @RequestBody PasswordResetRequest request){

    @PostMapping("/reset-password")
    @RateLimit("ip-otp-attempt")
    public GenericResponseMessage resetPassword(@Valid @RequestBody PasswordReset request){
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ClientAddressResolverTest,AuthEndpointRateLimitAnnotationTest,IpRateLimitInterceptorTest'`
Expected: PASS — 14 tests.

- [ ] **Step 7: Verify the existing suite is unaffected**

Run: `./mvnw test`
Expected: PASS — 141 tests, 0 failures.

This is the checkpoint for the H2 problem. If auth tests fail with *relation "rate_limit_bucket" does not exist* or SQL error `42000-240`, `rate-limit.enabled: false` did not reach `application-test.yaml` — fix Task 1's config rather than weakening the limiter.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java \
        src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthEndpointRateLimitAnnotationTest.java
git commit -m "feat(ratelimit): add the IP layer interceptor and @RateLimit

Keeps the annotation shape of the fine-dine implementation this is ported from,
but the annotation names a policy rather than carrying numbers, so limits stay
tunable in config while the endpoint still shows it is protected.

ClientAddressResolver reads only the socket address. The original parsed
X-Forwarded-For behind an IPv4 regex, which establishes no trusted source and
lets any client forge a fresh bucket per request; forwarding is now a container
concern via server.forward-headers-strategy, defaulting to none. IPv6 forms are
canonicalised so ::1 and 0:0:0:0:0:0:0:1 share one bucket.

The layer fails open on store-connectivity failures only, so a limiter outage
cannot take authentication down, while a programming error still surfaces as 500.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: The identity layer — guards before every user lookup

**Files:**
- Create: `modules/identity/internal/ratelimit/IdentityAction.java`, `IdentityRateLimitGuard.java`
- Modify: `modules/identity/internal/service/AuthServiceImpl.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit/IdentityRateLimitGuardTest.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/RateLimitEnumerationParityTest.java`

**Interfaces:**
- Consumes: `RateLimiter`, `RateLimitScope`, `RateLimitDecision` (Tasks 1, 3); `RateLimitExceededException`, `RateLimitUnavailableException` (Task 4); `OtpType`.
- Produces: `IdentityAction` (`LOGIN`, `OTP_SEND`, `OTP_ATTEMPT`, each with `policyName()`); `IdentityRateLimitGuard` with `void check(IdentityAction, String email)`, `void check(IdentityAction, OtpType, String email)`, `void refund(IdentityAction, String email)`, `void refund(IdentityAction, OtpType, String email)`, `boolean isBlocked(IdentityAction, OtpType, String email)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitScope;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityRateLimitGuardTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final IdentityRateLimitGuard guard = new IdentityRateLimitGuard(rateLimiter);

    @Test
    void anAllowedActionProceeds() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.allow("identity-login"));

        assertThatCode(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void aDeniedActionThrows() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.deny(30, "identity-login"));

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void theSubjectSeparatesOtpPurposes() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenReturn(RateLimitDecision.allow("identity-otp-send"));

        guard.check(IdentityAction.OTP_SEND, OtpType.RESET, "user@example.com");

        // A signup send and a reset send must not drain each other's quota.
        verify(rateLimiter).record(
                RateLimitScope.IDENTITY, "identity-otp-send", "user@example.com|RESET");
    }

    @Test
    void anUnreachableStoreRefusesRatherThanProceeding() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        // This layer IS the control preventing takeover and mail abuse. Proceeding
        // without it is proceeding unprotected, so it fails closed with 503.
        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void aProgrammingErrorIsNotConvertedTo503() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("No rate-limit policy configured named: typo"));

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundClearsTheLoginBucket() {
        guard.refund(IdentityAction.LOGIN, "user@example.com");

        verify(rateLimiter).reset(
                RateLimitScope.IDENTITY, "identity-login", "user@example.com");
    }

    @Test
    void aRefundFailureNeverBreaksASuccessfulRequest() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("store down"))
                .when(rateLimiter).reset(any(), anyString(), anyString());

        // The user authenticated successfully; failing their request because the
        // refund could not be written would be strictly worse than over-counting.
        assertThatCode(() -> guard.refund(IdentityAction.LOGIN, "user@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void isBlockedUsesPeekSoItDoesNotConsumeQuota() {
        when(rateLimiter.peek(eq(RateLimitScope.IDENTITY), eq("identity-otp-attempt"), anyString()))
                .thenReturn(RateLimitDecision.deny(30, "identity-otp-attempt"));

        assertThatCode(() -> guard.isBlocked(
                IdentityAction.OTP_ATTEMPT, OtpType.CREATE, "user@example.com"))
                .doesNotThrowAnyException();

        verify(rateLimiter).peek(
                RateLimitScope.IDENTITY, "identity-otp-attempt", "user@example.com|CREATE");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=IdentityRateLimitGuardTest`
Expected: FAIL — compilation error, `IdentityAction` does not exist.

- [ ] **Step 3: Write `IdentityAction` and the guard**

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

/** The identity-keyed controls, each mapped to a policy in {@code rate-limit.policies}. */
public enum IdentityAction {

    /** Failed authentication attempts against one address. */
    LOGIN("identity-login"),

    /** Outbound OTP emails for one address and purpose. Never refunded. */
    OTP_SEND("identity-otp-send"),

    /** OTP guesses for one address and purpose. */
    OTP_ATTEMPT("identity-otp-attempt");

    private final String policyName;

    IdentityAction(String policyName) {
        this.policyName = policyName;
    }

    public String policyName() {
        return policyName;
    }
}
```

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitDecision;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimitScope;
import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The identity-keyed control — the one that actually prevents account takeover
 * and mail abuse. Fails CLOSED: if the store cannot be reached, the request is
 * refused with 503 rather than proceeding unprotected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityRateLimitGuard {

    private final RateLimiter rateLimiter;

    /** Charges an action with no purpose dimension. Throws 429 when over the limit. */
    public void check(IdentityAction action, String email) {
        enforce(action, subject(email, null));
    }

    /** Charges an action per OTP purpose, so a signup send cannot drain a reset quota. */
    public void check(IdentityAction action, OtpType purpose, String email) {
        enforce(action, subject(email, purpose));
    }

    public void refund(IdentityAction action, String email) {
        release(action, subject(email, null));
    }

    public void refund(IdentityAction action, OtpType purpose, String email) {
        release(action, subject(email, purpose));
    }

    /** Reads the bucket without charging it, so a denied request costs no quota. */
    public boolean isBlocked(IdentityAction action, OtpType purpose, String email) {
        try {
            return rateLimiter
                    .peek(RateLimitScope.IDENTITY, action.policyName(), subject(email, purpose))
                    .denied();
        } catch (DataAccessException exception) {
            throw unavailable(action, exception);
        }
    }

    private void enforce(IdentityAction action, String subject) {
        final RateLimitDecision decision;
        try {
            decision = rateLimiter.record(RateLimitScope.IDENTITY, action.policyName(), subject);
        } catch (DataAccessException exception) {
            throw unavailable(action, exception);
        }

        if (decision.denied()) {
            throw new RateLimitExceededException(decision);
        }
    }

    /**
     * A refund failure is swallowed deliberately. The caller already succeeded;
     * failing their request because the counter could not be cleared would be
     * strictly worse than leaving them over-counted until the window lapses.
     */
    private void release(IdentityAction action, String subject) {
        try {
            rateLimiter.reset(RateLimitScope.IDENTITY, action.policyName(), subject);
        } catch (DataAccessException exception) {
            log.warn("Could not refund rate limit (policy={}, exceptionType={})",
                    action.policyName(), exception.getClass().getName());
        }
    }

    private RateLimitUnavailableException unavailable(
            IdentityAction action, DataAccessException exception) {

        log.error("Identity rate limit unavailable, refusing request "
                        + "(policy={}, exceptionType={})",
                action.policyName(), exception.getClass().getName());

        return new RateLimitUnavailableException(
                "Rate limit store unavailable for " + action.policyName(), exception);
    }

    /** Never log this value: it is the raw email. */
    private String subject(String email, OtpType purpose) {
        return purpose == null ? email : email + "|" + purpose.name();
    }
}
```

- [ ] **Step 4: Wire the guards into `AuthServiceImpl`**

Add the field, beside the existing collaborators:

```java
    private final IdentityRateLimitGuard identityRateLimitGuard;
```

and the imports for `IdentityRateLimitGuard` and `IdentityAction`.

**`signUp`** — charge before the lookup:

```java
    @Override
    @Transactional
    public GenericResponseMessage signUp(SignUpRequest request) {

        String email = normalizeEmail(request.email());

        // Charged before the lookup so an unregistered address accumulates
        // identical state; charging inside the branch below would make 429 an
        // account-existence oracle.
        identityRateLimitGuard.check(IdentityAction.OTP_SEND, OtpType.CREATE, email);

        Optional<User> existingUser = userRepository.findByEmail(email);
```

**`login`** — charge up front, refund on success:

```java
    @Override
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        // Charged before authenticating, keeping it a single atomic statement with
        // no peek-then-write race. A successful login refunds it below: access
        // tokens live 15 minutes with no refresh path, so counting successes would
        // spend the budget on ordinary re-authentication.
        identityRateLimitGuard.check(IdentityAction.LOGIN, email);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        SecurityUser principal = (SecurityUser) authentication.getPrincipal();
        User user = principal.user();

        identityRateLimitGuard.refund(IdentityAction.LOGIN, email);

        return LoginResponse.builder()
```

**`resendOtp`** — resolve the type *before* charging:

```java
    @Override
    public GenericResponseMessage resendOtp(ResendOtpRequest request) {
        String email = normalizeEmail(request.email());

        // Resolved first, and deliberately so: resolveOtpType throws on garbage
        // input, and charging before it would let an attacker drain a victim's
        // send quota with requests that never send mail.
        OtpType type = resolveOtpType(request.type());

        identityRateLimitGuard.check(IdentityAction.OTP_SEND, type, email);

        userRepository.findByEmail(email)
                .ifPresent(user -> generateAndSendOtp(user, type));

        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }
```

**`resetPasswordRequest`** — charge before the lookup:

```java
    @Override
    public GenericResponseMessage resetPasswordRequest(PasswordResetRequest request) {
        String email = normalizeEmail(request.email());

        identityRateLimitGuard.check(IdentityAction.OTP_SEND, OtpType.RESET, email);

        Optional<User> byEmail = userRepository.findByEmail(email);

        byEmail.ifPresent(user -> generateAndSendOtp(user, OtpType.RESET));
        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }
```

Leave `verifyOtp` and `resetPassword` untouched — Task 7 owns them.

- [ ] **Step 5: Write the enumeration parity test**

```java
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
import org.springframework.test.web.servlet.autoconfigure.AutoConfigureMockMvc;
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

        // If the guard sat inside .ifPresent(...), an unknown address would never
        // charge a bucket and would never see 429 — a clean existence oracle.
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

        // resolveOtpType runs first, so these never reached the bucket.
        assertThat(mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"type\":\"CREATE\"}"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='IdentityRateLimitGuardTest,RateLimitEnumerationParityTest'`
Expected: PASS — 10 tests.

- [ ] **Step 7: Verify the whole suite still passes**

Run: `./mvnw test && ./mvnw test -Dtest=CrowdtraceModulesTest`
Expected: PASS — 151 tests, 0 failures, and module verification green.

If `CrowdtraceModulesTest` fails, `shared` has acquired a dependency on `modules.identity`. The guard lives in identity and depends on shared; never the reverse.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/RateLimitEnumerationParityTest.java
git commit -m "feat(identity): throttle login and OTP sends per account

The IP layer alone does not stop this attack: it targets one account, and an
attacker rotating addresses walks straight through IP-only keying. These guards
key on the normalised email, reusing the same canonical form authentication uses.

Every guard is charged BEFORE the user lookup. Placing it inside the existing
ifPresent branches would mean unknown addresses never charge a bucket and never
see 429, turning the status code into an account-existence oracle that
AccountEnumerationTest exists to prevent.

Send buckets are keyed by purpose, so alternating /signup and /resend-otp cannot
double the allowance, and resolveOtpType runs before the charge so a malformed
type cannot drain a victim's quota.

A successful login refunds its charge: access tokens live 15 minutes with no
refresh path, so counting successes would spend the budget on re-authentication.

This layer fails closed with 503 -- it is the control, and proceeding without it
is proceeding unprotected.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: The OTP attempt cap and the resend bypass

The security payload of this plan. Everything before it throttles requests; this is what makes a 6-digit code unguessable.

**Files:**
- Create: `modules/identity/internal/ratelimit/OtpAttemptGuard.java`
- Modify: `modules/identity/internal/repository/OtpRepository.java`
- Modify: `modules/identity/internal/service/AuthServiceImpl.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/OtpAttemptCapTest.java`

**Interfaces:**
- Consumes: `IdentityRateLimitGuard`, `IdentityAction` (Task 6); `OtpRepository`, `OtpType`.
- Produces: `OtpAttemptGuard` with `void beforeAttempt(OtpType, String email)`, `void afterSuccess(OtpType, String email)`, `void requireNotBlocked(OtpType, String email)`; `OtpRepository.expireActive(String email, OtpType type, LocalDateTime now)`.

- [ ] **Step 1: Write the failing test**

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.autoconfigure.AutoConfigureMockMvc;
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
        "rate-limit.policies.ip-otp-send.window=1h",
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

        // Each wrong guess throws ValidationException, rolling back verifyOtp's
        // transaction. If the counter joined it, every attempt would erase its own
        // increment and this would never reach 429.
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

        // An attempts column on the otp row would be defeated here: generateOtp
        // inserts a new row and consumeOtp reads only the newest, so the fresh row
        // would arrive with attempts = 0. The counter is keyed on the identity instead.
        assertThat(verifyOtp(email, "000000")).isEqualTo(429);
    }

    @Test
    void aBlockedIdentityIsNotSentFreshCodes() throws Exception {
        String email = signUp();

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        // Mailing a code that cannot be used is both a bypass shape and a
        // mail-abuse path, so resend is refused while attempt-blocked.
        assertThat(resendOtp(email)).isEqualTo(429);
    }

    @Test
    void exceedingTheCapBurnsTheOutstandingCode() throws Exception {
        String email = signUp();
        String realCode = capturedCodeFor(email);

        for (int i = 0; i < 4; i++) {
            verifyOtp(email, "000000");
        }

        // Rejecting while leaving a valid credential in the database means it
        // revives when the window lapses. Credential state must match lockout state.
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=OtpAttemptCapTest`
Expected: FAIL — `guessesBeyondTheCapAreRejectedWith429` gets 400 instead of 429; nothing caps attempts yet.

- [ ] **Step 3: Add the burn query to `OtpRepository`**

```java
    /**
     * Expires every still-valid OTP for this address and purpose.
     *
     * <p>Not "the newest" — {@code generateOtp} can leave several unexpired rows,
     * and leaving any of them alive would let a burnt credential revive.
     */
    @Modifying
    @Query("update Otp o set o.expiredAt = :now "
            + "where o.email = :email and o.type = :type and o.expiredAt > :now")
    int expireActive(@Param("email") String email,
                     @Param("type") OtpType type,
                     @Param("now") LocalDateTime now);
```

- [ ] **Step 4: Write `OtpAttemptGuard`**

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import com.souldealers.crowdtracebackend.shared.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The OTP attempt cap.
 *
 * <p>An {@code attempts} column on the OTP row cannot work here: {@code generateOtp}
 * always inserts a new row while {@code consumeOtp} reads only the newest, so calling
 * {@code /resend-otp} would hand back a row with {@code attempts = 0}. The counter is
 * therefore keyed on the identity, which is exactly what an identity bucket is —
 * "5 guesses per 10 minutes per (email, purpose)".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpAttemptGuard {

    private final IdentityRateLimitGuard identityRateLimitGuard;
    private final OtpRepository otpRepository;

    /**
     * Charges one guess. When the cap is exceeded, burns the outstanding code
     * before throwing, so credential state agrees with lockout state.
     */
    public void beforeAttempt(OtpType purpose, String email) {
        try {
            identityRateLimitGuard.check(IdentityAction.OTP_ATTEMPT, purpose, email);
        } catch (RateLimitExceededException exception) {
            burnOutstandingCode(purpose, email);
            throw exception;
        }
    }

    /** Refunds the bucket once a code has been consumed successfully. */
    public void afterSuccess(OtpType purpose, String email) {
        identityRateLimitGuard.refund(IdentityAction.OTP_ATTEMPT, purpose, email);
    }

    /**
     * Refuses to mint a new code while the identity is attempt-blocked. Without
     * this, resend would mail a code that cannot be used — a bypass shape and a
     * mail-abuse path at once.
     */
    public void requireNotBlocked(OtpType purpose, String email) {
        if (identityRateLimitGuard.isBlocked(IdentityAction.OTP_ATTEMPT, purpose, email)) {
            identityRateLimitGuard.check(IdentityAction.OTP_ATTEMPT, purpose, email);
        }
    }

    /**
     * REQUIRES_NEW because the caller is about to throw. Joining its transaction
     * would roll the invalidation back and leave the burnt code alive.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void burnOutstandingCode(OtpType purpose, String email) {
        int burned = otpRepository.expireActive(email, purpose, LocalDateTime.now());
        if (burned > 0) {
            log.warn("OTP attempt cap exceeded; invalidated {} outstanding code(s) (purpose={})",
                    burned, purpose);
        }
    }
}
```

- [ ] **Step 5: Wire it into `AuthServiceImpl`**

Add the field and import:

```java
    private final OtpAttemptGuard otpAttemptGuard;
```

**`verifyOtp`** — charge before the lookup, refund on success:

```java
        String email = normalizeEmail(request.email());

        otpAttemptGuard.beforeAttempt(OtpType.CREATE, email);

        User user = userRepository.findByEmailForUpdate(email)
                .filter(candidate -> candidate.getAccountStatus() == UserStatus.PENDING_VERIFICATION)
                .orElseThrow(() -> new ValidationException(OTP_VERIFICATION_FAILED_MSG));

        if (!otpService.consumeOtp(request.code(), email, OtpType.CREATE)) {
            throw new ValidationException(OTP_VERIFICATION_FAILED_MSG);
        }

        otpAttemptGuard.afterSuccess(OtpType.CREATE, email);
```

**`resetPassword`** — same shape, after the password-match check so a typo does not spend a guess:

```java
        String email = normalizeEmail(request.email());

        otpAttemptGuard.beforeAttempt(OtpType.RESET, email);

        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ValidationException(OTP_VERIFICATION_FAILED_MSG));

        if (!otpService.consumeOtp(request.code(), email, OtpType.RESET)) {
            throw new ValidationException(OTP_VERIFICATION_FAILED_MSG);
        }

        otpAttemptGuard.afterSuccess(OtpType.RESET, email);
```

**`resendOtp`** — refuse while blocked, before charging the send bucket:

```java
        OtpType type = resolveOtpType(request.type());

        otpAttemptGuard.requireNotBlocked(type, email);
        identityRateLimitGuard.check(IdentityAction.OTP_SEND, type, email);
```

**`resetPasswordRequest`** — the same pair:

```java
        otpAttemptGuard.requireNotBlocked(OtpType.RESET, email);
        identityRateLimitGuard.check(IdentityAction.OTP_SEND, OtpType.RESET, email);
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=OtpAttemptCapTest`
Expected: PASS — 7 tests.

If `exceedingTheCapBurnsTheOutstandingCode` returns 200, `burnOutstandingCode` is being called on the caller's transaction and rolled back — check `REQUIRES_NEW` and that it is invoked through the Spring proxy.

- [ ] **Step 7: Verify the whole suite still passes**

Run: `./mvnw test`
Expected: PASS — 158 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit/OtpAttemptGuard.java \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/OtpAttemptCapTest.java
git commit -m "feat(identity): cap OTP guesses and close the resend bypass

A 6-digit code with a 5-minute window and unlimited guesses is a practical
account-takeover path; HMAC keying protects the database, not the guess rate.

The cap cannot live on the otp row. generateOtp always inserts a new row and
consumeOtp reads only the newest, so /resend-otp would hand back a row with
attempts = 0. It is keyed on the identity instead, which is what an identity
bucket already is: 5 guesses per 10 minutes per (email, purpose).

Exceeding the cap expires every outstanding code for that address and purpose
rather than merely rejecting, because a rejected-but-valid credential revives
when the window lapses. That runs in REQUIRES_NEW, since the caller is about to
throw and would otherwise roll the invalidation back.

Resend is refused while attempt-blocked: mailing a code that cannot be used is
both a bypass shape and a mail-abuse path.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Cleanup, failure policy, and release documentation

**Files:**
- Create: `shared/ratelimit/RateLimitBucketCleanupJob.java`
- Modify: `docs/superpowers/plans/2026-09-24-auth-hardening.md` (Deferred Items)
- Test: `src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit/RateLimitStoreFailurePolicyTest.java`

**Interfaces:**
- Consumes: `RateLimitBucketRepository` (Task 2), `IdentityRateLimitGuard` (Task 6).
- Produces: nothing downstream.

- [ ] **Step 1: Write the failing test**

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit.IdentityAction;
import com.souldealers.crowdtracebackend.modules.identity.internal.ratelimit.IdentityRateLimitGuard;
import com.souldealers.crowdtracebackend.shared.RateLimitUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the asymmetry: the coarse guard degrades, the security control refuses.
 * Getting these backwards is silent — either authentication dies during a
 * limiter incident, or the control quietly disappears during one.
 */
class RateLimitStoreFailurePolicyTest {

    static class Handler {
        @RateLimit("ip-login")
        public void limited() {}
    }

    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private HandlerMethod handler() throws Exception {
        Method method = Handler.class.getMethod("limited");
        return new HandlerMethod(new Handler(), method);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void theIpLayerDegradesRatherThanBlockingEveryone() throws Exception {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IpRateLimitInterceptor interceptor =
                new IpRateLimitInterceptor(rateLimiter, new ClientAddressResolver());

        assertThatCode(() -> interceptor.preHandle(
                request(), new MockHttpServletResponse(), handler()))
                .doesNotThrowAnyException();
    }

    @Test
    void theIdentityLayerRefusesRatherThanProceedingUnprotected() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IdentityRateLimitGuard guard = new IdentityRateLimitGuard(rateLimiter);

        assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void theIdentityLayerNeverFallsBackToAnInProcessCount() {
        when(rateLimiter.record(any(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("store down"));

        IdentityRateLimitGuard guard = new IdentityRateLimitGuard(rateLimiter);

        // Under multi-instance deployment an in-process fallback silently turns one
        // distributed cap into one cap per replica — precisely when the system is
        // already degraded. Every call must refuse, not the first N.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> guard.check(IdentityAction.LOGIN, "user@example.com"))
                    .isInstanceOf(RateLimitUnavailableException.class);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails or passes**

Run: `./mvnw test -Dtest=RateLimitStoreFailurePolicyTest`
Expected: PASS — the behaviour was built in Tasks 5 and 6. This test exists because the asymmetry is the thing most likely to be "simplified" later by someone making both layers consistent.

If it fails, a layer has the wrong policy. Fix the layer, not the test.

- [ ] **Step 3: Write the cleanup job**

Mirrors `ExpiredCredentialCleanupJob`, which purges expired OTPs and revoked tokens on the same schedule.

```java
package com.souldealers.crowdtracebackend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rate-limit buckets past their purge time carry no value — charging already
 * treats an expired window as a fresh one — so this bounds table growth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitBucketCleanupJob {

    private final RateLimitBucketRepository repository;
    private final RateLimitProperties properties;

    @Scheduled(cron = "${rate-limit.cleanup-cron:0 15 3 * * *}")
    public void purgeExpired() {
        if (!properties.isEnabled()) {
            return;
        }

        int purged = repository.purgeExpired();
        log.info("Purged expired rate limit buckets (count={})", purged);
    }
}
```

- [ ] **Step 4: Add the decision metrics**

The spec calls for `allowed` / `denied` / `store_error` counters tagged by policy, so the starting
limits can be tuned against real traffic instead of guessed at. Actuator is already a dependency, so
`MeterRegistry` is available for injection.

Tag by **policy name only**. Tagging by email or IP would both recreate the PII this design removed
from the table and blow up metric cardinality.

Add to `IpRateLimitInterceptor`:

```java
    private final MeterRegistry meterRegistry;

    private void count(String policyName, String outcome) {
        meterRegistry.counter("ratelimit.decision",
                "layer", "ip", "policy", policyName, "outcome", outcome).increment();
    }
```

Call `count(rateLimit.value(), "store_error")` in the `DataAccessException` branch, and
`count(rateLimit.value(), decision.allowed() ? "allowed" : "denied")` after a successful charge.

Add the equivalent to `IdentityRateLimitGuard`, with `"layer", "identity"`:

```java
    private final MeterRegistry meterRegistry;

    private void count(IdentityAction action, String outcome) {
        meterRegistry.counter("ratelimit.decision",
                "layer", "identity", "policy", action.policyName(), "outcome", outcome).increment();
    }
```

Call it from `enforce` on both branches and from `unavailable`. Both classes use
`@RequiredArgsConstructor`, so adding the final field is the only wiring needed — but the existing
unit tests construct these classes directly, so update those constructor calls to pass
`new SimpleMeterRegistry()`.

- [ ] **Step 4b: Re-run the tests that construct these classes directly**

Run: `./mvnw test -Dtest='IpRateLimitInterceptorTest,IdentityRateLimitGuardTest,RateLimitStoreFailurePolicyTest'`
Expected: PASS — 17 tests.

- [ ] **Step 5: Update the prior plan's Deferred Items**

In `docs/superpowers/plans/2026-09-24-auth-hardening.md`, replace the **Rate limiting / OTP attempt cap** row of the *Required before any production release* table with:

```markdown
| **Rate limiting / OTP attempt cap** (review item 5) | ~~Explicitly excluded by the user~~ **Delivered 2026-09-25** | Implemented per `docs/superpowers/specs/2026-09-25-otp-rate-limiting-design.md`. Two layers over `rate_limit_bucket`: a coarse IP guard and an identity-keyed control, with the OTP attempt cap keyed on identity rather than on the OTP row (a per-row counter is bypassable via `/resend-otp`). |
```

Add a row to the same table for the new secret:

```markdown
| **Prod `rate-limit.hmac-secret` is undefined** | Introduced 2026-09-25 under the same constraint as `otp.hmac-secret` | Prod needs `RATE_LIMIT_HMAC_SECRET` set before first boot. Valid Base64 of at least 32 random bytes, and **distinct from `OTP_HMAC_SECRET`** so rotating one does not affect the other. Validated configuration prevents startup otherwise. |
```

Add a row to the *Lower priority* table:

```markdown
| **Forwarded-header strategy is `none`** | Proxy topology is undocumented | `server.forward-headers-strategy: none`, so the IP layer keys on the socket address. Safe, but if a load balancer is in front, all traffic attributes to it and the IP layer stops distinguishing clients — the identity layer then carries the whole load. Switch to `native` with explicit `internal-proxies` once the LB CIDRs are known. |
```

- [ ] **Step 6: Run the full verification**

```bash
./mvnw test
./mvnw test -Dtest=CrowdtraceModulesTest
```

Expected: PASS — 161 tests, 0 failures, and module verification green.

- [ ] **Step 7: Confirm the limiter is live outside the test profile**

The whole mechanism is disabled in `application-test.yaml`, so a green suite alone does not prove it works when deployed. Start the app against the dev profile and drive the cap by hand:

```bash
export RATE_LIMIT_HMAC_SECRET=$(head -c 32 /dev/urandom | base64)
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then, in a second shell:

```bash
for i in $(seq 1 8); do
  curl -s -o /dev/null -w '%{http_code} ' \
    -X POST http://localhost:8080/api/v1/auth/verify-otp \
    -H 'Content-Type: application/json' \
    -d '{"email":"nobody@example.com","code":"000000"}'
done; echo
```

Expected: five non-429 responses, then `429` for the rest. Confirm `Retry-After` is present and at least 1:

```bash
curl -s -D - -o /dev/null \
  -X POST http://localhost:8080/api/v1/auth/verify-otp \
  -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.com","code":"000000"}' | grep -i 'retry-after\|^HTTP'
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit \
        src/test/java/com/souldealers/crowdtracebackend/shared/ratelimit \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/ratelimit \
        docs/superpowers/plans/2026-09-24-auth-hardening.md
git commit -m "feat(ratelimit): purge stale buckets and pin the failure policy

Buckets past their purge time carry no value, since charging already treats an
expired window as a fresh one, so a nightly job bounds table growth alongside
the existing expired-credential cleanup.

The failure-policy test exists because the asymmetry is the thing most likely to
be tidied away later: the IP guard degrades so a limiter outage cannot take
authentication down, while the identity layer refuses with 503 because
proceeding without it is proceeding unprotected. It also pins that there is no
in-process fallback, which under multi-instance deployment would silently turn
one distributed cap into one cap per replica.

Marks review item 5 delivered in the CT-007 Deferred Items and records
RATE_LIMIT_HMAC_SECRET as a new required production secret.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Done when

- [ ] `./mvnw test` is green — 161 tests, 0 failures (baseline was 93).
- [ ] `CrowdtraceModulesTest` passes: `shared` never imports `modules.identity`.
- [ ] Step 6 of Task 8 was run by hand and the cap fired outside the test profile.
- [ ] `RATE_LIMIT_HMAC_SECRET` is documented as a required production secret, distinct from `OTP_HMAC_SECRET`.
- [ ] `grep -rn "X-Forwarded-For" src/main/java` returns nothing.
- [ ] `ratelimit.decision` counters appear at `/actuator/metrics`, tagged by policy and never by email or IP.
