# OTP Rate Limiting Design — Peer Review

**Reviewed design:** `docs/superpowers/specs/2026-09-25-otp-rate-limiting-design.md`
**Reviewer:** Codex (`gpt-5.6-sol`, high reasoning effort, `sandbox: read-only`)
**Review date:** 2026-09-25
**Verdict:** Approved with changes — use PostgreSQL rather than Redis, split the control into two
layers, and do not port the fine-dine client-IP resolution

## Executive Summary

The review was commissioned *before* the design was written, to settle four questions that could not
be answered from the repository alone: which storage backend, where the identity-keyed check can
physically live, whether a per-row OTP attempt counter is bypassable, and what the `429` contract
should be.

Its headline conclusion: **approve a PostgreSQL-backed, two-layer design; do not port the fine-dine
interceptor verbatim.** Redis is a later scaling move, not a prerequisite for this ticket.

Three findings changed the design materially, and all three describe implementations that pass
ordinary unit tests while failing in production:

1. **The counter must commit outside the request transaction.** Not previously considered.
2. **A per-row OTP `attempts` column is bypassable via `/resend-otp`.** Suspected before the review;
   confirmed here with file:line evidence.
3. **`getClientIp`'s `X-Forwarded-For` handling is forgeable.** Suspected; confirmed, with the correct
   Spring Boot 4.1 remedy.

A fourth blocking issue — that the interceptor breaks the existing H2 test suite — was found *after*
this review, during design self-review, and is recorded in §8.1 of the spec. The review did flag that
production persistence tests must run on PostgreSQL Testcontainers; it did not extrapolate to the
other 93 tests.

## Findings That Changed the Design

### 1. Storage: PostgreSQL, not Redis (design §3.1)

There is no Redis dependency in `pom.xml` and no Redis service in `docker-compose.yaml`. The review
rejected an in-process limiter outright for the stated multi-instance deployment — it yields one cap
per replica and resets on every rolling deploy — and rejected Redis as unjustified operational cost
when an atomic `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` on the existing datasource gives the
same cross-replica correctness.

It named the conditions that would reverse this: limiter writes burdening the database, a need to
reject before consuming a DB connection, multi-region deployment, or an existing managed Redis with
clear ownership. It also advised storing HMACs of IP and email rather than raw values, under a key
distinct from `otp.hmac-secret`.

Two disciplined refusals worth recording. Asked to verify library compatibility, the review **declined
to name a Bucket4j version**, on the grounds that none is managed or present in the repository and the
recommended design does not need one. And it corrected a factual error in the brief: the repository
targets **Java 21** (`pom.xml:30`), not Java 25 as the prompt asserted, noting that Java 25
compatibility would have to be demonstrated by running the suite under JDK 25 rather than inferred.

### 2. The counter must commit independently of the request (design §3.3)

**This finding was not anticipated and is the most valuable in the review.**

`signUp`, `verifyOtp` and `resetPassword` are `@Transactional`
(`AuthServiceImpl.java:43,105,148`). A failed OTP guess throws `ValidationException`, rolling that
transaction back. If the counter increment joins it, every failed attempt erases its own evidence and
the cap never fires.

The review specified `Propagation.REQUIRES_NEW` **and** that the guard must *return a decision rather
than throw* — throwing inside the new transaction would mark it rollback-only and undo the increment
being recorded. The exception is raised by the caller, outside that boundary.

Its own summary of why this matters:

> The most important tests are the rollback and resend tests: a limiter can appear correct in ordinary
> unit tests while every failed authentication silently erases its counter.

### 3. The resend bypass, confirmed (design §3.4)

`generateOtp` always inserts a new row (`OtpServiceImpl.java:34`) while `consumeOtp` reads only the
newest row for `(email, type)` (`OtpServiceImpl.java:54`, `OtpRepository.java:18`). An `attempts`
column is therefore defeated by calling `/resend-otp` to mint a fresh row with `attempts = 0`.

Beyond confirming it, the review specified the composition rules adopted in the design: exceeding the
cap must **invalidate** the newest OTP (`expiredAt = now()`) rather than merely reject, because
"merely rejecting while a counter is blocked leaves a valid credential that may revive after cleanup,
policy change, counter reset, or partial failure"; resend must never clear the counter and must
itself be denied while blocked; and the attempt window must be at least the OTP's five-minute
lifetime (`OtpServiceImpl.java:24`).

### 4. Keying and placement (design §3.2, §3.5)

`preHandle` cannot see the request body, so the identity-keyed check cannot live in the interceptor.
The review compared three placements and recommended **explicit service-layer guards**:

- **AOP with SpEL** — rejected: a DTO rename or expression typo fails only at runtime, self-invocation
  bypasses the aspect, and aspect/transaction ordering can roll the counter back with the failed
  transaction (finding 2).
- **Body-caching filter** — rejected: buffers and parses untrusted JSON twice, must handle malformed
  and oversized bodies, and retains credentials in memory longer.
- **Explicit guards** — recommended: reuses the normalization the service already performs
  (`AuthServiceImpl.normalizeEmail`), and the one weakness (omission on a future endpoint) is
  coverable by a test enumerating every protected mapping.

It also flagged that send buckets must be **shared across equivalent endpoints**, or an attacker
alternates `/signup` and `/resend-otp` to double the allowance.

On limits, it judged fine-dine's IP values too aggressive as a sole layer — 5 logins per IP per 5
minutes (`fine-dine .../AuthController.java:34`) locks out "a university, office, carrier NAT, or
household" sharing one address — and proposed coarse IP limits with tight identity limits.

### 5. Defects not to inherit from fine-dine (design §3.6)

On `getClientIp` (`fine-dine .../RateLimitInterceptor.java:70`):

> That does not establish a trusted source, accepts syntactically shaped nonsense such as
> `999.999.999.999`, and excludes forwarded IPv6. A direct client can rotate XFF per request and
> receive a new bucket.

The remedy: `server.forward-headers-strategy: none` with `getRemoteAddr()` until proxy CIDRs are
documented; then `native` with explicit `internal-proxies`. `framework` is safe only when the app is
unreachable around the proxy *and* the edge strips all inbound `Forwarded`/`X-Forwarded-*`. Never
hand-parse forwarding headers, and never register a second filter alongside Boot's strategy.

Five further defects it advised against porting:

- A null Redis result silently allows the request, since only `requests != null && requests > limit`
  rejects (`RateLimitInterceptor.java:60`).
- The Lua script returns only the count, not the remaining TTL, so it **cannot** produce an accurate
  `Retry-After` (`RateLimitInterceptor.java:22`).
- It logs raw IP addresses, and logs ordinary quota exhaustion at WARN (`:61`).
- It keys by raw URI with no HTTP method or stable route pattern (`:49`).
- Its fixed-window design permits a two-window boundary burst, and its exception carries no
  reset/limit metadata.

### 6. Failure policy and enumeration (design §5.3, §6)

Fail **open** on the IP layer (a limiter outage must not take authentication down), fail **closed**
on the identity layer with `503` rather than `429`. Catch only store-connectivity failures, never
programming errors. And explicitly: **never fall back to an in-process counter** on store failure —
under multi-instance deployment that silently converts one distributed cap into one cap per replica.

On enumeration, the review supplied the anti-pattern the design now quotes: placing the guard inside
`userRepository.findByEmail(...).ifPresent(...)` means unknown accounts never charge a bucket, so the
`429` becomes a clean account-existence oracle. The guard must be charged **before** the lookup, with
key, limit, status, body and headers identical for known and unknown addresses.

It noted this does not remove the pre-existing timing difference from sending mail only to real
accounts — a non-goal — but must not add a second, easier oracle on top of it.

### 7. Modulith placement (design §4)

Split: generic infrastructure in `shared/ratelimit`, identity policy and OTP invalidation in
`modules/identity/internal/ratelimit`. Putting OTP expiry in `shared` would make shared infrastructure
depend on the identity module's internal `Otp` entity; putting the generic exception and interceptor
inside `identity/internal` would make shared exception handling depend on a feature's internals. Do
not declare `shared.ratelimit` as a new application module; keep it in the existing shared kernel and
run `CrowdtraceModulesTest` after each placement step.

## Recommendations Not Adopted

| Recommendation | Decision |
|---|---|
| A separate `otp_attempt_state` table for the attempt ledger | **Not adopted.** An OTP attempt cap *is* a rate limit — "5 guesses per 10 minutes per (email, purpose)". It reuses `rate_limit_bucket` with `action = OTP_ATTEMPT`, where `window_ends_at` serves as "blocked until". A second table would duplicate the counter, window and purge logic for no behavioural gain. The review's substantive point — that the ledger must be keyed on identity rather than on OTP row id — is fully adopted. |
| Emit `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` / `RateLimit-Policy` on `429` | **Not adopted.** These contradict the deliberately generic `429` body: `RateLimit-Policy: 5;w=600` is the attempt cap and `3;w=3600` the send bucket, so publishing them tells an attacker which control tripped and lets them tune around each. `Retry-After` alone — which the review's more important point makes *accurate*, by computing it from the returned `window_ends_at` rather than the configured window — gives a well-behaved client everything it needs. |
| Leave login charging unspecified | **Superseded.** The review set identity `LOGIN` at 5 per 5 minutes without saying whether a successful login charges the bucket. The design pins it: charge up front (keeping one atomic statement, no peek-then-write race), refund on success. Access tokens live 15 minutes with no refresh path, so counting successful logins would spend the budget on ordinary re-authentication. |

## Verification Performed

Claims were checked against the repository rather than accepted:

- **Java version** — confirmed `<java.version>21</java.version>` (`pom.xml:30`). The review was right
  to correct the brief's "Java 25".
- **Transaction boundaries** — confirmed `@Transactional` on `signUp`, `verifyOtp` and `resetPassword`,
  and that `resetPassword` throws `ValidationException` on a failed `consumeOtp`
  (`AuthServiceImpl.java:148-167`). The rollback bypass is real.
- **Resend bypass** — confirmed `generateOtp` unconditionally saves a new row and
  `findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc` reads only the newest.
- **Enumeration shape** — confirmed `resendOtp` and `resetPasswordRequest` both wrap the send in
  `.ifPresent(...)` and return `TOKEN_SENT_MSG` unconditionally (`AuthServiceImpl.java:128-145`).
- **Cited test files exist** — `TokenRevocationRaceTest`, `AccountEnumerationTest`, `ResendOtpTest`,
  `PasswordResetFlowTest`, `OtpKeyingTest` all present.
- **Exception handler shape** — confirmed every handler returns a bare `ProblemDetail` via a private
  `problem(...)` helper that sets `type`, `title`, `instance`, `code` and `correlationId`
  (`GlobalExceptionHandler.java:173-192`), and therefore cannot attach `Retry-After`.
- **Test profile** — confirmed H2 with `MODE=PostgreSQL`, `ddl-auto: create-drop` and
  `flyway.enabled: false`, and that **13 test classes** issue requests to `/api/v1/auth/*`.

## Issue Found After the Review

Recorded here because the review's Testcontainers advice pointed at it without reaching it.

The review said production persistence and concurrency tests must run on PostgreSQL because
`application-test.yaml` uses H2 with `create-drop` and Flyway disabled. It did not extend that to the
**existing** suite. Registering `IpRateLimitInterceptor` would break all 13 auth test classes at once:
`V8` never runs (Flyway off) and there is no `@Entity` for the bucket, so the table does not exist;
and H2 2.4.240 cannot execute `ON CONFLICT` regardless — the same `42000-240` error the CT-007 review
measured empirically.

Resolved in design §8.1 via a `rate-limit.enabled` property, `false` in the test profile, with
rate-limit tests opting in against Testcontainers PostgreSQL.

## Final Recommendation

**Approved.** All seven findings above are incorporated into
`docs/superpowers/specs/2026-09-25-otp-rate-limiting-design.md`; the three non-adopted
recommendations are recorded above with rationale.

The review's own closing caution is worth carrying into implementation:

> A shared distributed counter establishes the cap; OTP invalidation establishes credential state.
> Both are required because either one alone leaves a recovery or race path.

Open questions carried into the design's §7: proxy topology (ship with forwarding disabled until the
load balancer and its CIDRs are documented), the targeted-lockout denial-of-service inherent to any
attempt cap, starting limit values pending telemetry, and the new required `RATE_LIMIT_HMAC_SECRET`
production secret.
