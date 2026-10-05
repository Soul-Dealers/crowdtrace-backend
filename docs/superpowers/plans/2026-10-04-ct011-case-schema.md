# CT-011 — Case, Sensitive-Details, File, and Consent Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/11
**Phase:** 2 — Case Registry and Submission
**Depends on:** CT-006 (identity persistence — `users` table)
**Unblocks:** CT-012 (projections), CT-013 (submission), CT-014 (duplicate/minor), CT-015 (file validation)

**Goal:** Lay the persistence foundation for the case registry — one Flyway migration whose constraints, foreign keys, indexes and query shapes are proven against real PostgreSQL. Entities and repositories are CT-012's.

**Architecture:** A single `V12__create_case_tables.sql` creates four tables in the `casefile` module: `cases` (public fields + review/public status + admin flags), `case_sensitive_details` (1:1, admin-only), `case_files` (attributable, visibility-tagged file metadata), and `case_consents` (append-only consent history). No Java production code is added; tests drive the schema through `JdbcTemplate`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA, Flyway, PostgreSQL 16, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/product-spec.md` §3.1–3.5, §5.1–5.2, §7.1–7.3; `docs/tasks/phase_2_case_registry.md` CT-011–CT-015; `docs/blueprint.md` Step 3.1.

---

## Decisions

Settled with Edem during planning, plus a design review with Codex (gpt-6-sol).

| # | Decision | Rationale |
|---|---|---|
| D1 | **CT-011 is schema only; entities and repositories stay in CT-012** | CT-012 is titled "Implement case entities, repositories, and visibility projections". The issue's "repository tests" are met at the schema level: `CaseQueryTest` runs the exact queries CT-012's repositories will issue against the migrated Postgres schema. Agreed by Edem, Claude and Codex. |
| D2 | **`BIGINT` identity primary keys (`Long`) everywhere, no separate public UUID** | Edem's call; matches `users`. Known trade-off: public case URLs expose sequential ids (case volume is guessable). Adding a `public_reference UUID` later is an additive migration — CT-012 should raise it again if public URLs need to be non-enumerable. |
| D3 | **Region is a fixed CHECK-constrained enum of Ghana's 16 regions** | Resolves PRD open question #5. Makes phase-4 region filters exact. A new region is a one-line migration + enum change. `last_seen_location` stays free text for the precise place. |
| D4 | **Consent stores user, version, source channel and timestamp — no IP / user agent** | Attributable without collecting extra personal data under Act 843. `source` is `WEB`, `MOBILE`, or `API`. |
| D5 | **Consent is per case and append-only** (no `revoked_at`) | History = multiple rows (e.g. re-consent on a new policy version). Revocation semantics are undefined in the PRD; phase 6 adds them with a defined effect on the case rather than a dangling nullable column now. |
| D6 | **Two independent statuses.** `review_status` (`SUBMITTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `TAKEN_DOWN`) is `NOT NULL`. `case_status` (`MISSING`, `FOUND_SAFE`, `FOUND_DECEASED`, `CLOSED`) is **null until approval** | A case that was never published has no public outcome. Two narrow CHECKs only: an `APPROVED` case must have a `case_status`; a `SUBMITTED`/`UNDER_REVIEW`/`REJECTED` case must not. `TAKEN_DOWN` may keep its former outcome. Transition rules belong to the CT-019 lifecycle service, not SQL. |
| D7 | **Sensitive details: `reporter_relationship` `NOT NULL`; the other four are nullable** ("where available", PRD §3.2 step 3) | Retention (phase 6) purges by **deleting the row**, never by nulling fields — so `NOT NULL` stays honest and no column is nullable "just for the purge". |
| D8 | **Reporter's account contact is not stored on the case** | It is `users.email`, joined at admin-projection time (CT-012). Storing a copy would create a second piece of personal data to keep in sync and purge. |
| D9 | **`case_files.case_id` is nullable** — files are uploaded first, then attached at submission | CT-015 validates a file *before* a case can reference it, and CT-013 submits a "report-file reference". `uploaded_by` is `NOT NULL`, so an unattached file is still attributable. CT-013 must check `uploaded_by = reporter_id` when attaching; cleanup of stale unattached uploads is phase 6 (the `idx_case_files_unattached` index exists for it). |
| D10 | **File purpose fixes visibility.** `REPORT` ⇒ `PRIVATE`, `PHOTO` ⇒ `PUBLIC` (CHECK) | A report can never be marked public by a bug. A `PUBLIC` photo is still served only once the case is approved — visibility is about the storage policy, not about publication. |
| D11 | **`submitted_at` is the "reported date"** for CT-014 duplicate matching | PRD §3.2 step 7 matches on "reported/last-seen date". Defined here so CT-014 doesn't invent a column. |
| D12 | **No audit, review-notes, or duplicate-match tables** | Audit is CT-020 (own service). Review notes/source belong to the phase-3 `case_reviews` table. Duplicate confidence/reason metadata is CT-014's. `cases.duplicate_flag` is only the queue signal and never blocks intake. |
| D13 | **Time-dependent rules stay out of SQL** (e.g. last-seen date not in the future) | A `CHECK` against `CURRENT_DATE` is non-deterministic. CT-013's validator owns it. Age range (`0..130`) is static, so it is a CHECK. |
| D14 | **Optimistic locking (`version`) on `cases`** | Reporter and admin can both update a live case's status (PRD §3.4). Costs one column; prevents a silent lost update in CT-019. |

### Out of scope (from the issue)

Publishing/approving, moderator decisions, takedown, search, notifications, the storage provider, retention cleanup, duplicate detection logic, submission API, and response mappers.

---

## Global Constraints

- **Java 21**, Spring Boot 4.1, Spring Modulith. No new dependencies.
- **Commits carry no Claude attribution** (`~/.claude/CLAUDE.md` overrides the harness reminder). Message style: `feat(casefile): … [CT-011]`.
- **Before running tests:** `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true`. Without them the Postgres tests fail or silently skip.
- **Migrations are PostgreSQL-only.** Test them on Testcontainers Postgres, never H2 (see `IdentityMigrationTest` javadoc). Default `@DataJpaTest` here runs on H2 with `ddl-auto: create-drop` and therefore **does not exercise the migration at all** — so the CT-011 repository tests boot Postgres + Flyway with `ddl-auto: validate`.
- **Enums are `VARCHAR(32)` + named `CHECK`**, timestamps `TIMESTAMP WITHOUT TIME ZONE`, constraint names `pk_` / `fk_` / `uq_` / `idx_` / `<table>_<column>_check` — as in V1–V11.
- **`casefile` must not import `modules.identity.internal`.** `CrowdtraceModulesTest` must stay green. Reporter/uploader/consenting user are `Long` columns.
- **No production Java in this task.** Entities, enums, and repositories are CT-012 (see handoff notes).
- The suite is green at every commit.

## Review Focus

1. **A soft-deleted file still counted as the case's report** — a reporter deletes the wrong upload, and the case must no longer look evidenced. Pinned by `CaseQueryTest.associatesLiveFilesWithTheirCase` and `detectsWhetherAReportIsAttached` (Task 2).
2. **A report file marked `PUBLIC`** — must be rejected by the database, not just the service. Pinned by `CaseMigrationTest.rejectsAPublicReport` (Task 1).
3. **An unattached upload** (case_id null) must still name its uploader. Pinned by `CaseMigrationTest.requiresAnUploaderEvenBeforeAttachment` (Task 1) and `CaseQueryTest.findsUnattachedUploadsByUploader` (Task 2).
4. **Deleting a user who reported a case** must fail loudly (users are soft-deleted; a hard delete would orphan consent history). Pinned by `CaseMigrationTest.cannotHardDeleteAReporter` (Task 1).
5. **Approved case without a public outcome / pending case with one.** Pinned by `CaseMigrationTest.statusesStayConsistent` (Task 1).

---

## File Structure

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V12__create_case_tables.sql` | All four tables, checks, FKs, indexes |
| `src/test/java/com/souldealers/crowdtracebackend/CaseMigrationTest.java` | Constraints, FKs, indexes via `JdbcTemplate` |
| `src/test/java/com/souldealers/crowdtracebackend/CaseQueryTest.java` | Ownership, status filters, consent history, file association |
| `docs/changelog.md` | One entry |

---

### Task 1: V12 migration with schema-level tests

**Files:**
- Create: `src/main/resources/db/migration/V12__create_case_tables.sql`
- Create: `src/test/java/com/souldealers/crowdtracebackend/CaseMigrationTest.java`

**Interfaces:**
- Produces: tables `cases`, `case_sensitive_details`, `case_files`, `case_consents` with the exact columns below — Task 2 maps them 1:1 and `SchemaValidationTest` enforces it.

- [ ] **Step 1: Write the failing migration test**

Copy the class header, container, and `@DynamicPropertySource` verbatim from `IdentityMigrationTest` (Postgres 16, `flyway.enabled=true`, `ddl-auto=none`). Then:

```java
@Autowired private JdbcTemplate jdbc;

private long user(String email) {
    return jdbc.queryForObject("""
            INSERT INTO users (email, password_hash, display_name, role, account_status)
            VALUES (?, 'x', 'Ada', 'REGISTERED_USER', 'ACTIVE') RETURNING id""", Long.class, email);
}

private long caseFor(long reporterId) {
    return jdbc.queryForObject("""
            INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                last_seen_location, physical_description, clothing, circumstances,
                public_contact_number, review_status, submitted_at)
            VALUES (?, 'Kofi Mensah', 12, 'MALE', DATE '2026-09-30', 'GREATER_ACCRA',
                'Madina market', 'Slim, 1.4m', 'Blue school uniform', 'Did not return from school',
                '+233200000000', 'SUBMITTED', CURRENT_TIMESTAMP) RETURNING id""", Long.class, reporterId);
}

@Test
void createsTheFourCaseTables() {
    assertThat(List.of("cases", "case_sensitive_details", "case_files", "case_consents"))
            .allMatch(this::tableExists);
}

@Test
void newCaseDefaultsToUnflaggedAndHasNoPublicOutcome() {
    long id = caseFor(user("a@x.com"));
    Map<String, Object> row = jdbc.queryForMap(
            "SELECT priority_minor, duplicate_flag, case_status, version FROM cases WHERE id = ?", id);
    assertThat(row).containsEntry("priority_minor", false).containsEntry("duplicate_flag", false)
            .containsEntry("case_status", null).containsEntry("version", 0L);
}

@Test
void rejectsUnknownEnumValues() {
    long id = caseFor(user("b@x.com"));
    assertThatThrownBy(() -> jdbc.update("UPDATE cases SET review_status = 'PUBLISHED' WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE cases SET region = 'ACCRA' WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE cases SET age = 131 WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void statusesStayConsistent() {
    long id = caseFor(user("c@x.com"));
    // approved without an outcome
    assertThatThrownBy(() -> jdbc.update("UPDATE cases SET review_status = 'APPROVED' WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    // pending with an outcome
    assertThatThrownBy(() -> jdbc.update("UPDATE cases SET case_status = 'MISSING' WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    // approval sets both; takedown may keep the outcome
    jdbc.update("UPDATE cases SET review_status = 'APPROVED', case_status = 'MISSING' WHERE id = ?", id);
    jdbc.update("UPDATE cases SET review_status = 'TAKEN_DOWN' WHERE id = ?", id);
}

@Test
void rejectsACaseForAnUnknownReporter() {
    assertThatThrownBy(() -> caseFor(999_999L)).isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void cannotHardDeleteAReporter() {
    long reporter = user("d@x.com");
    caseFor(reporter);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", reporter))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void requiresAReporterRelationshipOnSensitiveDetails() {
    long id = caseFor(user("e@x.com"));
    assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO case_sensitive_details (case_id, reporter_relationship) VALUES (?, NULL)", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("INSERT INTO case_sensitive_details (case_id, reporter_relationship) VALUES (?, 'Mother')", id);
}

@Test
void rejectsAPublicReport() {
    long uploader = user("f@x.com");
    assertThatThrownBy(() -> insertFile(uploader, null, "REPORT", "PUBLIC", "k1"))
            .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertFile(uploader, null, "PHOTO", "PRIVATE", "k2"))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void requiresAnUploaderEvenBeforeAttachment() {
    assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO case_files (uploaded_by, purpose, visibility, storage_key, content_type,
                size_bytes, checksum_sha256) VALUES (NULL, 'REPORT', 'PRIVATE', 'k3', 'application/pdf', 10, ?)""",
            "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void storageKeysAreUnique() {
    long uploader = user("g@x.com");
    insertFile(uploader, null, "REPORT", "PRIVATE", "dup");
    assertThatThrownBy(() -> insertFile(uploader, null, "REPORT", "PRIVATE", "dup"))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void consentVersionIsRecordedOncePerCase() {
    long reporter = user("h@x.com");
    long id = caseFor(reporter);
    insertConsent(id, reporter, "2026-10-01");
    insertConsent(id, reporter, "2026-11-01");   // history: new version is allowed
    assertThatThrownBy(() -> insertConsent(id, reporter, "2026-10-01"))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void createsTheQueryIndexes() {
    assertThat(indexesOn("cases")).contains(
            "idx_cases_reporter", "idx_cases_review_queue", "idx_cases_public_status");
    assertThat(indexesOn("case_files")).contains(
            "uq_case_files_storage_key", "idx_case_files_case", "idx_case_files_unattached");
    assertThat(indexesOn("case_consents")).contains("idx_case_consents_case", "idx_case_consents_user");
}

private int insertFile(long uploader, Long caseId, String purpose, String visibility, String key) {
    return jdbc.update("""
            INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key,
                content_type, size_bytes, checksum_sha256)
            VALUES (?, ?, ?, ?, ?, 'application/pdf', 1024, ?)""",
            caseId, uploader, purpose, visibility, key, "a".repeat(64));
}

private int insertConsent(long caseId, long userId, String version) {
    return jdbc.update("""
            INSERT INTO case_consents (case_id, user_id, consent_type, consent_version, source)
            VALUES (?, ?, 'SENSITIVE_DATA_COLLECTION', ?, 'WEB')""", caseId, userId, version);
}

private boolean tableExists(String table) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = ?)", Boolean.class, table));
}

private List<String> indexesOn(String table) {
    return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
}
```

Check `users` insert columns against `V1`/`V4`/`V7`/`V11` (`role`/`account_status` values come from `UserRoles` / `UserStatus`). Use distinct emails per test — the container is shared and there is no rollback.

- [ ] **Step 2: Run it — expect FAIL** (tables do not exist)

`./mvnw test -Dtest=CaseMigrationTest`

- [ ] **Step 3: Write the migration**

```sql
-- Case registry schema (CT-011).
--
-- Four tables with one privacy boundary: `cases` holds what may become public plus the
-- admin-only review signals; `case_sensitive_details` holds admin-only personal data and is
-- deleted as a whole by retention cleanup, which is why reporter_relationship can stay
-- NOT NULL. Files and consents name the user who created them so every record is
-- attributable. Lifecycle transitions are enforced by the case services, not here — the
-- checks below only pin the vocabulary and the two states that can never be valid.

CREATE TABLE cases
(
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    reporter_id           BIGINT                      NOT NULL,
    full_name             VARCHAR(255)                NOT NULL,
    age                   SMALLINT                    NOT NULL,
    gender                VARCHAR(32)                 NOT NULL,
    last_seen_date        DATE                        NOT NULL,
    region                VARCHAR(32)                 NOT NULL,
    last_seen_location    VARCHAR(500)                NOT NULL,
    physical_description  TEXT                        NOT NULL,
    clothing              TEXT                        NOT NULL,
    circumstances         TEXT                        NOT NULL,
    public_contact_number VARCHAR(32)                 NOT NULL,
    review_status         VARCHAR(32)                 NOT NULL,
    case_status           VARCHAR(32),
    closing_statement     TEXT,
    priority_minor        BOOLEAN                     NOT NULL DEFAULT FALSE,
    duplicate_flag        BOOLEAN                     NOT NULL DEFAULT FALSE,
    version               BIGINT                      NOT NULL DEFAULT 0,
    submitted_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    approved_at           TIMESTAMP WITHOUT TIME ZONE,
    resolved_at           TIMESTAMP WITHOUT TIME ZONE,
    closed_at             TIMESTAMP WITHOUT TIME ZONE,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_cases PRIMARY KEY (id),
    -- RESTRICT: users are soft-deleted (V4). A hard delete must not orphan a case.
    CONSTRAINT fk_cases_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT cases_age_check CHECK (age BETWEEN 0 AND 130),
    CONSTRAINT cases_gender_check CHECK (gender IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT cases_region_check CHECK (region IN (
        'AHAFO', 'ASHANTI', 'BONO', 'BONO_EAST', 'CENTRAL', 'EASTERN', 'GREATER_ACCRA',
        'NORTH_EAST', 'NORTHERN', 'OTI', 'SAVANNAH', 'UPPER_EAST', 'UPPER_WEST', 'VOLTA',
        'WESTERN', 'WESTERN_NORTH')),
    CONSTRAINT cases_review_status_check CHECK (review_status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'TAKEN_DOWN')),
    CONSTRAINT cases_case_status_check CHECK (case_status IN (
        'MISSING', 'FOUND_SAFE', 'FOUND_DECEASED', 'CLOSED')),
    -- A published case always has a public outcome; a never-published case never has one.
    -- TAKEN_DOWN is deliberately unconstrained: it may keep the outcome it had while live.
    CONSTRAINT cases_approved_has_outcome_check
        CHECK (review_status <> 'APPROVED' OR case_status IS NOT NULL),
    CONSTRAINT cases_unpublished_has_no_outcome_check
        CHECK (review_status NOT IN ('SUBMITTED', 'UNDER_REVIEW', 'REJECTED') OR case_status IS NULL)
);

-- "My cases" for a reporter, newest first.
CREATE INDEX idx_cases_reporter ON cases (reporter_id, created_at DESC);
-- Moderation queue: minors first, then oldest submission (PRD §3.5, US-02).
CREATE INDEX idx_cases_review_queue ON cases (review_status, priority_minor DESC, submitted_at);
-- Public registry filters by outcome among published cases only.
CREATE INDEX idx_cases_public_status ON cases (case_status) WHERE review_status = 'APPROVED';

CREATE TABLE case_sensitive_details
(
    case_id               BIGINT                      NOT NULL,
    reporter_relationship VARCHAR(100)                NOT NULL,
    medical_conditions    TEXT,
    known_associates      TEXT,
    vehicle_info          TEXT,
    social_media_handles  TEXT,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_case_sensitive_details PRIMARY KEY (case_id),
    CONSTRAINT fk_case_sensitive_details_case FOREIGN KEY (case_id) REFERENCES cases (id) ON DELETE CASCADE
);

-- case_id is NULL between upload and submission (CT-015 → CT-013); uploaded_by is never NULL.
CREATE TABLE case_files
(
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    case_id         BIGINT,
    uploaded_by     BIGINT                      NOT NULL,
    purpose         VARCHAR(32)                 NOT NULL,
    visibility      VARCHAR(32)                 NOT NULL,
    storage_key     VARCHAR(512)                NOT NULL,
    content_type    VARCHAR(100)                NOT NULL,
    size_bytes      BIGINT                      NOT NULL,
    checksum_sha256 CHAR(64)                    NOT NULL,
    uploaded_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attached_at     TIMESTAMP WITHOUT TIME ZONE,
    deleted_at      TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_case_files PRIMARY KEY (id),
    CONSTRAINT fk_case_files_case FOREIGN KEY (case_id) REFERENCES cases (id),
    CONSTRAINT fk_case_files_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id),
    CONSTRAINT case_files_purpose_check CHECK (purpose IN ('REPORT', 'PHOTO')),
    CONSTRAINT case_files_visibility_check CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    -- A police report can never be stored as public; a photo is always publishable.
    CONSTRAINT case_files_purpose_visibility_check CHECK (
        (purpose = 'REPORT' AND visibility = 'PRIVATE') OR (purpose = 'PHOTO' AND visibility = 'PUBLIC')),
    CONSTRAINT case_files_size_check CHECK (size_bytes > 0),
    CONSTRAINT case_files_attachment_check CHECK ((case_id IS NULL) = (attached_at IS NULL))
);

CREATE UNIQUE INDEX uq_case_files_storage_key ON case_files (storage_key);
CREATE INDEX idx_case_files_case ON case_files (case_id, purpose) WHERE deleted_at IS NULL;
-- Stale-upload cleanup and "my pending uploads".
CREATE INDEX idx_case_files_unattached ON case_files (uploaded_by, uploaded_at) WHERE case_id IS NULL;

-- Append-only. A new policy version is a new row; the same version twice is a bug.
CREATE TABLE case_consents
(
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    case_id         BIGINT                      NOT NULL,
    user_id         BIGINT                      NOT NULL,
    consent_type    VARCHAR(32)                 NOT NULL,
    consent_version VARCHAR(32)                 NOT NULL,
    source          VARCHAR(32)                 NOT NULL,
    accepted_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_case_consents PRIMARY KEY (id),
    CONSTRAINT fk_case_consents_case FOREIGN KEY (case_id) REFERENCES cases (id),
    CONSTRAINT fk_case_consents_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT case_consents_type_check CHECK (consent_type IN ('SENSITIVE_DATA_COLLECTION')),
    CONSTRAINT case_consents_source_check CHECK (source IN ('WEB', 'MOBILE', 'API')),
    CONSTRAINT uq_case_consents_version UNIQUE (case_id, consent_type, consent_version)
);

CREATE INDEX idx_case_consents_case ON case_consents (case_id, accepted_at);
CREATE INDEX idx_case_consents_user ON case_consents (user_id);
```

- [ ] **Step 4: Run it — expect PASS.** Then the full suite (`./mvnw test`) — `SchemaValidationTest` must still pass (no entities yet, so `validate` ignores the new tables).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V12__create_case_tables.sql src/test/java/com/souldealers/crowdtracebackend/CaseMigrationTest.java
git commit -m "feat(casefile): add case, sensitive-details, file and consent schema [CT-011]"
```

---

### Task 2: Query tests for ownership, status filters, consent history, and file association

The issue's "repository tests" are satisfied at the schema level: each test runs the SQL query a CT-012 repository method will issue, against the Flyway-migrated Postgres schema, and asserts the result. This proves the FKs and indexes support the queries without building entities early (D1).

**Files:**
- Create: `src/test/java/com/souldealers/crowdtracebackend/CaseQueryTest.java`
- Modify: `docs/changelog.md` (one entry)

**Interfaces:**
- Consumes: Task 1 tables.
- Produces: the query shapes CT-012's repositories must reproduce (listed in the CT-012 handoff below).

- [ ] **Step 1: Write the tests.** Same Postgres/Flyway class header as `CaseMigrationTest` (`ddl-auto=none`). Add class-level `@Transactional` so each test rolls back, and reuse the `user(..)` / `insertFile(..)` / `insertConsent(..)` helpers (copy them — test classes don't share helpers here). Add a richer case helper:

```java
private long caseOf(long reporterId, int age, boolean minor, String reviewStatus,
                    String caseStatus, LocalDateTime submittedAt) {
    return jdbc.queryForObject("""
            INSERT INTO cases (reporter_id, full_name, age, gender, last_seen_date, region,
                last_seen_location, physical_description, clothing, circumstances,
                public_contact_number, review_status, case_status, priority_minor,
                submitted_at, created_at)
            VALUES (?, 'Ama Owusu', ?, 'FEMALE', DATE '2026-09-30', 'ASHANTI', 'Kejetia',
                'Short', 'Red dress', 'Left home', '+233200000001', ?, ?, ?, ?, ?)
            RETURNING id""", Long.class,
            reporterId, age, reviewStatus, caseStatus, minor, submittedAt, submittedAt);
}

private static LocalDateTime at(int hour) { return LocalDateTime.of(2026, 10, 1, hour, 0); }
```

Tests:

```java
@Test
void listsOnlyTheReportersOwnCasesNewestFirst() {
    long a = user("own-a@x.com"), b = user("own-b@x.com");
    long older = caseOf(a, 30, false, "SUBMITTED", null, at(9));
    long newer = caseOf(a, 30, false, "SUBMITTED", null, at(10));
    caseOf(b, 30, false, "SUBMITTED", null, at(11));

    assertThat(jdbc.queryForList(
            "SELECT id FROM cases WHERE reporter_id = ? ORDER BY created_at DESC", Long.class, a))
            .containsExactly(newer, older);
}

@Test
void ownershipScopedLookupHidesOtherReportersCases() {
    long a = user("scope-a@x.com"), b = user("scope-b@x.com");
    long id = caseOf(a, 30, false, "SUBMITTED", null, at(9));
    String sql = "SELECT count(*) FROM cases WHERE id = ? AND reporter_id = ?";
    assertThat(jdbc.queryForObject(sql, Integer.class, id, b)).isZero();
    assertThat(jdbc.queryForObject(sql, Integer.class, id, a)).isOne();
}

@Test
void reviewQueueShowsMinorsFirstThenOldest() {
    long r = user("queue@x.com");
    long adult = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    long minorOlder = caseOf(r, 12, true, "UNDER_REVIEW", null, at(10));
    long minorNewer = caseOf(r, 15, true, "SUBMITTED", null, at(11));
    caseOf(r, 8, true, "APPROVED", "MISSING", at(8));

    assertThat(jdbc.queryForList("""
            SELECT id FROM cases WHERE review_status IN ('SUBMITTED', 'UNDER_REVIEW')
            ORDER BY priority_minor DESC, submitted_at""", Long.class))
            .containsExactly(minorOlder, minorNewer, adult);
}

@Test
void publicFilterReturnsOnlyApprovedCasesWithMatchingOutcome() {
    long r = user("public@x.com");
    long live = caseOf(r, 30, false, "APPROVED", "MISSING", at(9));
    caseOf(r, 30, false, "APPROVED", "FOUND_SAFE", at(10));
    caseOf(r, 30, false, "TAKEN_DOWN", "MISSING", at(11));
    caseOf(r, 30, false, "SUBMITTED", null, at(12));

    assertThat(jdbc.queryForList("""
            SELECT id FROM cases WHERE review_status = 'APPROVED' AND case_status IN ('MISSING')""",
            Long.class)).containsExactly(live);
}

@Test
void associatesLiveFilesWithTheirCase() {
    long r = user("files@x.com");
    long c1 = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    long c2 = caseOf(r, 30, false, "SUBMITTED", null, at(10));
    insertAttachedFile(r, c1, "REPORT", "PRIVATE", "f1", false);
    insertAttachedFile(r, c1, "PHOTO", "PUBLIC", "f2", false);
    insertAttachedFile(r, c1, "PHOTO", "PUBLIC", "f3", true);   // soft-deleted
    insertAttachedFile(r, c2, "REPORT", "PRIVATE", "f4", false);

    assertThat(jdbc.queryForList(
            "SELECT storage_key FROM case_files WHERE case_id = ? AND deleted_at IS NULL", String.class, c1))
            .containsExactlyInAnyOrder("f1", "f2");
}

@Test
void detectsWhetherAReportIsAttached() {
    long r = user("report@x.com");
    long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    String sql = """
            SELECT EXISTS (SELECT 1 FROM case_files
                           WHERE case_id = ? AND purpose = 'REPORT' AND deleted_at IS NULL)""";
    insertAttachedFile(r, c, "PHOTO", "PUBLIC", "p1", false);
    assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isFalse();
    insertAttachedFile(r, c, "REPORT", "PRIVATE", "r1", false);
    assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isTrue();
}

@Test
void findsUnattachedUploadsByUploader() {
    long a = user("up-a@x.com"), b = user("up-b@x.com");
    long c = caseOf(a, 30, false, "SUBMITTED", null, at(9));
    insertFile(a, null, "REPORT", "PRIVATE", "staged-a");
    insertAttachedFile(a, c, "REPORT", "PRIVATE", "attached-a", false);
    insertFile(b, null, "REPORT", "PRIVATE", "staged-b");

    assertThat(jdbc.queryForList(
            "SELECT storage_key FROM case_files WHERE uploaded_by = ? AND case_id IS NULL", String.class, a))
            .containsExactly("staged-a");
}

@Test
void attachmentTimestampMovesWithTheCaseLink() {
    long r = user("attach@x.com");
    long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    insertFile(r, null, "REPORT", "PRIVATE", "half");
    // linking a case without stamping attached_at (or vice versa) is rejected
    assertThatThrownBy(() -> jdbc.update("UPDATE case_files SET case_id = ? WHERE storage_key = 'half'", c))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void keepsConsentHistoryInAcceptanceOrderAndAttributed() {
    long r = user("consent@x.com");
    long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    jdbc.update("""
            INSERT INTO case_consents (case_id, user_id, consent_type, consent_version, source, accepted_at)
            VALUES (?, ?, 'SENSITIVE_DATA_COLLECTION', '2026-11-01', 'MOBILE', ?),
                   (?, ?, 'SENSITIVE_DATA_COLLECTION', '2026-10-01', 'WEB', ?)""",
            c, r, at(12), c, r, at(9));

    assertThat(jdbc.queryForList("""
            SELECT consent_version, user_id, source FROM case_consents
            WHERE case_id = ? ORDER BY accepted_at""", c))
            .extracting(row -> row.get("consent_version"), row -> row.get("user_id"), row -> row.get("source"))
            .containsExactly(tuple("2026-10-01", r, "WEB"), tuple("2026-11-01", r, "MOBILE"));
}

@Test
void reportsWhetherConsentWasGiven() {
    long r = user("given@x.com");
    long c = caseOf(r, 30, false, "SUBMITTED", null, at(9));
    String sql = """
            SELECT EXISTS (SELECT 1 FROM case_consents
                           WHERE case_id = ? AND consent_type = 'SENSITIVE_DATA_COLLECTION')""";
    assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isFalse();
    insertConsent(c, r, "2026-10-01");
    assertThat(jdbc.queryForObject(sql, Boolean.class, c)).isTrue();
}

private void insertAttachedFile(long uploader, long caseId, String purpose, String visibility,
                                String key, boolean deleted) {
    jdbc.update("""
            INSERT INTO case_files (case_id, uploaded_by, purpose, visibility, storage_key,
                content_type, size_bytes, checksum_sha256, attached_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, 'application/pdf', 1024, ?, CURRENT_TIMESTAMP, ?)""",
            caseId, uploader, purpose, visibility, key, "a".repeat(64),
            deleted ? LocalDateTime.now() : null);
}
```

Note: `insertFile(..)` from Task 1 inserts with `case_id` null and no `attached_at`, which satisfies `case_files_attachment_check`; to attach, use `insertAttachedFile`.

- [ ] **Step 2: Run** `./mvnw test -Dtest=CaseQueryTest` with the Docker env vars exported — expect PASS on first run (the schema already exists; these tests pin behaviour rather than drive new code). **Then prove they bite:** temporarily change the queue query's `priority_minor DESC` to `ASC` and confirm `reviewQueueShowsMinorsFirstThenOldest` fails; revert.

- [ ] **Step 3: Full suite.** `./mvnw test`. Confirm the Postgres tests actually *ran* (surefire count includes `CaseMigrationTest` and `CaseQueryTest`), not skipped.

- [ ] **Step 4: Changelog + commit.** Under the unreleased section of `docs/changelog.md`:
  `- CT-011: case registry schema (cases, sensitive details, case files, consents) with migration and query tests.`

```bash
git add src/test/java/com/souldealers/crowdtracebackend/CaseQueryTest.java docs/changelog.md
git commit -m "test(casefile): pin ownership, queue, consent and file queries on the case schema [CT-011]"
```

---

## Acceptance-criteria trace

| Acceptance criterion | Where |
|---|---|
| FKs and indexes support reporter/status/review queries | V12 `fk_*`, `idx_cases_reporter`, `idx_cases_review_queue`, `idx_cases_public_status`; `createsTheQueryIndexes`; `CaseQueryTest` queue/ownership/filter tests |
| Status enums are explicit | Named CHECK per enum column (vocabulary in D6, D3, D10, D4); `rejectsUnknownEnumValues`. Java enums + drift test in CT-012 |
| Consent and file records are attributable | `case_consents.user_id`, `case_files.uploaded_by` both `NOT NULL` FKs; `requiresAnUploaderEvenBeforeAttachment`, `findsUnattachedUploadsByUploader`, `keepsConsentHistoryInAcceptanceOrderAndAttributed` |
| Sensitive fields not nullable by accident | D7; `requiresAReporterRelationshipOnSensitiveDetails`; all required public fields `NOT NULL` |
| Tests: ownership, status filters, consent history, file association | `CaseQueryTest` (Task 2) |

## Handoff notes for later tasks

- **CT-012 — entities and repositories (designed here, built there):**
  - Public enums in `modules.casefile`, values exactly as the V12 CHECKs: `ReviewStatus`, `CaseStatus`, `Gender { MALE, FEMALE, UNKNOWN }`, `GhanaRegion` (16), `CaseFilePurpose { REPORT, PHOTO }`, `FileVisibility { PRIVATE, PUBLIC }`, `ConsentType { SENSITIVE_DATA_COLLECTION }`, `ConsentSource { WEB, MOBILE, API }`.
  - Entities in `modules.casefile.internal.model`. **Name the case entity `CaseRecord`, not `Case`** — `CASE` is an HQL keyword and breaks JPQL. `CaseSensitiveDetails` uses `@MapsId` on `case_id`, and `CaseRecord` holds **no** back-reference, so reading a case never loads sensitive data by accident. `CaseConsent` is immutable (`updatable = false`, no setters). `CaseFile.attachTo(caseId, at)` throws if already attached and sets `attached_at` with `case_id` (V12 CHECK requires both together). `@Version` on `CaseRecord.version`.
  - **`reporterId`, `uploadedBy`, `userId` are plain `Long` columns, never `@ManyToOne User`** — identity's model is internal to its module; `CrowdtraceModulesTest` would fail.
  - Repositories reproduce `CaseQueryTest`'s queries: `findByReporterIdOrderByCreatedAtDesc`, `findByIdAndReporterId`, `findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAsc`, `findByReviewStatusAndCaseStatusIn`, `findByCaseIdAndDeletedAtIsNull`, `existsByCaseIdAndPurposeAndDeletedAtIsNull`, `findByUploadedByAndCaseIdIsNull`, `findByCaseIdOrderByAcceptedAtAsc`, `existsByCaseIdAndConsentType`.
  - **Repository tests must boot Postgres + Flyway with `ddl-auto: validate`** — the default `@DataJpaTest` runs on H2 `create-drop` and never touches V12. Include an enum-drift test that saves every enum value (the V9 bug class), and an optimistic-lock test.
  - Load `CaseSensitiveDetails` explicitly for the admin projection only; reporter contact = `users.email` via identity's public API. Revisit D2 (public UUID) if public URLs must be non-enumerable.
- **CT-013:** in one transaction — create case (`SUBMITTED`), sensitive details, consent row, attach the report (`CaseFile.attachTo`) after checking `uploadedBy == reporterId` and `purpose == REPORT`; reject when either is missing. Validate `last_seen_date` not in the future.
- **CT-014:** use `submitted_at` as the reported date; store match metadata in its own table; only set `duplicate_flag` / `priority_minor` here.
- **Phase 6 retention:** delete the `case_sensitive_details` row and the REPORT objects + rows; clean stale unattached uploads via `idx_case_files_unattached`; define consent revocation.
