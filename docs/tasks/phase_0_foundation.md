# Phase 0 — Project Foundation

**Goal:** Make the Spring Boot backend runnable, reproducible, testable, and consistent before
domain code is introduced.

**Five Questions:**

- **Q1:** Developers can start the API, run migrations, and understand every response shape.
- **Q2:** Secrets are not committed; environments cannot silently auto-create production schema; errors are stable.
- **Q3:** Configuration, persistence setup, web support, and test support own these rules.
- **Q4:** A clean checkout starts, migrates, answers health checks, and passes a representative API error test.
- **Q5:** Do not implement case, identity, moderation, or notification behavior in this phase.

## Tasks

### CT-001 — Establish Spring Boot foundation and dependency baseline

- **Labels:** `epic:foundation`, `type:infra`, `priority:p0`
- **Depends on:** None
- **Issue:** Keep Java 21 and Spring Boot 4.1.0 as the baseline. Add Web MVC, JPA, Security,
  Bean Validation, PostgreSQL, Flyway, S3, email, and OpenAPI dependencies. Establish the
  package structure described in the blueprint and verify the application boots.
- **Acceptance criteria:** The app starts with the local profile; dependencies are explicit;
  package structure is documented; the context-load test passes.
- **Tests:** Context-load test and a clean `./mvnw test` run.

### CT-002 — Configure PostgreSQL, Flyway, profiles, and local Compose

- **Labels:** `epic:foundation`, `type:infra`, `priority:p0`
- **Depends on:** CT-001
- **Issue:** Configure database access from environment variables, connection pooling, Flyway,
  local/test/staging/production profiles, and a Docker Compose PostgreSQL service. Disable
  schema auto-generation outside explicitly local development.
- **Acceptance criteria:** A clean database can be migrated from zero; test configuration is
  isolated; missing required production database configuration fails fast.
- **Tests:** Migration integration test against PostgreSQL and profile configuration test.

### CT-003 — Add API error, validation, pagination, CORS, and correlation conventions

- **Labels:** `epic:foundation`, `type:feature`, `priority:p0`
- **Depends on:** CT-001
- **Issue:** Add `@ControllerAdvice`, a stable error envelope, validation-error mapping,
  authentication/authorization/not-found/lifecycle errors, pagination conventions, CORS policy,
  request limits, and correlation IDs.
- **Acceptance criteria:** Invalid requests return the documented error shape; paginated responses
  use one convention; every request can be correlated in logs; CORS is allowlist-based.
- **Tests:** MockMvc tests for validation, not-found, forbidden, lifecycle, pagination, and CORS.

### CT-004 — Add health/readiness, structured logging, and test infrastructure

- **Labels:** `epic:foundation`, `type:infra`, `type:test`, `priority:p0`
- **Depends on:** CT-002, CT-003
- **Issue:** Add liveness/readiness endpoints, structured Logback logging, a test profile,
  reusable database fixtures, and integration-test support for PostgreSQL.
- **Acceptance criteria:** Liveness does not require external dependencies; readiness reports
  database health; logs include request IDs and omit secrets; integration tests run reproducibly.
- **Tests:** Health endpoint tests, log redaction test, and one repository integration test.

### CT-005 — Define the initial OpenAPI contract and repository workflow

- **Labels:** `epic:foundation`, `type:docs`, `priority:p1`
- **Depends on:** CT-003
- **Issue:** Add API metadata, error schemas, authentication placeholders, pagination schemas,
  and contribution guidance for endpoint/version changes. Document local setup and verification
  commands.
- **Acceptance criteria:** OpenAPI renders locally; every foundation endpoint is documented;
  README setup instructions work from a clean checkout; breaking contract changes require review.
- **Tests:** OpenAPI generation/build check.

## Verification

- `./mvnw test`
- Clean PostgreSQL migration from an empty database
- Liveness/readiness and representative error responses verified
