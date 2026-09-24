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
