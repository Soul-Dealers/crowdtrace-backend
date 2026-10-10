# CT-016 — Review, Audit, and Comment-Report Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/16
**Phase:** 3 — Review, Moderation, Lifecycle, and Audit
**Depends on:** CT-011 (`cases`, `users`)
**Unblocks:** CT-017 (review decisions), CT-018 (lifecycle audit), CT-019 (comment reports), CT-020 (audit recorder), CT-025 (adds the `comment_id` FK)

**Goal:** One Flyway migration that gives phase 3 its three governance records: a case review decision history, a structured, PII-minimized audit event log, and comment reports with attributable resolution. Constraints, indexes and the queue queries are proven against real PostgreSQL.

**Architecture:** `V14__create_governance_tables.sql` creates `case_reviews`, `audit_events` and `content_reports`. No Java production code: entities, repositories and services belong to the tasks that own the behaviour (CT-017 reviews, CT-019 reports, CT-020 `AuditEvent` + recorder). Tests drive the schema through `JdbcTemplate`, as CT-011 did.

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway, PostgreSQL 16, Testcontainers, JUnit 5, AssertJ. No new dependencies.

**Spec:** `docs/product-spec.md` §3.3 (L96–102 review + future AI source), §4.4 / US-06 (L120–130, L499–511), data model (L253–272), §6 audit (L292), retention (L306, L317); `docs/tasks/phase_3_governance.md` CT-016–CT-020; `docs/blueprint.md` Step 4.1, 4.4, 6.1.

---

## Decisions

Designed by Claude and Codex ("Astra", gpt-6-astra) independently, then reconciled. D9–D12 settled by Edem. No open disagreements.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Schema only. No entities, repositories or recorder** | CT-011 precedent (its D1): the issue's "queue-query tests" are met by `GovernanceQueryTest` running the exact SQL the future repositories will issue. `shared/audit/AuditEvent.java` stays an empty placeholder for CT-020, which owns the recorder, action vocabulary and redaction (Edem: audit is its own service). Mapping now would pre-empt CT-017/019/020 design. |
| D2 | **One migration, `V14__create_governance_tables.sql`** | The three tables are one deliverable with no ordering dependency between them. V12 grouped four tables the same way. Blueprint names `V7/V8/V9` are stale and discarded. |
| D3 | **`case_reviews` is an append-only decision history, not the queue** | One row per human decision. `case_id` is not unique (a case can be reviewed again after a future resubmission or correction). The queue stays on `cases` with the existing `idx_cases_review_queue (review_status, priority_minor DESC, submitted_at)`. Duplicate-flag/date filter indexes wait until CT-017 fixes the query shapes, so we don't index guesses. |
| D4 | **`decision IN ('APPROVED', 'REJECTED')`** | Matches the review states that exist on `cases`. Takedown is **not** a review decision: it is a CT-018 lifecycle action recorded in `audit_events`, so the original approval row is never overwritten. |
| D5 | **`review_source NOT NULL CHECK (review_source = 'HUMAN')`** | Keeps the PRD's extensible source field (L102) while making a non-human decision unstorable (Q5). A future AI source needs a deliberate migration that also decides whether it can be advisory-only. A second "decision ⇒ HUMAN" constraint is redundant today. `FUTURE_AI` from the blueprint is discarded. The constraint does not prove moderator authorization; CT-017 enforces the role. |
| D6 | **Rejection requires non-blank notes; notes capped at 2000** | `CHECK (decision <> 'REJECTED' OR (review_notes IS NOT NULL AND btrim(review_notes) <> ''))`. Spelled out because `btrim(NULL) <> ''` is NULL and would pass a CHECK. 2000 matches `verification_requests.review_notes`. Notes are admin-only. |
| D7 | **`audit_events` shape** — `actor_id` (nullable FK users, no cascade), `actor_role` snapshot, `action`, `target_type`, `target_id`, `metadata JSONB`, `correlation_id`, `occurred_at` | Attributable without copying personal data: no email, name, IP, user agent or free-text column. The role is snapshotted because roles change (CT-034). The target is polymorphic with **no FK**: audit must outlive and span every entity type, and CT-032 deletes sensitive fields/files, not case rows, so no audit history needs deleting. |
| D7a | **System actors are explicit:** `actor_role` includes `SYSTEM`, and `CHECK ((actor_id IS NULL) = (actor_role = 'SYSTEM'))` | "No actor" can never be confused with "lost the user". Retention jobs (CT-033) record as `SYSTEM` and correlate runs through `correlation_id`. |
| D7b | **Shape, not vocabulary, is enforced in SQL** — `action` and `target_type` must match `^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)*$`, e.g. `CASE.APPROVED` | The vocabulary belongs to CT-020's Java enum. A format CHECK stops free text (and stray PII) from landing in the column without a migration per new action. |
| D7c | **`metadata` is a non-null JSON object, at most 4 KB** — `jsonb_typeof(metadata) = 'object'` and `octet_length(metadata::text) <= 4096` | "Structured" is enforced. The size cap blocks dumping whole request bodies or files into audit. The per-action key allowlist and redaction are CT-020's (an object check alone cannot minimize PII). |
| D7d | **`correlation_id VARCHAR(64)`, nullable** | `CorrelationIdFilter` accepts arbitrary caller header text (`shared/config/CorrelationIdFilter.java:30`). CT-020 must normalize/bound it before storage; the column bound is the backstop. |
| D7e | **No append-only trigger in CT-016** | The policy for correcting accidentally recorded sensitive content isn't defined, and CT-020 owns "required state changes cannot succeed without an audit event". CT-020 decides whether to add an UPDATE/DELETE guard. Handed off explicitly. |
| D8 | **Audit indexes:** `(target_type, target_id, occurred_at, id)`, `(actor_id, occurred_at, id) WHERE actor_id IS NOT NULL`, `(action, occurred_at, id)`, `(occurred_at, id)` | Matches CT-035's audit search by entity, actor, action and date (blueprint L614). `id` breaks timestamp ties so pagination is stable. |
| D9 | **`content_reports.comment_id BIGINT NOT NULL` with no FK yet; CT-025 adds it** | Edem. The `comments` table is CT-025's. Polymorphic targets are speculative, creating `comments` here steals CT-025, and deferring the table drops issue scope. **Named gap:** until CT-025 runs `ALTER TABLE content_reports ADD CONSTRAINT fk_content_reports_comment FOREIGN KEY (comment_id) REFERENCES comments (id)`, a report can name a non-existent comment. No endpoint can write reports before CT-019, which depends on CT-025, so the gap is unreachable in production. |
| D10 | **One OPEN report per (comment, reporter)**: partial unique index `WHERE status = 'OPEN'` | Edem. CT-019 returns the existing open report on a repeat. After a resolution the user may report again. The index is also the race backstop for concurrent double-submits. |
| D11 | **Removing a comment resolves all its open reports together** | Edem. CT-019 does it in one transaction, attributed to the removing moderator. The schema supports it through `idx_content_reports_open_comment`. |
| D12 | **`REQUEST_CHANGES` deferred** | Edem. It needs a matching `cases.review_status` and a reporter edit/resubmit flow. It is added with its own migration if ever wanted. |
| D13 | **Report status = `OPEN` \| `RESOLVED`, outcome = `resolution` `REMOVED` \| `DISMISSED`** | Two small vocabularies instead of compound statuses. One CHECK pins attribution: `OPEN` ⇒ `resolution`, `moderator_id`, `resolution_notes`, `resolved_at` all NULL; `RESOLVED` ⇒ `resolution`, `moderator_id`, `resolved_at` NOT NULL. Resolution notes are optional (US-06 records a note on removal; CT-019 may require it for `REMOVED`). |
| D14 | **Report reason = category + optional details**: `reason IN ('SPAM', 'HARASSMENT', 'MISINFORMATION', 'PRIVACY', 'OTHER')`, `details VARCHAR(1000)` required (non-blank) only for `OTHER` | Categories make the queue sortable and countable (PRD L347 metrics) and keep free text, a common way PII leaks in, to the one case that needs it. CT-019 may rename categories with a migration. |
| D15 | **Report queue index:** `(created_at, id) WHERE status = 'OPEN'` plus `(comment_id) WHERE status = 'OPEN'` | Oldest-first open queue and per-comment grouping/bulk resolution (D11). `status` is redundant inside a partial index. |
| D16 | **`comments.report_count` denormalization is discarded for now** | Counts are derived from `content_reports` (indexed). CT-025 may add the counter only against a measured need. |
| D17 | **Timestamps are `TIMESTAMP WITHOUT TIME ZONE`, IDs are `BIGINT` identity** | Matches V1–V13 and the `LocalDateTime` mappings already in use. |

**Concurrency handoffs (not schema):** CT-017 must update `cases`, insert `case_reviews` and record audit in one transaction under the existing case lock/`@Version` (`CaseRecordRepository.java:34`). CT-019 resolves with a guarded `UPDATE … WHERE status = 'OPEN'` so two moderators can't both resolve.

**Discarded:** blueprint migration numbers V7/V8/V9, `FUTURE_AI`, `REQUEST_CHANGES`, `comments.report_count`, a V13→V14 data-preservation test (V14 only creates new tables).

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V14__create_governance_tables.sql` | Create | Three tables, constraints, indexes |
| `src/test/java/com/souldealers/crowdtracebackend/GovernanceMigrationTest.java` | Create | Constraint and index tests |
| `src/test/java/com/souldealers/crowdtracebackend/GovernanceQueryTest.java` | Create | Queue/history query contracts |
| `docs/changelog.md` | Modify | Unreleased entry |

Tests copy the `CaseMigrationTest` harness exactly (`@SpringBootTest` with Flyway on, `ddl-auto=none`, `postgres:16-alpine` Testcontainer, `user(...)`/`caseFor(...)` JDBC helpers). Local runs need the OrbStack socket env vars (see memory).

---

### Task 1: V14 migration with schema-level tests

**Files:** create the migration and `GovernanceMigrationTest`.

- [ ] **Step 1: Write failing tests** in `GovernanceMigrationTest`. Each constraint test inserts one violating row and asserts `DataIntegrityViolationException`; each happy path asserts the row persists.

  `case_reviews`
  - `approvedReviewWithoutNotesPersists`
  - `rejectionRequiresNonBlankNotes`: NULL, `''` and `'   '` all fail
  - `decisionVocabularyIsPinned`: `TAKEN_DOWN` and `REQUEST_CHANGES` fail
  - `onlyHumanSourceIsAccepted`: `AI` fails
  - `reviewRequiresExistingCaseAndReviewer`: FK violations
  - `aCaseMayHaveSeveralReviews`: two rows for one case persist

  `audit_events`
  - `userActorEventPersists`, `systemEventHasNoActor`
  - `actorAndSystemRoleMustAgree`: NULL actor with `MODERATOR` fails; a user id with `SYSTEM` fails
  - `actionAndTargetTypeMustBeUpperDotted`: `'case approved'`, `'Case.Approved'`, `''` fail; `CASE.APPROVED` passes
  - `metadataMustBeAJsonObject`: `'[]'`, `'"x"'`, `'null'` fail; omitted defaults to `{}`
  - `metadataIsCappedAt4Kb`
  - `correlationIdIsBounded`: 65 chars fail

  `content_reports`
  - `openReportPersists`
  - `otherReasonRequiresDetails`, `reasonVocabularyIsPinned`
  - `oneOpenReportPerReporterAndComment`: second OPEN fails; after resolving the first, a new OPEN persists
  - `resolvedReportMustBeAttributed`: RESOLVED without moderator / resolution / resolved_at fails
  - `openReportCarriesNoResolution`: OPEN with any resolution field fails
  - `commentIdHasNoForeignKeyYet`: a non-existent comment id persists (pins D9 so CT-025 must change this test when it adds the FK)

  Indexes
  - `governanceIndexesExist`: query `pg_indexes` for every name in Step 3

- [ ] **Step 2: Run, expect failure** (`relation "case_reviews" does not exist`).

  ```bash
  ./mvnw test -Dtest=GovernanceMigrationTest
  ```

- [ ] **Step 3: Write the migration**

  ```sql
  -- Governance records (CT-016).
  --
  -- case_reviews: append-only human decision history. The review queue itself stays on
  -- cases (idx_cases_review_queue). Takedown is a lifecycle action, recorded in audit_events.
  -- audit_events: who did what to which target. No personal data columns; the action
  -- vocabulary, metadata allowlist and the recorder are CT-020's.
  -- content_reports: comment reports. comment_id gets its FK in CT-025, which creates comments.

  CREATE TABLE case_reviews
  (
      id            BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
      case_id       BIGINT                      NOT NULL,
      reviewer_id   BIGINT                      NOT NULL,
      decision      VARCHAR(32)                 NOT NULL,
      review_source VARCHAR(32)                 NOT NULL,
      review_notes  VARCHAR(2000),
      created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
      CONSTRAINT pk_case_reviews PRIMARY KEY (id),
      CONSTRAINT fk_case_reviews_case FOREIGN KEY (case_id) REFERENCES cases (id),
      CONSTRAINT fk_case_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id),
      CONSTRAINT case_reviews_decision_check CHECK (decision IN ('APPROVED', 'REJECTED')),
      -- Only a human decides. A future AI source needs its own migration and rules.
      CONSTRAINT case_reviews_source_check CHECK (review_source = 'HUMAN'),
      -- btrim(NULL) is NULL, so the NULL case is spelled out.
      CONSTRAINT case_reviews_rejection_notes_check CHECK (
          decision <> 'REJECTED' OR (review_notes IS NOT NULL AND btrim(review_notes) <> ''))
  );

  CREATE INDEX idx_case_reviews_case ON case_reviews (case_id, created_at, id);
  CREATE INDEX idx_case_reviews_reviewer ON case_reviews (reviewer_id, created_at, id);

  CREATE TABLE audit_events
  (
      id             BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
      actor_id       BIGINT,
      actor_role     VARCHAR(32)                 NOT NULL,
      action         VARCHAR(64)                 NOT NULL,
      target_type    VARCHAR(32)                 NOT NULL,
      target_id      BIGINT                      NOT NULL,
      metadata       JSONB                       NOT NULL DEFAULT '{}'::jsonb,
      correlation_id VARCHAR(64),
      occurred_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
      CONSTRAINT pk_audit_events PRIMARY KEY (id),
      -- RESTRICT: users are soft-deleted (V4); audit must never lose its actor.
      CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_id) REFERENCES users (id),
      CONSTRAINT audit_events_actor_role_check CHECK (
          actor_role IN ('REGISTERED_USER', 'MODERATOR', 'SUPER_ADMIN', 'SYSTEM')),
      CONSTRAINT audit_events_system_actor_check CHECK ((actor_id IS NULL) = (actor_role = 'SYSTEM')),
      CONSTRAINT audit_events_action_format_check CHECK (
          action ~ '^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)*$'),
      CONSTRAINT audit_events_target_type_format_check CHECK (
          target_type ~ '^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)*$'),
      CONSTRAINT audit_events_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object'),
      CONSTRAINT audit_events_metadata_size_check CHECK (octet_length(metadata::text) <= 4096)
  );

  CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id, occurred_at, id);
  CREATE INDEX idx_audit_events_actor ON audit_events (actor_id, occurred_at, id) WHERE actor_id IS NOT NULL;
  CREATE INDEX idx_audit_events_action ON audit_events (action, occurred_at, id);
  CREATE INDEX idx_audit_events_occurred ON audit_events (occurred_at, id);

  CREATE TABLE content_reports
  (
      id               BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
      comment_id       BIGINT                      NOT NULL,
      reporter_id      BIGINT                      NOT NULL,
      reason           VARCHAR(32)                 NOT NULL,
      details          VARCHAR(1000),
      status           VARCHAR(32)                 NOT NULL DEFAULT 'OPEN',
      resolution       VARCHAR(32),
      moderator_id     BIGINT,
      resolution_notes VARCHAR(2000),
      created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
      resolved_at      TIMESTAMP WITHOUT TIME ZONE,
      CONSTRAINT pk_content_reports PRIMARY KEY (id),
      CONSTRAINT fk_content_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
      CONSTRAINT fk_content_reports_moderator FOREIGN KEY (moderator_id) REFERENCES users (id),
      CONSTRAINT content_reports_reason_check CHECK (
          reason IN ('SPAM', 'HARASSMENT', 'MISINFORMATION', 'PRIVACY', 'OTHER')),
      CONSTRAINT content_reports_other_details_check CHECK (
          reason <> 'OTHER' OR (details IS NOT NULL AND btrim(details) <> '')),
      CONSTRAINT content_reports_status_check CHECK (status IN ('OPEN', 'RESOLVED')),
      CONSTRAINT content_reports_resolution_check CHECK (resolution IN ('REMOVED', 'DISMISSED')),
      -- An open report carries no outcome; a resolved one always says who, what and when.
      CONSTRAINT content_reports_attribution_check CHECK (
          (status = 'OPEN' AND resolution IS NULL AND moderator_id IS NULL
              AND resolution_notes IS NULL AND resolved_at IS NULL)
          OR (status = 'RESOLVED' AND resolution IS NOT NULL AND moderator_id IS NOT NULL
              AND resolved_at IS NOT NULL))
  );

  -- One open report per user per comment; re-reporting is allowed after resolution.
  CREATE UNIQUE INDEX uq_content_reports_open_reporter
      ON content_reports (comment_id, reporter_id) WHERE status = 'OPEN';
  -- Moderator queue, oldest first.
  CREATE INDEX idx_content_reports_open_queue ON content_reports (created_at, id) WHERE status = 'OPEN';
  -- Group by comment and resolve every open report on a removed comment.
  CREATE INDEX idx_content_reports_open_comment ON content_reports (comment_id) WHERE status = 'OPEN';
  CREATE INDEX idx_content_reports_reporter ON content_reports (reporter_id);
  ```

- [ ] **Step 4: Run, expect pass.** Then run `SchemaValidationTest`, `CaseMigrationTest` and `CrowdtraceModulesTest` to confirm nothing else moved.

- [ ] **Step 5: Commit** `feat(governance): add review, audit and comment-report schema [CT-016]`

---

### Task 2: Queue and history query tests

**Files:** create `GovernanceQueryTest` (same harness, `@Transactional` per test like `CaseQueryTest`). Each test runs the literal SQL the owning task's repository will issue. Paste that SQL into the repository later unchanged.

- [ ] **Step 1: Write the tests**
  - `reviewHistoryForCaseIsChronologicalWithIdTieBreak` (CT-017 detail view): `SELECT … FROM case_reviews WHERE case_id = ? ORDER BY created_at, id`. Two rows with equal `created_at` come back in id order; other cases' rows are excluded.
  - `openReportQueueIsOldestFirstAndExcludesResolved` (CT-019 queue): `WHERE status = 'OPEN' ORDER BY created_at, id LIMIT ? OFFSET ?`. Resolved rows are absent; ties are stable.
  - `openReportsGroupByComment` (CT-019 queue): `SELECT comment_id, count(*), min(created_at) FROM content_reports WHERE status = 'OPEN' GROUP BY comment_id ORDER BY min(created_at), comment_id`.
  - `removingACommentResolvesAllItsOpenReports` (D11): `UPDATE content_reports SET status='RESOLVED', resolution='REMOVED', moderator_id=?, resolved_at=? WHERE comment_id=? AND status='OPEN'` updates only that comment's open rows. A second run updates 0 (guarded, idempotent).
  - `existingOpenReportIsFoundForRepeatSubmission` (D10): `WHERE comment_id=? AND reporter_id=? AND status='OPEN'`.
  - `auditTrailForTargetIsChronological` (CT-035): `WHERE target_type=? AND target_id=? ORDER BY occurred_at, id`.
  - `auditByActorExcludesSystemEvents`: `WHERE actor_id=? ORDER BY occurred_at DESC, id DESC`.
  - `queueQueriesUseTheirIndexes`: with `SET LOCAL enable_seqscan = off`, `EXPLAIN` of the open-queue and audit-by-target queries names `idx_content_reports_open_queue` and `idx_audit_events_target`. This proves the indexes are usable, not that they're fast (AC: "queues are indexable").

- [ ] **Step 2: Run, expect pass** (schema exists from Task 1). If any query needs a different index, fix the migration in place: V14 is unreleased.

  ```bash
  ./mvnw test -Dtest='Governance*Test'
  ```

- [ ] **Step 3: Commit** `test(governance): pin review, report and audit queue queries [CT-016]`

---

### Task 3: Docs and handoffs

- [ ] **Step 1:** Add to `docs/changelog.md` under Unreleased:
  `- CT-016: governance schema — human-only case review history with required rejection notes, structured PII-minimized audit events with explicit system actors, and attributable comment reports (one open report per user per comment). Schema and query tests only; comment FK lands with CT-025.`
- [ ] **Step 2:** Run the full suite: `./mvnw test`.
- [ ] **Step 3: Commit** `docs(governance): record CT-016 schema decisions [CT-016]`
- [ ] **Step 4:** Copy the handoffs below into the PR body.

## Handoffs

- **CT-017:** map `CaseReview` (scalar ids, governance-internal repository). Write case state, review row and audit event in one transaction under the case lock. Add duplicate-flag/date queue indexes once filters are fixed.
- **CT-018:** takedown, status changes and closing statements are `audit_events` rows, never `case_reviews`.
- **CT-019:** guarded resolution update (D11 query). Return the existing open report on repeats (D10). Decide whether `REMOVED` requires notes.
- **CT-020:** owns the `AuditEvent` entity (replace the placeholder), action vocabulary enum, metadata allowlist/redaction, correlation-id normalization (D7d), and whether to add an append-only trigger (D7e).
- **CT-025:** add `fk_content_reports_comment` and flip `commentIdHasNoForeignKeyYet` into an FK-violation test.
- **CT-032/033:** retention writes `SYSTEM` audit events with a run correlation id. Review/report notes retention is still undefined (open question below).

## Open questions (not blocking CT-016)

1. How long do review notes and report resolution notes live, and how is sensitive text recorded by mistake corrected? (CT-020/CT-032)
2. Should a `REMOVED` resolution require notes? (CT-019)
