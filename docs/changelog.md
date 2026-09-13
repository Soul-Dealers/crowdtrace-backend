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
