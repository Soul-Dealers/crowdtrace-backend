# CT-014 — Non-Blocking Duplicate Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/14
**Phase:** 2 — Case Registry and Submission
**Depends on:** CT-012 (`CaseRecord`, `CaseRecordRepository`), CT-013 (`CaseSubmittedEvent`, D5/D6)
**Unblocks:** CT-017 (the queue surfaces `duplicate_flag` and the match details; moderators confirm a match as duplicate or distinct)

**Goal:** Every submitted case is compared against the existing registry after it commits. A possible match flags the new case and records why, for a moderator to judge. Detection never rejects, delays-to-failure or alters an intake, and the same data always produces the same result.

**Architecture:** Everything lives in `modules.casefile`. A pure, deterministic `DuplicateMatcher` (name normalization + similarity + date window → score and reasons) sits behind a `DuplicateDetectionService` that loads candidates, stores matches and sets the flag in its own `REQUIRES_NEW` transaction. A `@TransactionalEventListener(AFTER_COMMIT)` on `CaseSubmittedEvent` calls it and swallows failures.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, PostgreSQL 16 + Testcontainers, JUnit 5, AssertJ. No new dependencies: Levenshtein is hand-rolled (~15 lines) and there's no `pg_trgm`.

**Spec:** `docs/product-spec.md` §3.2 step 7, §3.5 ("flagged for human review; submission is not blocked"), §5.1 (duplicate flag is admin-only); `docs/crowdtrace-spec.md` §3.4 ("the new submission is flagged in the review queue for the admin to confirm"); `docs/blueprint.md` Step 3.4; CT-013 plan D5/D6 and handoff notes.

---

## Decisions

D1–D12 were settled by Edem. D10a records the connection-pool correction found during final review. Design produced by Claude and Codex ("Astra") independently, then reconciled. The one disagreement (D6) went to Edem.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Match on name + last-seen date only.** "Reported date" is dropped. `submitted_at` is not used as a matching date | Edem. No reported-date field exists, and `submitted_at` is platform intake time, not the disappearance. Substituting it would create false positives. |
| D2 | **Name normalization (versioned, comparison-only):** Unicode NFD → strip combining marks → `Locale.ROOT` lowercase → punctuation becomes a space → collapse whitespace → split into tokens → **sort tokens, keeping repeats**. Letters such as `ɛ`/`ɔ` are preserved. No ASCII transliteration, nickname or day-name equivalence, or honorific stripping | `Kofi Mensah` ↔ `mensah  KOFI` ↔ `Kofí Mensah` match without guessing which token is the surname. Akan day names, nicknames and honorifics need an agreed list. Deferred, to avoid encoding wrong assumptions. The stored `full_name` is never modified. |
| D3 | **Similarity N = 1 − levenshtein(a, b) / max(len a, len b)** over the sorted, space-joined tokens, measured in code points. **Match when N ≥ 0.90 and \|Δ last-seen\| ≤ 7 days.** A single-token name on either side requires an exact name match | Levenshtein is explicit and deterministic, and its penalties are easy to explain to a moderator. Jaro-Winkler's prefix boost over-rewards shared common first names (`Kwame …`). `pg_trgm` is deferred until volume justifies it (needs an extension plus recalibration). The single-token rule stops `Ama` from fuzzy-matching `Ami`. |
| D4 | **Confidence = round(100 × (0.8·N + 0.2·(1 − d/8)))**, an integer 0–100 using `RoundingMode.HALF_UP`. **Reason codes:** `NAME_EXACT`, `NAME_REORDERED`, `NAME_FUZZY`, `LAST_SEEN_EXACT`, `LAST_SEEN_NEAR`. Also stored: `name_similarity` (×1000, integer), `day_difference`, `algorithm_version = "v1"` | Integer arithmetic means no floating-point drift between runs, which covers the AC "deterministic for the same data". The score is a **ranking aid, not a probability**. Moderators decide (Q5). The version lets CT-017+ tell results apart if the rules change. |
| D5 | **Missing dates → no match.** The pure matcher is null-safe: if either last-seen date is null, it returns no match and never substitutes another date | Covers the AC "missing dates are handled". V12 makes `last_seen_date` NOT NULL, so this is defensive. It is pinned at the matcher level, where a null can be constructed. |
| D6 | **Flag only the new case.** The match row records both ids, so an admin can see the link from either side. Existing cases are never modified | Edem, following the spec ("the new submission is flagged"). Astra proposed flagging both cases. Rejected: it would change the admin state of already-published cases and race with CT-018 updates. |
| D7 | **Candidates = all other cases, whatever their review or case status, including the same reporter's own.** Prefiltered in SQL by `last_seen_date BETWEEN d−7 AND d+7 AND id <> :caseId`, ordered by id. Only `(id, full_name, last_seen_date)` is fetched | Edem. A rejected hoax being resubmitted, or a reporter double-submitting, are exactly the signals moderators need. Ordering by id plus a pure matcher makes the result independent of row order. |
| D8 | **Storage:** new table `case_duplicate_matches(id, case_id → cases, matched_case_id → cases, confidence, name_similarity, day_difference, reasons, algorithm_version, detected_at)` with `UNIQUE (case_id, matched_case_id)` and `CHECK (case_id <> matched_case_id)`. The row is **directional**: `case_id` is the newly submitted case. **No name snapshots** | The unique key is the backstop against a pair being stored twice. Making redelivery a clean no-op is D8a's job. Storing no names means retention (CT-032) has nothing extra to delete, and the names stay on `cases`, which keeps them as a public archive anyway (§7.1). `reasons` is a comma-joined `VARCHAR(200)` of the D4 codes, fixed order. |
| D8a | **Serialize detection per case:** `detect(caseId)` first loads the new case with `PESSIMISTIC_WRITE` (the existing `findOwnedByIdForUpdate` pattern, or an unowned `findByIdForUpdate`). It then skips any candidate whose `(case_id, matched_case_id)` row already exists | Astra's finding: the unique key alone *rejects* a concurrent second delivery with a violation, but doesn't make it a no-op. Locking the case row means a second delivery waits, then sees the rows the first one committed, and inserts nothing. The unique key stays as the backstop. |
| D9 | **Flag via the entity, not a bulk `UPDATE`:** load the new `CaseRecord`, call `setDuplicateFlag(true)`, let `@Version` bump | A bulk update bypasses optimistic locking. A moderator holding a stale copy would then silently overwrite the flag back to `false`, because JPA writes every column. Going through the entity turns that race into an `OptimisticLockException` for the stale writer. |
| D10 | **Delivery:** `DuplicateDetectionListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) → `DuplicateDetectionService.detect(caseId)` (`@Transactional(propagation = REQUIRES_NEW)`). The listener catches `RuntimeException` and logs WARN with **only the case id and exception type**. Synchronous, best-effort | Spring already swallows AFTER_COMMIT exceptions. Catching explicitly keeps the log useful and free of PII. `REQUIRES_NEW` is required because the original transaction's resources are still bound after commit. The request waits for detection, but it's one indexed range query plus in-memory matching. `@Async` is deferred until there's a measured latency problem. |
| D10a | **Release Hibernate connections after transaction completion:** `DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` | Prevents synchronous AFTER_COMMIT detection from waiting for an extra connection while every submission still holds its original one. Applies globally. Hibernate read-only sessions still skip flushing, but Spring no longer sets the JDBC read-only hint; custom transaction isolation would need additional configuration. The current application uses default isolation throughout. |
| D11 | **Index:** `CREATE INDEX idx_cases_last_seen ON cases (last_seen_date, id)` | Supports the D7 prefilter. The blueprint's step 4.1 already asks for a date index. |
| D12 | **Config:** `crowdtrace.duplicates.{date-window-days: 7, name-threshold-permille: 900}`, validated at startup. The algorithm version is a code constant, not config | Lets thresholds be tuned without a deploy. The version tracks the *code*, so it can't be config. |

### Discarded

- Using "reported date" or `submitted_at` as a matching date → D1.
- Flagging the existing case as well → D6 (Edem).
- `MinorPriorityService` from blueprint step 3.4 → CT-013 D5 already sets `priority_minor` at creation. CT-014 never touches it.
- `pg_trgm` / GIN trigram search, Jaro-Winkler, token-set similarity → D3. Revisit with real volume.
- Ghanaian nickname, day-name and honorific equivalence tables → D2. Needs product input and fixtures.
- Storing name snapshots in match rows → D8.
- A bulk `UPDATE cases SET duplicate_flag` → D9.
- Automatic rejection, merging or "confirmed duplicate" state → a moderator decision (Q5, CT-017).

### Out of scope

Showing matches in the review queue or admin detail API, and the moderator's duplicate/distinct decision (CT-017). Recomputing matches when a case is edited (CT-018 can re-publish an event if needed). Retry or replay of lost events (needs the Modulith JPA event registry). Audit events (CT-020, see [[audit-is-its-own-service]]). Retention of match rows (phase 6: they hold no PII, but rule on it alongside CT-032). Async execution.

---

## Global Constraints

- **Branch:** `feat/CT-014-Duplicate-Detection`, cut from `main` (CT-013 is merged).
- **Commits carry no Claude attribution** (`~/.claude/CLAUDE.md`). Style: `feat(casefile): … [CT-014]`.
- **Before tests:** `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true`.
- Repository, service and listener tests use Postgres + Flyway via `CasePostgresTestSupport`.
- **One migration: `V13__create_case_duplicate_matches.sql`** (table + D11 index).
- Logs and exception messages never contain names, dates or other case content. Case ids only.
- `CrowdtraceModulesTest` stays green. Nothing here leaves `casefile`.
- The suite is green at every commit.

## Review Focus

1. **Detection blocking or failing an intake.** Pinned by `DuplicateDetectionListenerTest.throwingDetectorDoesNotFailSubmission`.
2. **Non-determinism.** Pinned by `DuplicateMatcherTest.sameInputsSameResult` and `.candidateOrderDoesNotChangeResult`.
3. **Touching an existing case.** Pinned by `DuplicateDetectionServiceTest.existingCaseIsNeverModified` (same `version` and flag).
4. **PII in match rows or logs.** Pinned by a schema assertion (no name or text columns) and a log-capture test.

---

## File Structure

`C` = `src/main/java/com/souldealers/crowdtracebackend/modules/casefile/`

| File | Status | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V13__create_case_duplicate_matches.sql` | new | D8 table, D11 index |
| `src/main/resources/application.yaml` | modify | D12 config |
| `C/internal/duplicates/NameNormalizer.java` | new | D2 |
| `C/internal/duplicates/DuplicateMatcher.java` | new | D3–D5, a pure function returning `Optional<DuplicateMatch>` |
| `C/internal/duplicates/DuplicateMatch.java`, `MatchReason.java` | new | value types |
| `C/internal/duplicates/DuplicateProperties.java` | new | D12 `@ConfigurationProperties`, `@Validated` |
| `C/internal/duplicates/DuplicateDetectionService.java` | new | D7–D9, `REQUIRES_NEW` |
| `C/internal/duplicates/DuplicateDetectionListener.java` | new | D10 |
| `C/internal/model/CaseDuplicateMatch.java` | new | entity |
| `C/internal/repository/CaseDuplicateMatchRepository.java` | new | `existsByCaseIdAndMatchedCaseId`, `findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc` (for CT-017) |
| `C/internal/repository/CaseRecordRepository.java` | modify | `findDuplicateCandidates(caseId, from, to)` projection |

---

### Task 1: Schema

- [x] `V13`: create the table as in D8 (`confidence SMALLINT CHECK 0..100`, `name_similarity SMALLINT CHECK 0..1000`, `day_difference SMALLINT CHECK >= 0`, `reasons VARCHAR(200) NOT NULL`, `algorithm_version VARCHAR(16) NOT NULL`, `detected_at TIMESTAMP NOT NULL`), plus an index on `matched_case_id` and the D11 index.
- [x] Repository test (Postgres): a row round-trips; a duplicate `(case_id, matched_case_id)` → `DataIntegrityViolationException`; self-match is rejected by the CHECK; the table has no name or free-text columns.
- [x] Commit `feat(casefile): add duplicate-match persistence [CT-014]`.

### Task 2: `NameNormalizer` + `DuplicateMatcher` (pure)

- [x] `NameNormalizerTest`: case and whitespace are collapsed; `Kofí` → `kofi`; `Ɛfua Ɔsei` keeps `ɛ`/`ɔ`; `Mensah, Kofi` ≡ `Kofi Mensah`; repeated tokens are kept (`Ama Ama Owusu` ≠ `Ama Owusu`).
- [x] `DuplicateMatcherTest`:
  - **True matches:** exact name + same day → `NAME_EXACT, LAST_SEEN_EXACT`, confidence 100; reordered name, 3 days apart → `NAME_REORDERED, LAST_SEEN_NEAR`, confidence 93; `Kofi Mensah` vs `Kofi Mensa`, 1 day → `NAME_FUZZY, LAST_SEEN_NEAR`, N = 10/11, confidence 90.
  - **False positives (no match):** same name 8 days apart; `Kofi Mensah` vs `Kwame Mensah` on the same day (N < 0.90); single-token `Ama` vs `Ami`; a common first name with a different surname.
  - **Missing dates:** either date null → empty.
  - **Determinism:** the same inputs give an identical result (`equals`) across repeated calls; confidence is pinned exactly for 3 fixtures; shuffling the candidate order gives the same result list.
- [x] Commit `feat(casefile): add deterministic name and last-seen duplicate matcher [CT-014]`.

### Task 3: `DuplicateDetectionService`

- [x] Candidate query (D7) and service (D8, D8a, D9).
- [x] **Commit fixtures before calling `detect`.** `CasePostgresTestSupport` is `@Transactional`, and `detect` runs in `REQUIRES_NEW`, so it can't see uncommitted rows. Make this test class non-transactional (`@Transactional(propagation = NOT_SUPPORTED)`), insert fixtures with a `TransactionTemplate`, and clean up in `@AfterEach`.
- [x] Tests (Postgres): **two concurrent `detect(caseId)` calls → exactly one row per pair, no exception, `duplicate_flag=true`**; a match → row stored + new case flagged; **the existing case's `duplicate_flag` and `version` are unchanged**; candidates of every status, including REJECTED, TAKEN_DOWN and same-reporter, are compared; no match → no rows and flag stays `false`; multiple matches → one row each, flag set once; calling `detect` twice → no new rows, no error (idempotent); thresholds come from properties.
- [x] Commit `feat(casefile): store duplicate matches and flag the new case [CT-014]`.

### Task 4: Listener + non-blocking end-to-end

- [x] `DuplicateDetectionListener` (D10).
- [x] Tests (full context, Postgres): submitting a case that matches an existing one → 201, `reviewStatus=SUBMITTED`, and afterwards `duplicate_flag=true` with one match row; a detector that throws (`@MockitoSpyBean`) → submission still 201, case persisted, flag `false`, WARN log contains the case id but no name; a rolled-back submission → the detector is never invoked. Extend the CT-013 `CaseSubmittedEventTest` rather than duplicating its setup.
- [x] Commit `feat(casefile): run duplicate detection after case submission commits [CT-014]`.

### Task 5: Docs

- [x] Update `docs/tasks/phase_2_case_registry.md` (CT-014: last-seen only, only the new case flagged), the blueprint step 3.4 note (MinorPriorityService superseded), and `docs/changelog.md`.
- [x] Commit `docs(casefile): record duplicate detection rules [CT-014]`.

---

## Acceptance-criteria trace

| AC | Where |
|---|---|
| Detection is deterministic for the same data | D3, D4, D7; Task 2 determinism tests |
| Missing dates are handled | D5; Task 2 missing-date tests |
| A possible match marks the case but does not block it | D6, D10; Task 3 flag test, Task 4 end-to-end and throwing-detector tests |
| Tests: false-positive, missing-date, non-blocking submission | Task 2, Task 4 |

## Handoff notes

- **→ CT-017:** Show `duplicate_flag` in the queue filters. Show matches via `CaseDuplicateMatchRepository.findByCaseIdOrderByConfidenceDescMatchedCaseIdAsc`, with confidence, reasons and the linked case id. The moderator's "duplicate / distinct" decision is CT-017's, and it must not delete match rows: they are the evidence.
- **→ CT-018:** If edits to name or last-seen date are allowed on a submitted case, re-publish `CaseSubmittedEvent` (or a new event). Detection is idempotent per pair, but stale rows aren't removed. Decide there.
- **→ CT-032 / phase 6:** Match rows hold only ids and scores. Decide retention alongside the case record.
- **→ Later:** Calibrate the 0.90 / 7-day thresholds against real moderator outcomes. Add Ghanaian name-equivalence fixtures (day names, nicknames, honorifics) with product input, and bump `algorithm_version`.

## Implementation notes — 2026-10-08

- The local `main` did not contain CT-013, so `feat/CT-014-Duplicate-Detection` was created
  from the existing CT-013 tip (`01bfca7`) to retain the required event and submission flow.
- Following Edem’s coding style, detection uses `DuplicateDetectionService` plus
  `DuplicateDetectionServiceImpl`, both internal to `casefile`.
- Claude Opus 5.5 advised using `days/(configured window + 1)` in the confidence date term.
  This preserves D4 exactly at the default 7-day window and keeps confidence within 0–100
  when D12’s window is widened. Name similarity is stored with HALF_UP rounding, while
  threshold comparison uses the unrounded fraction. Ordered normalized tokens distinguish
  exact names from reordered names.

- Final review found connection-pool starvation at concurrent submission callbacks. Added a
  two-connection-pool HTTP regression test that synchronizes both committed submissions,
  verifies zero connections retained before detection, and requires flags plus both directional
  match rows. The test failed with two retained connections before applying D10a.
