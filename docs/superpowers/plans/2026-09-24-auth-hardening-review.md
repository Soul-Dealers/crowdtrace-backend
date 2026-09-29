# Auth Hardening Plan — Revised Review

**Reviewed plan:** `docs/superpowers/plans/2026-09-24-auth-hardening.md`  
**Review date:** 2026-09-24  
**Verdict:** Conditionally approved — fix the Task 11 database/test mismatch before implementation

## Executive Summary

The revised plan now makes sense as an auth-hardening implementation plan. It addresses all five blockers from the first review and is materially more precise about transaction boundaries, endpoint contracts, migrations, error responses, and end-to-end verification.

The plan is not quite executable unchanged. Task 11 deliberately uses PostgreSQL-specific `INSERT ... ON CONFLICT DO NOTHING`, but its proposed `TokenRevocationRaceTest` runs under the normal test profile, which uses H2. H2 2.4.240 rejects that SQL even with `MODE=PostgreSQL`, so the test—and therefore the full suite—will fail after the implementation is added.

Fix that mismatch and the plan is ready to execute. The remaining observations are improvements that can be handled while implementing the affected tasks; they do not require another redesign.

## What the Revision Fixed

### 1. OTP storage now addresses the actual offline-attack threat

The plan replaces plain SHA-256 with HMAC-SHA-256 using a dedicated OTP secret and includes normalized email, OTP purpose, and code in the authenticated input. This prevents an attacker who only obtains the database from recovering six-digit codes with a one-million-value lookup table, while also separating codes by identity and purpose.

It also now:

- Returns plaintext only at minting time and persists only the keyed digest.
- Makes `consumeOtp` transactional, correctly owning the pessimistic-lock boundary.
- Updates `AuthServiceImplTest` for the changed service contract.
- Tests one-time consumption and rejects replay of the stored digest.
- Documents the production secret as a deployment prerequisite.

### 2. Endpoint canonicalization and OpenAPI changes are correctly ordered

The root aliases are removed before the OpenAPI contract task. The plan now verifies absence through Spring's handler mappings rather than assuming an unauthenticated request can reach MVC and return 404. It also explicitly updates `OpenApiContractTest` to the canonical `/api/v1/auth/*` paths.

### 3. Credential invalidation is exact

The timestamp design has been replaced by `credentials_version`. Tokens carry the signed version and authentication succeeds only when it matches the current user record. Resetting a password increments the version, avoiding clock precision, timezone, and same-second boundary errors.

The plan also adds the previously missing migration/test updates and a real reset-then-login lifecycle test.

### 4. Security error responses preserve observability

The custom authentication entry point now includes the correlation ID, and the correlation filter is explicitly ordered early enough for security-filter failures to use it.

### 5. Schema and cleanup work is concrete

The plan discovered and addresses the missing `users.deleted_at` migration. A schema-validation boot test is included, OTP cleanup uses bulk delete queries, and the expiration column receives an index.

### 6. Production limitations are stated honestly

Rate limiting and OTP attempt caps remain outside this tranche, but the revised plan now treats them as production blockers rather than implying this work alone makes authentication production-ready.

## Blocking Correction

### Task 11 mixes PostgreSQL SQL with the H2 test profile

Task 11 adds this native repository operation:

```sql
insert into revoked_tokens (token_hash, expires_at)
values (?, ?)
on conflict (token_hash) do nothing
```

That is a good production design for idempotent revocation on PostgreSQL. However, `TokenRevocationRaceTest` is annotated with `@ActiveProfiles("test")`, and `application-test.yaml` configures H2 with PostgreSQL compatibility mode. H2 2.4.240 still reports a syntax error for this form of `ON CONFLICT`.

Use one of these resolutions:

1. **Preferred:** run the native-query integration test against PostgreSQL using Testcontainers. This verifies the same SQL and concurrency semantics used in production.
2. Provide a test-only, dialect-specific repository implementation. This keeps the ordinary H2 suite but means the H2 test no longer validates the production statement itself.
3. Replace the native upsert with a portable transactional design, using a genuinely isolated insert transaction. Do not catch a uniqueness exception inside the same ambient transaction, because the transaction can already be rollback-only.

The preferred correction should be added directly to Task 11's file list, setup, and verification commands. A PostgreSQL-backed integration test can assert both repeated and concurrent calls deterministically.

The proposed red phase is also probabilistic: increasing concurrency until the old check-then-insert implementation happens to fail is not a reliable test gate. It is enough to prove the final database operation is idempotent under coordinated concurrent calls on PostgreSQL; the test does not need to reproduce the historical race nondeterministically first.

## Non-Blocking Improvements

### Make the OTP domain-separation test prove what its name claims

`theSameCodeForADifferentEmailProducesADifferentDigest` currently mints a code for one email and attempts to consume it for an email with no OTP row. That failure is expected regardless of whether the digest includes the email, so it does not prove domain separation.

Inject a deterministic code generator, or make the digest helper package-private and test it directly. Assert that the same fixed code produces different digests for:

- Two normalized email addresses.
- `CREATE` versus `RESET` for the same email.

### Validate the OTP HMAC secret at startup

The proposed `@Value String` accepts an empty or weak secret. Use validated configuration properties and reject a missing or undersized key at startup. Prefer at least 32 bytes of random key material. If the environment value is Base64, decode it explicitly; otherwise name and document it as a raw secret so operators are not misled by Base64-looking sample values.

### Fail closed for unexpected principal implementations

`credentialsVersionMatches` currently returns `true` when `UserDetails` is not `SecurityUser`. The current loader returns `SecurityUser`, but a security invariant should not silently disappear if another authentication path is introduced. Return `false` for an unexpected principal type, or make the filter's dependency on `SecurityUser` explicit.

Add a focused test proving that an otherwise valid legacy token with no `cv` claim is rejected. The prose promises this behavior, but the shown tests do not isolate it.

### Avoid exception-message logging in the JWT filter

Logging `exception.getMessage()` can expose account identifiers or parser detail. Log a stable reason/category at debug level and keep the response generic.

### Clarify the global test-harness rule

`SchemaValidationTest` intentionally needs only a context boot, not MockMvc or a mocked notification service. Mark schema-only context tests as an explicit exception to the plan's global Spring test-harness convention.

## Verification Performed

- The original baseline suite passed: **93 tests, 0 failures, 0 errors**.
- The revised plan was compared against the current auth services, repositories, filter chain, migrations, application test profile, and contract/lifecycle tests.
- Running Hibernate schema validation against the current migrations fails on missing `users.deleted_at`, confirming that Task 8 addresses a real schema drift rather than speculative work.
- Executing the planned `INSERT ... ON CONFLICT DO NOTHING` statement against the project's H2 2.4.240 dependency in PostgreSQL mode produces SQL error `42000-240`, confirming the Task 11 incompatibility.

## Final Recommendation

**Approve after one targeted revision:** make Task 11's native PostgreSQL upsert test run on PostgreSQL, preferably with Testcontainers.

After that change, implementation can begin. Apply the non-blocking improvements during their respective tasks, and keep rate limiting, OTP attempt caps, production secret management, and signing-key rotation as release gates before describing authentication as production-ready.
