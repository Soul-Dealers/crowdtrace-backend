## Unreleased

- CT-020: added a central transactional audit recorder with a closed action vocabulary, schema-validated metadata,
  per-operation audit enforcement, and database-enforced append-only audit events. Verification approve, reject,
  revoke, and grant are audited; revocation preserves approval attribution and adds `revokedAt` and
  `revocationNotes` to the admin response and OpenAPI contract. Correlation IDs are normalized. Adds
  `spring-boot-starter-aspectj`.
- CT-020 (PR #51 review): `case_reviews` is append-only and `content_reports` only moves `OPEN → RESOLVED`, never
  deleted, with `resolved_at >= created_at`. The audit metadata DB check is an 8 KB backstop behind the app's 4 KB limit.
  `review_source` defaults to `HUMAN`; three unused governance indexes are dropped. Every timestamp default is UTC.

- CT-016: governance schema — human-only case review history with required rejection notes, structured PII-minimized
  audit events with explicit system actors, and attributable comment reports (one open report per user per comment).
  Schema and query tests only; comment FK lands with CT-025.

- CT-014: deterministic normalized-name and last-seen duplicate detection after submission commits,
  directional match metadata without name snapshots, and a review flag on the new case only.
  Configurable validated thresholds, concurrent/idempotent detection and PII-safe failure logging;
  detection failures cannot reject intake. Release committed intake connections before detection
  to prevent connection-pool starvation during concurrent submissions. No new dependencies or admin API changes.

- CT-013: authenticated report/photo uploads with per-user quotas and atomic validated case submission,
  current-version consent, immediate minor priority, safe responses and a non-blocking submitted-case event.
- CT-012: case entities, repositories, and public/reporter/admin projections behind CaseQueryService.
- CT-011: case registry schema (cases, sensitive details, case files, consents) with migration and query tests.

## 2026-09-29 — Add CT-009 authentication and current-user endpoints
- Collapsed the duplicate `/api/auth` controller mapping onto the canonical `/api/v1/auth` prefix and
  extended the canonical-path guard to cover `/me`, `/logout`, and `/profile-settings`.
- Replaced the planned `/api/auth/register`, `/api/auth/login`, and `/api/auth/refresh` OpenAPI
  placeholders with the shipped endpoints, tagged under `Authentication`, and documented every
  unauthenticated auth endpoint as public instead of inheriting the bearer requirement.
- Added the pseudonym-and-badge `PublicUserResponse` projection, carrying the badge type the spec
  lists as a public account field, as the seam other modules render authors through.
- Resolved the badge per verification type from the most recent decision, so a revocation withdraws
  the badge and a rejected second application cannot strip a badge an administrator never revoked.
- Recorded the token lifecycle in ADR-002: short-lived access tokens, logout revocation, and
  credentials-version invalidation, with no refresh token.
- Added MockMvc coverage for registration and the successful-login-resolves-current-user path.

## 2026-09-23 — Add CT-008 role-based method authorization
- Added runtime-retained registered-user, moderator, and Super Admin method authorization annotations.
- Enabled Spring method security, protected user listing for Super Admins, and permitted the public API namespace.
- Added the complete JWT-backed authorization matrix, authority-source, account-lifecycle, and OpenAPI regression coverage.

## 2026-09-13 — Add CT-006 identity persistence
- Replaced the starter user migration with the initial `users` and `verification_requests` schema.
- Kept numeric user IDs and renamed the stored credential column to `password_hash`.
- Added explicit role, account-status, verification-type, and verification-status enums with email,
  role, ownership, and pending-queue repository queries.
- Protected private identity and verification fields from JSON projections and documented the safe
  public display-name-only user response.

## 2026-08-21 — Add Compose-compatible application and Postgres containers
- Replaced the placeholder Dockerfile with a Java 21 multi-stage application image build.
- Added a Compose application service and Postgres service wired through datasource environment credentials.
- Added Docker build-context exclusions and a safe environment example.

## 2026-09-11 — Add health probes and clear foundation test blockers
- Added Actuator liveness/readiness endpoints, with database health included only in readiness.
- Allowed unauthenticated access to the two health probe routes while keeping other routes protected.
- Corrected Modulith module expectations, generic exception test coverage, and the `UserRepository` ID type.
- Added HTTP health endpoint and repository regression tests.

## 2026-09-13 — Add current structured logging safeguards
- Enabled Spring Boot ECS JSON console logging so the existing MDC correlation ID is emitted with log events.
- Removed the current exception-handler paths that emitted raw exception messages and stack traces.
- Added focused tests for structured output, correlation IDs, and exception-detail suppression.
