## Unreleased

- CT-021: added public `GET /api/public/cases` search for approved cases, ordered by approval time and id
  descending. Filters cover literal, case-insensitive names (3–100 characters after trimming), region, gender,
  inclusive age bounds (0–130), case statuses, inclusive last-seen dates, and inclusive approval dates;
  all four date bounds accept years 0001–9999. Omitted statuses include all four outcomes; an explicitly
  empty status filter is invalid. Pagination defaults to page 0 and size 20, caps size at 50, fixes ordering,
  and rejects offsets at or beyond 10,000. Results expose 11 public summary fields: id, full name, age,
  gender, region, last-seen date, status, presentation, approval time, resolution time, and thumbnail photo id.
  Deceased summaries carry `DECEASED_NOTICE`, omit withheld fields, and can retain an eligible thumbnail.
  A thumbnail is the earliest live PUBLIC PHOTO by upload time then id; cases without one have a null value.
  V20 adds `pg_trgm` and a `lower(full_name)` GIN index, plus review-status-leading B-tree indexes. These
  are non-partial because Hibernate's generic enum binding cannot prove a partial-index predicate. Query-plan
  proof uses 60 representative fixtures and a varied 10,000-row background population with strict allowed-
  index checks; performance targets remain CT-024. Handoffs: CT-022 reuses `toPublic` and returns 404 for
  non-approved cases; CT-023 serves `thumbnailPhotoId` only for PUBLIC photos of APPROVED cases; CT-024 owns
  pilot-sized performance verification.

- CT-018: case lifecycle on approved cases. Reporters (`/api/user/cases/{id}`) read their case with `version`,
  move it from `MISSING` to `FOUND_SAFE`/`FOUND_DECEASED`/`CLOSED`, set a closing statement on a resolved case and
  add photos while it is `APPROVED`. Admins (`/api/v1/admin/cases/{id}`) correct any outcome (including reopening to
  `MISSING`, which clears `resolved_at`, `closed_at` and the statement), set statements, take a case down with a
  private reason and restore it. Admins cannot take down or restore a case they reported (403). Every mutation locks
  the case, checks `expectedCaseVersion`, records its `CASE.*` audit event (new `CASE.RESTORED`) and publishes an
  ids-only `notification` event (`CaseStatusChangedEvent`, `CasePhotosAddedEvent`, `CaseTakenDownEvent`,
  `CaseRestoredEvent`) in one transaction. Photo audits are enforced per file through `LivePhotoAuditor`; a reopen
  that clears a statement is enforced through `ClosingStatementClearer`. `FOUND_DECEASED` public projections carry
  `presentation = DECEASED_NOTICE` and null the last-seen location, physical description, clothing, circumstances
  and contact number. V19 adds `takedown_reason`/`taken_down_by`/`taken_down_at` and checks that each case's
  columns match its review state and outcome. Restore keeps the latest takedown columns; a second takedown overwrites
  them. Nullable OpenAPI 3.1 fields use `type` unions.
  Handoffs: CT-021/022 must use the public mapper (deceased withholding) and keep the `APPROVED` filter; CT-023
  must refuse files of a taken-down case; CT-026 blocks new follows on non-approved cases and keeps follow rows
  across restore; CT-027 consumes the four events (reporter-only for takedown/restore, never the reason); CT-028
  emails the reporter on `CaseStatusChangedEvent`; CT-032 keys retention off `resolved_at`/`closed_at`. Live-case
  field edits and police closing statements remain out of scope.

- CT-017: moderator review queue and decisions under `/api/v1/admin/review/cases` (filtered queue, detail with
  duplicate matches and review history, approve, reject). A decision locks the case, checks state, version and
  report, appends an immutable `case_reviews` row, records `CASE.APPROVED`/`CASE.REJECTED` and publishes
  `CaseReviewDecidedEvent` in one transaction; self-review returns 403; a stale `expectedCaseVersion` or an already-decided case returns 409.
  Attaching files and adding duplicate matches now bump the case version; case timestamps use the UTC clock.
  `AdminCaseResponse` gains `version`. Review detail lists evidence as metadata only (`accessAvailable = false`);
  signed access (CT-023/030) and in-app delivery (CT-027) must land before moderator rollout. Retires the
  `/api/admin/cases/review` and `/decision` OpenAPI placeholders.
- CT-017 (implementation rulings): the casefile port `CaseReviewMutationService.decide` takes `Long` case ids and
  runs the self-review check under the case lock (403, then state, version, report as 409). Submission commits at version 1 (the attach bump); duplicate detection may raise it. Clients must send caseDetails.version. Review detail uses a first-statement `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY` inside
  `REQUIRES_NEW`, because the DELAYED_ACQUISITION handling mode rejects `@Transactional` isolation. The detail view
  hides the admin `files` field and shows files as evidence.
  Handoff (CT-018/CT-023): any future path that removes or replaces case evidence must lock the case and
  force-increment its version, otherwise the approval report check and the stale-version check stop holding.

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
