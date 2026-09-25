# OTP & Auth Endpoint Rate Limiting — Design

**Date:** 2026-09-25
**Status:** Approved for planning
**Ticket:** Successor to CT-007. Closes review item 5 ("Rate limiting / OTP attempt cap") from
`docs/superpowers/plans/2026-09-24-auth-hardening.md` → Deferred Items → *Required before any production release*.

---

## 1. Why

The auth hardening work keyed OTP codes with HMAC-SHA-256, which protects the *database*. It does
nothing about the *guess rate*. The prior plan states the consequence plainly:

> A 6-digit code with a 5-minute window and unlimited guesses on `/reset-password` is a practical
> account-takeover path even with HMAC storage, because keying protects the database, not the guess
> rate. `/signup` and `/resend-otp` are also unthrottled email-send triggers.

Two distinct problems follow from that:

1. **Credential brute force.** 10^6 codes, 5-minute window, unlimited attempts. An attacker scripting
   `/reset-password` against one address takes the account.
2. **Email-send abuse.** `/signup`, `/resend-otp` and `/request-password-reset` each trigger an
   outbound email with no throttle. That is a free mail-bombing primitive aimed at a third party, and
   it burns the Resend quota.

This design closes both, and is explicitly the release gate the prior review named.

## 2. Intended outcome

A request-throttling control that:

- is **correct across multiple app instances** (confirmed deployment target),
- makes 6-digit OTP brute force infeasible **without** being bypassable via `/resend-otp`,
- does not lock out shared-NAT populations (offices, universities, carrier NAT) as collateral,
- does not become a new account-enumeration oracle,
- returns a standards-correct `429` that tells a well-behaved client when to retry,
- adds **no new stateful infrastructure**.

### Non-goals

- Volumetric DoS protection. That belongs at an edge gateway or WAF; PostgreSQL cannot protect
  itself from traffic large enough to saturate connection acquisition.
- Per-user configurable limits, quota dashboards, or an admin override UI.
- Closing the pre-existing response-timing difference between known and unknown accounts
  (mail is sent only for real accounts). This design must not *worsen* it; equalising it is separate work.
- CAPTCHA, device fingerprinting, or adaptive/behavioural throttling.

## 3. Decisions and their rationale

### 3.1 Storage: PostgreSQL, not Redis

**Decision:** a `rate_limit_bucket` table in the existing database, written with a single atomic
`INSERT … ON CONFLICT DO UPDATE … RETURNING`.

The reference implementation being ported from
(`fine-dine/services/common/.../ratelimit/RateLimitInterceptor.java`) *is* a Redis `INCR`+`EXPIRE`
Lua script. crowdtrace has no Redis: none in `pom.xml`, no service in `docker-compose.yaml`. So this
is a port of the *shape*, not the code.

| Option | Multi-instance correct | New infra | Verdict |
|---|---|---|---|
| Redis + Lua (the fine-dine port) | yes | Redis container, HA, TLS, monitoring, failure policy, prod provisioning | Right later, unjustified now |
| Bucket4j / Caffeine in-process | **no** — one cap per replica, and counters reset on every rolling deploy | none | Disqualified: deployment is multi-instance |
| PostgreSQL + Flyway | yes | none | **Chosen** |

An in-process limiter was ruled out by the stated topology. Between the two correct options,
PostgreSQL wins on cost: the datasource, Flyway pipeline, and a proven Testcontainers concurrency-test
pattern (`TokenRevocationRaceTest.java`) already exist. `ON CONFLICT DO UPDATE` is atomic under
concurrency and uses the database clock, so replicas cannot disagree about the window.

**What would change this:** sustained hostile traffic where limiter writes themselves burden the
database; a need to reject before consuming a DB connection; multi-region deployment; or an existing
managed Redis with clear ownership. The `RateLimiter` interface exists so that swap is a new
implementation class plus config, not a rewrite. Record this as the documented upgrade path — but do
not build a second unused implementation now (YAGNI).

### 3.2 Two layers, because one key cannot do both jobs

The fine-dine interceptor keys on `clientIp + requestURI` only. That is the wrong key for this
threat: the attack is against **one account**, and an attacker rotating IPs walks straight through an
IP-only limit. Conversely an identity-only limit ignores broad scraping. Both are needed, and they
have genuinely different requirements:

| | **Coarse guard** | **The security control** |
|---|---|---|
| Key | `HMAC(clientIp) + method + route pattern` | `HMAC(normalisedEmail) + action + purpose` |
| Protects against | broad abuse, scripted scanning | targeted account takeover, mail bombing |
| Enforced in | `HandlerInterceptor` | explicit call in the service layer |
| Store unreachable | **fail open** + metric | **fail closed** → `503` |
| Limits | deliberately coarse | tight |

**IP limits are intentionally looser than identity limits.** fine-dine uses 5 logins per IP per 5
minutes (`fine-dine .../AuthController.java:34`). As the *only* layer that locks out an entire office,
university or carrier-NAT range on one user's typos. The coarse layer catches scripts; the identity
layer catches the actual attack.

**The identity layer cannot live in the interceptor.** `preHandle` runs before body parsing, and
reading the `InputStream` there consumes it before the argument resolver sees it. Alternatives
considered and rejected:

- **AOP with a SpEL key expression** — a DTO field rename or expression typo fails only at runtime;
  self-invocation silently bypasses the aspect; aspect/transaction ordering can roll the counter back
  with the failed transaction (see §3.3). Too much action-at-a-distance for a security control.
- **A body-caching filter** (`ContentCachingRequestWrapper`) — requires buffering and parsing
  untrusted JSON twice, handling malformed and oversized bodies, and holding plaintext credentials in
  memory longer.
- **Explicit guard calls in `AuthServiceImpl`** — **chosen.** The email is already parsed, validated
  and normalised there (`AuthServiceImpl.normalizeEmail`), so the guard reuses the exact same
  canonical string the rest of auth uses. The call site is visible when reading the method. Its one
  weakness — someone adding a seventh endpoint and forgetting the guard — is covered by a test that
  enumerates every mapping on `AuthController` and asserts a policy exists for it.

### 3.3 The counter must commit independently of the request

**This is the defect most likely to ship unnoticed.** `signUp`, `verifyOtp` and `resetPassword` are
`@Transactional` (`AuthServiceImpl.java:43,105,148`). A failed OTP guess throws `ValidationException`,
which rolls that transaction back. If the counter increment joined it, **every failed attempt erases
its own evidence** and the cap never fires — while unit tests that assert "6th call is rejected"
within a single passing transaction still go green.

The guard therefore runs in `Propagation.REQUIRES_NEW`, and — critically — **returns a decision
rather than throwing**. Throwing inside the new transaction would mark it rollback-only and undo the
very increment being recorded.

```java
RateLimitDecision decision = identityRateLimitGuard.record(OTP_ATTEMPT, RESET, email);
if (decision.denied()) {
    throw new RateLimitExceededException(decision);   // thrown OUTSIDE the counter transaction
}
```

A dedicated test asserts the increment survives an outer transaction that subsequently throws.

### 3.4 The OTP attempt cap is the same mechanism, not a second one

An `attempts` column on the `otp` row — the obvious implementation — **does not work here.**
`generateOtp` always inserts a new row (`OtpServiceImpl.java:34`) while `consumeOtp` reads only the
newest row for `(email, type)` (`OtpServiceImpl.java:54`, `OtpRepository:18`). So:

```
guess ×5  → attempts hits the cap
POST /resend-otp → new otp row, attempts = 0
consumeOtp reads the NEW row → cap bypassed, guessing resumes
```

The cap must therefore live outside the OTP rows, keyed on the identity — which is exactly what an
identity-layer bucket already is. An OTP attempt cap *is* a rate limit: "5 guesses per 10 minutes per
`(email, purpose)`". So it reuses `rate_limit_bucket` with `action = OTP_ATTEMPT`, and
`window_ends_at` doubles as "blocked until". **One table, one SQL statement, one mental model.**

*(Considered and rejected: a separate `otp_attempt_state` table. It would duplicate the counter,
window and purge logic for no behavioural gain.)*

Three rules compose the controls:

1. **Exceeding the cap burns the code.** Set the newest OTP's `expiredAt = now()`. Rejecting while
   leaving a valid credential in the database means it revives when the window lapses, a policy
   changes, or a purge misfires. Credential state must agree with lockout state.
2. **Resend does not clear the attempt counter,** and is itself denied while the identity is
   attempt-blocked. Otherwise the system mails a code that cannot be used — worse UX *and* a bypass.
3. **The attempt window (10 min) must be ≥ the OTP lifetime (5 min, `OtpServiceImpl.java:24`).** A
   shorter window would reset mid-life and hand back free guesses against a still-valid code.

### 3.5 Identity send buckets are shared across equivalent endpoints

*(This section is about the identity layer. The IP layer keys per route, as §3.2 states.)*

`/signup` and `/resend-otp?type=CREATE` both mint a `CREATE` OTP; `/request-password-reset` and
`/resend-otp?type=RESET` both mint a `RESET` one. Separate buckets would let an attacker alternate
endpoints to double the allowance. The identity bucket key is the **action and purpose**, not the URL:

```
LOGIN                    ← /login
OTP_SEND    + CREATE     ← /signup, /resend-otp(CREATE)
OTP_SEND    + RESET      ← /request-password-reset, /resend-otp(RESET)
OTP_ATTEMPT + CREATE     ← /verify-otp
OTP_ATTEMPT + RESET      ← /reset-password
```

### 3.6 Client IP: do not port `getClientIp`

`RateLimitInterceptor.getClientIp` takes the leftmost `X-Forwarded-For` value and accepts it if it
matches an IPv4-shaped regex. Since nothing establishes that the header came from a trusted proxy,
**any client can send a different `X-Forwarded-For` per request and get a fresh bucket every time.**
That is worse than having no IP limit, because it looks like a control in code review and in tests.
It also accepts shaped nonsense (`999.999.999.999`) and silently ignores forwarded IPv6, falling
through to `getRemoteAddr()`.

**Decision:** `server.forward-headers-strategy: none` in all profiles, and use `getRemoteAddr()` only.
Do not hand-parse forwarding headers anywhere.

The proxy topology is an open deployment question (§7). Ship closed. When the load balancer and its
CIDRs are documented, the switch is configuration, not code:

```yaml
server:
  forward-headers-strategy: native
  tomcat:
    remoteip:
      internal-proxies: "<exact LB/reverse-proxy CIDRs>"
```

`framework` (Boot's `ForwardedHeaderFilter`) is only safe when the app is unreachable around the
proxy *and* the edge strips all inbound `Forwarded`/`X-Forwarded-*` before writing its own. Both
conditions must be verified before enabling either mode. A test asserts that with forwarding
disabled, rotating `X-Forwarded-For` does **not** yield a new bucket.

### 3.7 Do not store raw IPs or emails

`subject_key` holds an HMAC-SHA-256 of the canonical IP or normalised email, under a dedicated
`rate-limit.hmac-secret` — **distinct from `otp.hmac-secret`**, so rotating one does not affect the
other, and a limiter-table leak does not reveal which addresses use the service. Logs and metrics
carry the policy name and a truncated key prefix, never the raw value. (fine-dine logs raw client IPs
at WARN on every ordinary quota exhaustion; that is both a privacy leak and log noise.)

Rotating the rate-limit key clears all active buckets — an acceptable, documented consequence.

## 4. Components

Generic mechanism goes in `shared`; identity-specific policy and the OTP side-effect go in the
identity module. `shared` must not learn about the `Otp` entity, and the exception handled by
`GlobalExceptionHandler` (which lives in `shared`) must not live inside a feature module's internals.
Dependency direction is one-way: `modules/identity/internal → shared/ratelimit`, never the reverse.
`shared.ratelimit` is **not** declared as a new Spring Modulith application module; it is part of the
existing shared kernel. `CrowdtraceModulesTest` must pass unchanged.

```
shared/ratelimit/
  RateLimiter.java                  interface: record(...), peek(...)
  RateLimitDecision.java            record(allowed, limit, windowSeconds, retryAfterSeconds)
  RateLimitScope.java               enum { IP, IDENTITY }
  RateLimitPolicy.java              record(name, limit, Duration window)
  PostgresRateLimiter.java          the upsert; REQUIRES_NEW
  RateLimitBucketRepository.java    native query
  RateLimitProperties.java          @ConfigurationProperties("rate-limit"), validated at startup
  ClientAddressResolver.java        getRemoteAddr() + canonicalisation, no header parsing
  IpRateLimitInterceptor.java       coarse layer, fail-open
  RateLimitWebConfig.java           registers the interceptor
  RateLimitBucketCleanupJob.java    @Scheduled purge of purge_after < now

shared/
  RateLimitExceededException.java      → 429   (sits beside the other shared exceptions)
  RateLimitUnavailableException.java   → 503

modules/identity/internal/ratelimit/
  IdentityAction.java               enum { LOGIN, OTP_SEND, OTP_ATTEMPT }
  IdentityRateLimitGuard.java       record()/peek() against the identity scope
  OtpAttemptGuard.java              attempt cap + burn-the-OTP side-effect
```

### 4.1 Schema — `V8__create_rate_limit_bucket.sql`

```sql
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

### 4.2 The one statement that matters

```sql
INSERT INTO rate_limit_bucket AS b
       (scope, action, subject_key, request_count, window_started_at, window_ends_at, purge_after)
VALUES (:scope, :action, :subjectKey, 1, now(), now() + :window, now() + :window + interval '1 hour')
ON CONFLICT (scope, action, subject_key) DO UPDATE SET
    request_count     = CASE WHEN b.window_ends_at <= now() THEN 1
                             ELSE b.request_count + 1 END,
    window_started_at = CASE WHEN b.window_ends_at <= now() THEN now()
                             ELSE b.window_started_at END,
    window_ends_at    = CASE WHEN b.window_ends_at <= now() THEN now() + :window
                             ELSE b.window_ends_at END,
    purge_after       = CASE WHEN b.window_ends_at <= now() THEN now() + :window + interval '1 hour'
                             ELSE b.purge_after END
RETURNING request_count, window_ends_at;
```

Three properties to preserve, each with a test:

- **Atomic.** One statement; concurrent callers serialise on the row. No read-then-write race.
- **A denied request must never extend `window_ends_at`.** Note the `ELSE` branches leave it
  untouched. Extending on denial would let an attacker hold a victim in permanent lockout by
  continuing to hammer a blocked bucket.
- **Database clock throughout.** `now()`, not an app-server clock, so replicas with drifting clocks
  cannot disagree about the window. (This also fixes the mixed-clock inconsistency noted in the prior
  plan's Deferred Items — new columns are `TIMESTAMPTZ`.)

`peek` is the same key with a plain `SELECT`, no mutation — used to ask "is this identity currently
attempt-blocked?" without charging the bucket, so a denied resend does not consume the send allowance.

### 4.3 Limits

Configuration-driven via `RateLimitProperties`, so tuning is a config change. Starting values:

| Action | IP layer | Identity layer |
|---|---|---|
| `LOGIN` | 60 / 5 min | 5 / 5 min |
| `OTP_SEND` (per purpose) | 20 / hr | 3 / hr |
| `OTP_ATTEMPT` (per purpose) | 60 / 10 min | 5 / 10 min |
| `POST /signup` (IP layer only) | 30 / hr | n/a — identity side is `OTP_SEND + CREATE` |

Treat these as safe starting points, not product requirements. Emit
`allowed`/`denied`/`store_error` counters tagged by policy name (never by raw email or IP) so they
can be tuned against real traffic.

## 5. Error handling

Both exceptions extend `RuntimeException` and are unchecked, matching the existing shared exceptions
(`ValidationException`, `ConflictException`, …).

### 5.1 The handler must return `ResponseEntity`

`GlobalExceptionHandler`'s private `problem(status, title, detail, code, request)` helper builds a
`ProblemDetail` body and every handler returns it bare (`GlobalExceptionHandler.java:173`). A bare
`ProblemDetail` **cannot carry response headers**, so a `429` built that way has no `Retry-After` —
the single most common mistake in rate-limit implementations, and one fine-dine makes.

The two new handlers — and only those two — return `ResponseEntity<ProblemDetail>`. Existing handlers
are untouched; Spring MVC accepts a mix of return types across `@ExceptionHandler` methods, so this
is additive.

```java
@ExceptionHandler(RateLimitExceededException.class)
public ResponseEntity<ProblemDetail> handleRateLimitExceeded(
        RateLimitExceededException exception, HttpServletRequest request) {

    log.warn("Rate limit exceeded on {} {} (policy={}, retryAfter={}s)",
            request.getMethod(), request.getRequestURI(),
            exception.policyName(), exception.retryAfterSeconds());

    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
            .header("RateLimit-Limit", Long.toString(exception.limit()))
            .header("RateLimit-Remaining", "0")
            .header("RateLimit-Reset", Long.toString(exception.retryAfterSeconds()))
            .header("RateLimit-Policy", exception.limit() + ";w=" + exception.windowSeconds())
            .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    RATE_LIMIT_EXCEEDED_MSG, "RATE_LIMIT_EXCEEDED", request));
}
```

`problem()` already sets `type` to `urn:crowdtrace:problem:rate-limit-exceeded`, `instance`, `code`
and `correlationId`. No change to the helper is required.

**`Retry-After` is computed from the `window_ends_at` returned by the upsert**, rounded up and clamped
to ≥ 1 second — not from the configured window length. fine-dine's Lua script returns only the count,
so it cannot do this and reports the full window on every denial, telling a client to wait far longer
than necessary.

`RateLimit-*` headers are emitted on `429` only. Emitting them on success would require deciding
which of the two layers is authoritative and would expose per-identifier activity to anyone who can
send a request.

### 5.2 `503` when the identity layer cannot reach its store

```
RateLimitUnavailableException → 503 Service Unavailable
    code: RATE_LIMIT_UNAVAILABLE
    Retry-After: 5
```

`503` is deliberately distinct from `429`: the client did nothing wrong, and a `429` would be a lie
that also trains clients to back off for the wrong reason.

### 5.3 Fail-open vs fail-closed, and what "failure" means

| Layer | Store unreachable | Why |
|---|---|---|
| IP interceptor | **fail open**, log at WARN (rate-limited), increment `store_error` | A limiter outage must not take authentication down for everyone. This layer is a coarse guard, not the control. |
| Identity guard (`LOGIN`, `OTP_SEND`) | **fail closed** → `503`; no login, no OTP minted, no mail | These *are* the controls preventing takeover and mail abuse. Proceeding without them is proceeding unprotected. |
| `OTP_ATTEMPT` + burn | **fail closed** → `503`; do not reach `consumeOtp` | Verification must not run uncounted. |

Two rules on the catch:

- **Catch only store-connectivity failures** (`DataAccessResourceFailureException`,
  `CannotCreateTransactionException`, `QueryTimeoutException` — i.e. the relevant `DataAccessException`
  subtypes). A missing table, a malformed query or an NPE is a **programming error** and must surface
  as `500`. A blanket `catch (Exception)` on the fail-open path would silently disable the limiter for
  the lifetime of a bug.
- **Never fall back to an in-process counter.** Under multi-instance deployment that quietly converts
  one shared cap into N independent caps — the exact failure mode §3.1 rejected, arriving precisely
  when the system is already degraded.

### 5.4 Interceptor exceptions reach the handler

An exception thrown from `HandlerInterceptor.preHandle` is caught by `DispatcherServlet.doDispatch`
and routed through the `HandlerExceptionResolver` chain, so `@RestControllerAdvice` handles it
normally. This is load-bearing and non-obvious, so a test asserts the interceptor's `429` arrives as
`application/problem+json` with the correct headers, rather than as a container error page.

### 5.5 Message constants

Added to `CustomMessages`, matching the existing generic-message convention — deliberately vague, so
the body reveals nothing about which layer or key tripped:

```java
public static final String RATE_LIMIT_EXCEEDED_MSG   = "Too many requests, try again later";
public static final String RATE_LIMIT_UNAVAILABLE_MSG = "Service temporarily unavailable, try again shortly";
```

## 6. Account enumeration

`/resend-otp` and `/request-password-reset` currently send mail only when the user exists but always
return the same generic body (`AuthServiceImpl.java:133,143`), and `AccountEnumerationTest` /
`ResendOtpTest` / `PasswordResetFlowTest` pin that behaviour.

**A carelessly placed guard breaks it.** This is the wrong order:

```java
userRepository.findByEmail(email).ifPresent(user -> {
    guard.record(OTP_SEND, purpose, email);   // WRONG — unknown accounts never charge a bucket
    generateAndSendOtp(user, purpose);
});
```

Unknown addresses would then never see a `429`, turning the status code into a clean existence oracle
— reintroducing exactly what `AccountEnumerationTest` was written to prevent.

**Correct order — charge the bucket before the lookup:**

```
normalise email  →  charge identity bucket  →  if denied, throw (429)
                 →  findByEmail             →  conditionally mint + send
                 →  return the existing generic success message
```

Key, limit, status, body and headers must be **byte-identical** for known and unknown addresses. The
test compares full responses, excluding only `correlationId` and `instance`, following the existing
approach in `AccountEnumerationTest`.

This does not remove the pre-existing timing difference from actually sending mail (a non-goal, §2) —
but it must not add a second, far easier oracle on top of it.

## 7. Open questions

| Question | Disposition |
|---|---|
| **Proxy topology** — is the app reachable directly, or only via an LB that strips inbound `X-Forwarded-*`? | Unresolved. Ship `forward-headers-strategy: none`; §3.6 documents the switch. Until then the IP layer keys on socket address, which is always safe but attributes all traffic to the proxy if one exists — making the identity layer carry the load. |
| Identity lockout enables targeted denial of service (attacker locks a victim out by burning their attempts) | Inherent to any attempt cap. Mitigated by keeping the window short (10 min) and by never extending it on denial (§4.2). Recovery UX needs product agreement. |
| Limit values | Starting points, tuned from the metrics in §4.3. |
| Prod `RATE_LIMIT_HMAC_SECRET` | New required env var, joining `SECRET_KEY` and `OTP_HMAC_SECRET` in the prior plan's Deferred Items. Validated at startup like `OtpProperties`, so a missing key fails the boot rather than weakening the control silently. |

## 8. Test strategy

The test that matters is not "the annotation is present" — it is "the counter survives a rolled-back
request" and "resend does not reset the cap". Both describe limiters that look correct in ordinary
unit tests.

**Store (PostgreSQL Testcontainers — `application-test.yaml` uses H2 with `create-drop` and Flyway
disabled, so it cannot prove the `ON CONFLICT` upsert; follow the `TokenRevocationRaceTest` pattern):**

1. **Atomicity under concurrency.** N > limit callers on one key via a start barrier → exactly `limit`
   allowed, one row, correct count.
2. **Rollback independence.** Record inside an outer transaction that then throws → counter still
   incremented. *This is the highest-value test in the suite.*
3. **Window behaviour.** `limit` passes, `limit + 1` denied, `Retry-After` accurate, expiry resets,
   **denial does not extend the window**, distinct action/purpose keys stay isolated.
4. **Migration + purge.** Table, PK, index; cleanup job removes only `purge_after < now()`.

**HTTP:**

5. Parameterised over all six endpoints: allowance works, then `429` +
   `application/problem+json` + `RATE_LIMIT_EXCEEDED` + accurate `Retry-After` + `RateLimit-*`.
6. **Layer isolation.** Changing IP does not bypass the identity cap; changing email does not bypass
   the IP cap.
7. **Header spoofing.** With forwarding disabled, rotating `X-Forwarded-For` still shares one bucket.

**Security regression:**

8. **Resend bypass.** Exhaust attempts → resend → counter not reset, newest OTP expired, the new code
   cannot unlock, no mail sent while blocked. (`OtpKeyingTest` proves one-time consumption but not
   attempt state.)
9. **Concurrent guesses** never exceed the cap; a correct and an over-cap request racing yield at
   most one successful consumption.
10. **Known/unknown parity** below and above the limit, full-response comparison.
11. **Failure policy.** Store unavailable → IP layer proceeds; identity layer returns `503` with no
    OTP, mail, login or password mutation.
12. **`CrowdtraceModulesTest` passes** and the full suite is green (baseline: 93 tests).

## 9. Build order

Each step is independently verifiable; the mechanism is proven on PostgreSQL before anything depends
on it.

1. Config + policy model: `RateLimitProperties` (validated at startup), policy names, `RateLimitDecision`.
2. `V8` migration + the upsert + Testcontainers tests 1–4. **Mechanism proven before use.**
3. `PostgresRateLimiter`, HMAC keying, `REQUIRES_NEW`, purge job.
4. Exceptions + `GlobalExceptionHandler` returning `ResponseEntity<ProblemDetail>` + `CustomMessages`.
5. `ClientAddressResolver` + `IpRateLimitInterceptor` + `forward-headers-strategy: none` + tests 5, 7.
6. `IdentityRateLimitGuard` wired into `AuthServiceImpl` **before** every user lookup + tests 6, 10.
7. `OtpAttemptGuard`: cap, OTP burn, resend-lockout composition + tests 8, 9.
8. Failure-policy tests (11), metrics, module verification and full suite (12).
9. Document `RATE_LIMIT_HMAC_SECRET` and the proxy switch; update the prior plan's Deferred Items.

## 10. References

- `docs/superpowers/plans/2026-09-24-auth-hardening.md` — Deferred Items, review item 5
- `docs/superpowers/plans/2026-09-24-auth-hardening-review.md` — "keep rate limiting, OTP attempt caps … as release gates"
- `docs/decisions/ADR-001-modular-monolith.md` — module boundary rules
- Reference implementation ported from: `fine-dine/services/common/src/main/java/com/finedine/common/ratelimit/`
  (shape adopted; Redis backing, IP-only keying and `getClientIp` header trust deliberately not adopted)
- Peer review by Codex (gpt-5.6-sol) informing §3.3, §3.6, §5 and §8
