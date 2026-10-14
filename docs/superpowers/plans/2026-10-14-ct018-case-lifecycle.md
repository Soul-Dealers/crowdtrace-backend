# CT-018 — Case Lifecycle, Status Updates and Takedown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Phase:** 3 — Review, Moderation, Lifecycle, and Audit
**Depends on:** CT-017 (case lock, `expectedCaseVersion`, `ActorLookup`, review detail), CT-020 (audit recorder), CT-015 (`attachPhotos`), CT-013 (upload endpoint). Branch from `dev` once PR #53 (CT-017) is merged.
**Unblocks:** CT-021/022 (public reads use the deceased projection and takedown exclusion), CT-026 (follows respect takedown), CT-027 (in-app notifications), CT-028 (reporter status email), CT-032 (retention keys off `resolved_at`/`closed_at`)

**Goal:** The reporter or an admin can move a live case from `MISSING` to an outcome. Admins can correct outcomes, take a case down and restore it. Reporters and admins can set a public closing statement, and reporters can add photos to their live case. Every change is serialized per case, audited, and emits an ids-only event in the same transaction. A `FOUND_DECEASED` case is shown publicly with a notice and without granular missing-person details.

**Architecture:** `casefile` owns the lifecycle rules, the case mutations and the reporter endpoints through a public `CaseLifecycleService`. `governance` exposes the admin endpoints and calls that service; it writes nothing itself. Audit goes through `shared.audit` (existing CT-020 actions plus a new `CASE.RESTORED`). Event contracts live in `notification`, as `CaseReviewDecidedEvent` does. No consumers until CT-027/028.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA, PostgreSQL 16, Flyway, Testcontainers, JUnit 5, AssertJ. No new dependencies.

**Spec:** `docs/tasks/phase_3_governance.md` CT-018; `docs/product-spec.md` §3.4 (L104–111); `docs/crowdtrace-spec.md` §3.3 (L69–75); `docs/blueprint.md` L380–396; CT-016 plan D4; CT-020 plan action table (L62–65) and L260; CT-015 plan L198; CT-014 plan L146; CT-017 plan handoffs and `docs/changelog.md` CT-017 handoff.

---

## Decisions

Designed by Claude and Codex ("Astra") independently, then reconciled. Both designs agreed on: lifecycle rules owned by `casefile`; adding only `APPROVED → TAKEN_DOWN` to the review machine; reporter-terminal / admin-correctable outcomes; no live-case field edits (so no CT-014 re-detection); required `expectedCaseVersion` under the case lock; existing CT-020 audit actions; one V19 migration; and deferring police authorship. Adopted from Astra: the version required on photo additions (D11), `resolvedAt` kept across resolved-to-resolved corrections (D6), takedown attribution `taken_down_by` (D8), the stricter V19 shape checks (D15), a reporter `GET` exposing `version` (D14), the proxied statement-clear helper (D7) and the photo follower event (D12). Not adopted from Astra: a closing-statement event (nothing consumes it; YAGNI), and events in `casefile` (CT-017 D10 put event contracts in `notification`; `notification` has no dependencies, so there is no cycle). Settled by Edem: D9 (server withholds details on `FOUND_DECEASED`), D8 (takedown is restorable) and D11 (photos at any outcome while `APPROVED`). Astra's review of the final plan found three defects, all fixed: per-file photo audits weren't enforced (D11, Task 6), V19 would break existing fixtures (Task 1), and the reopen statement-clear audit had no isolated rollback test (Task 7).

| # | Decision | Rationale |
|---|---|---|
| D1 | **`casefile` owns lifecycle.** Public `casefile.CaseLifecycleService` holds every mutation; the reporter controller lives in `casefile`, and the admin controller lives in `governance` and calls the service. Governance never touches `cases`. | Case invariants stay in one module (CT-017 D1). Audit is `shared`, so casefile can record it without depending on governance. |
| D2 | **Actor comes from the principal.** Controllers pass `authentication.getName()`; the service resolves it with `identity.ActorLookup.byEmail` (CT-017 D2). Admin methods carry `@RequiresModerator` on the controller **and** the concrete service method. Reporter methods lock with `findOwnedByIdForUpdate`, so a foreign or unknown case → `404` (existing convention, `CaseQueryServiceTest.anotherReportersCaseIsNotFound`). | JSON can't spoof the actor, role or time. |
| D3 | **Review machine adds `APPROVED → TAKEN_DOWN` and `TAKEN_DOWN → APPROVED` (restore), both moderator/Super Admin only.** Everything else stays as in CT-017; `SUBMITTED → UNDER_REVIEW` stays unexposed. An admin can't take down or restore a case they reported → `403` (mirrors CT-017 D3). | Blueprint L393. Takedown is a visibility action, not a review decision (CT-016 D4): no `case_reviews` row. |
| D4 | **Outcome machine, only while `review_status = APPROVED`** (`TAKEN_DOWN` freezes status, statement and photos → `409`). `R` = reporter, `A` = moderator/Super Admin. | Product spec §3.4: reporter or admin updates, admin has final override. Resolved is terminal for the reporter; admins correct mistakes. |

| From \ To | MISSING | FOUND_SAFE | FOUND_DECEASED | CLOSED |
|---|---|---|---|---|
| MISSING | — | R/A | R/A | R/A |
| FOUND_SAFE | A | — | A | A |
| FOUND_DECEASED | A | A | — | A |
| CLOSED | A | A | A | — |

`—` (same status) → `409`. Reporter attempting an `A`-only cell → `409` (the transition is invalid for them; the case is theirs, so no existence leak). An admin who reported the case acts through the reporter endpoints with reporter rights, and may also use the admin endpoints for status and statement (only takedown/restore are blocked for own cases).

| # | Decision | Rationale |
|---|---|---|
| D5 | **Status change** = `PATCH …/status {expectedCaseVersion, caseStatus}`. Records `CASE.STATUS_CHANGED {fromStatus, toStatus}` (mapped to `AuditCaseStatus` with an exhaustive `switch`). | Existing closed schema (`AuditAction.java:24`). |
| D6 | **Timestamps (injected UTC `Clock`):** `MISSING → resolved` sets `resolvedAt`; resolved → resolved keeps it. Entering `CLOSED` sets `closedAt`; leaving `CLOSED` clears it. Reopening (`→ MISSING`) clears `resolvedAt`, `closedAt` and the closing statement. `approvedAt` never changes. | CT-032 retention counts 12 months from resolution; an outcome correction mustn't restart that clock, but a reopen must. |
| D7 | **Closing statement** = `PUT …/closing-statement {expectedCaseVersion, closingStatement}`. Reporter or admin, only while the outcome is not `MISSING`. Stripped (Unicode), non-blank, ≤ 2000; identical text → `409`. No delete endpoint. Text lives on `cases`, never in audit or events (CT-020 L260). Records `CASE.CLOSING_STATEMENT_SET {}`. A reopen that clears an existing statement also records `CASE.CLOSING_STATEMENT_SET` through a proxied `MANDATORY` helper carrying that annotation, so the conditional obligation is enforced (`AuditObligations` tracks action sets, not counts). | Spec §3.3: optional public statement on resolution. Police authorship deferred: no police role exists and badges aren't privileges (product-spec L70). |
| D8 | **Takedown** = `POST /api/v1/admin/cases/{id}/takedown {expectedCaseVersion, reason}` from `APPROVED` with any outcome. Reason required (Unicode-stripped, non-blank, ≤ 2000), admin-only. Sets `review_status = TAKEN_DOWN`, `takedown_reason`, `taken_down_by`, `taken_down_at`; keeps outcome, statement, timestamps, files and review history. Records `CASE.TAKEN_DOWN {}`. **Restore** (Edem) = `POST …/restore {expectedCaseVersion}` from `TAKEN_DOWN` → `APPROVED` with the preserved outcome; records new `CASE.RESTORED {}`. The takedown columns describe the **latest** takedown and are kept after restore (admins still see why it was hidden); a second takedown overwrites them, and the audit trail keeps every takedown and restore. Repeating either → `409`. | Spec: manual takedown for hoaxes; Edem wants mistakes reversible. No `case_reviews` row either way (CT-016 D4). Keeping the columns avoids losing the only copy of the reason text. |
| D9 | **Deceased presentation (Edem: server-enforced).** `PublicCaseResponse` gains `presentation` (`STANDARD` \| `DECEASED_NOTICE`). For `FOUND_DECEASED` the public mapper nulls `physicalDescription`, `clothing`, `circumstances`, `lastSeenLocation` and `publicContactNumber`; name, age, gender, region, `lastSeenDate`, status, closing statement and dates remain. Reporter and admin projections are unchanged. Banner wording belongs to clients. | Product spec: "a respectful notice banner instead of emphasizing granular missing-person details". Enforcing it in the one public mapper means every client complies. |
| D10 | **Public visibility:** public reads already require `APPROVED` (`CaseQueryServiceImpl.java:39`), so a taken-down case is `404` publicly and leaves listings; restore makes it visible again. Reporter and admin reads still work. | No new query code. CT-026 must block new follows on unavailable cases (handoff). |
| D11 | **Live-case photos (Edem: any outcome while `APPROVED`)** = `POST /api/user/cases/{id}/photos {expectedCaseVersion, photoFileIds}`, reporter only. Upload first through `POST /api/user/case-files?purpose=PHOTO` (20/day). New `CaseFileAttachmentService.attachLivePhotos(caseId, reporterId, expectedCaseVersion, photoIds)` checks `APPROVED` and the version **under the same parent lock** `attachPhotos` takes, then reuses its validation, cap and force-increment (CT-017 D13). One `CASE.PHOTO_ADDED {fileId}` per file, each recorded through a separately proxied `MANDATORY` helper `LivePhotoAuditor.recordPhotoAdded(actor, caseId, fileId)` that carries `@AuditedOperation(CASE_PHOTO_ADDED)`, so every file has its own obligation frame (`AuditObligations` deduplicates actions per frame, `AuditObligations.java:48`). The outer `addPhotos` carries `@Transactional` but **no** identical outer obligation. `attachedAt` comes from the `Clock`. No admin upload, removal or replacement. | CT-015 D17 / L198 handoff. Checking state outside the lock would race a takedown. |
| D12 | **Events** (`notification` module, published inside the transaction, ids only, `eventId` = `<kind>:<auditId>` from `AuditRecorder.record`'s return value): `CaseStatusChangedEvent(eventId, caseId, reporterId, fromStatus, toStatus, actorKind REPORTER\|ADMIN, occurredAt)`; `CasePhotosAddedEvent(eventId, caseId, fileIds, occurredAt)` (first photo audit id); `CaseTakenDownEvent(eventId, caseId, reporterId, occurredAt)`; `CaseRestoredEvent(eventId, caseId, reporterId, occurredAt)`. Status values are a `notification`-owned enum mapped by casefile. No names, text, reasons, emails or storage keys. | Consumers (CT-027 followers in-app, CT-028 reporter email) run after commit; a rollback delivers nothing. CT-029 dedups on `eventId`. |
| D13 | **One transaction per call, serialized per case:** lock (`findByIdForUpdate` / `findOwnedByIdForUpdate`) → authorize → check state → check `expectedCaseVersion` → mutate → flush → audit → publish. Order of errors: `404` → `403` → `409` state → `409` version → `409` transition (mirrors CT-017). Responses return the real resulting version. Concrete methods carry `@Transactional` + `@AuditedOperation` (CT-017 D15). | Two actors on one case get one success and one `409`. |
| D14 | **Reporter read:** new `GET /api/user/cases/{id}` returns `ReporterCaseResponse`, which gains `version`, `reviewStatus`, `resolvedAt`, `closedAt` (additive). Admins read the version from the CT-017 review detail. `AdminCaseResponse` gains `takedownReason`, `takenDownBy`, `takenDownAt`. | Reporters need a version to send. |
| D15 | **`V19__enforce_case_lifecycle.sql`:** add `takedown_reason VARCHAR(2000)`, `taken_down_by BIGINT REFERENCES users(id)`, `taken_down_at TIMESTAMP`. Checks: `review_status = 'TAKEN_DOWN'` ⇒ all three takedown columns set and `case_status IS NOT NULL`; takedown columns all-null or all-set; `resolved_at IS NOT NULL` ⇔ `case_status IN ('FOUND_SAFE','FOUND_DECEASED','CLOSED')`; `closed_at IS NOT NULL` ⇔ `case_status = 'CLOSED'`; `closing_statement` null or (non-blank, ≤ 2000, outcome ≠ `MISSING`). | DB is the backstop. V12 leaves taken-down state unconstrained (`V12:49`). Check the migration number at implementation time. |
| D16 | **Out of scope:** live-case field edits (name, dates, descriptions, contact, reports), so CT-014 re-detection isn't needed; police closing statements; retention queuing (CT-032 reads the timestamps); delivery of any event. | YAGNI; Astra and Claude agreed. |

### Endpoints

All return `200 ApiResponse<…>`. Errors use ProblemDetail: `400` invalid body, `401`, `403` role or own-case takedown/restore, `404` unknown or foreign case / unavailable upload, `409` state, version, transition, same value or already-attached file, `500` audit failure.

| Method + path | Body | Response |
|---|---|---|
| `GET /api/user/cases/{id}` | — | `ReporterCaseResponse` |
| `PATCH /api/user/cases/{id}/status` | `UpdateCaseStatusRequest(expectedCaseVersion, caseStatus)` | `CaseLifecycleResponse` |
| `PUT /api/user/cases/{id}/closing-statement` | `SetClosingStatementRequest(expectedCaseVersion, closingStatement)` | `CaseLifecycleResponse` |
| `POST /api/user/cases/{id}/photos` | `AddCasePhotosRequest(expectedCaseVersion, photoFileIds)` | `CasePhotosAddedResponse(caseId, caseVersion, photos, addedAt)` |
| `PATCH /api/v1/admin/cases/{id}/status` | `UpdateCaseStatusRequest` | `CaseLifecycleResponse` |
| `PUT /api/v1/admin/cases/{id}/closing-statement` | `SetClosingStatementRequest` | `CaseLifecycleResponse` |
| `POST /api/v1/admin/cases/{id}/takedown` | `TakeDownCaseRequest(expectedCaseVersion, reason)` | `CaseLifecycleResponse` |
| `POST /api/v1/admin/cases/{id}/restore` | `RestoreCaseRequest(expectedCaseVersion)` | `CaseLifecycleResponse` |

`CaseLifecycleResponse(caseId, caseVersion, reviewStatus, caseStatus, closingStatement, resolvedAt, closedAt, occurredAt)`. `photos` are `CaseFileMetadataResponse` (safe metadata only).

---

## Review Focus

- **Reopen with a statement present:** `FOUND_SAFE` + statement → admin `MISSING` must clear the statement, record both audits, and satisfy V19 (Task 3).
- **Takedown racing a reporter action:** status change, statement or photo add concurrent with takedown → exactly one wins; a photo never lands on a taken-down case (Task 6).
- **Restore after a correction:** take down a `FOUND_DECEASED` case, restore it → still `FOUND_DECEASED`, still withheld publicly, statement intact (Task 4).
- **Deceased projection leaking via listing:** the public list item for `FOUND_DECEASED` must null the same five fields as detail (Task 5).
- **Partial photo audit:** a batch whose second `CASE.PHOTO_ADDED` is missing must roll back entirely (Task 6).
- **Admin who is the reporter:** can use reporter rights on their own case but gets `403` on takedown/restore of it (Task 4, Task 7).

---

## File Structure

Paths are under `src/main/java/com/souldealers/crowdtracebackend/`.

| Path | Action | Decision |
|---|---|---|
| `src/main/resources/db/migration/V19__enforce_case_lifecycle.sql` | Create | D15 |
| `modules/casefile/internal/model/CaseRecord.java` | Modify | takedown columns |
| `modules/casefile/CaseLifecycleService.java`, `CaseLifecycleResponse.java`, `CasePhotosAddedResponse.java`, `CasePresentation.java` | Create | D1, D9, D12 |
| `modules/casefile/{UpdateCaseStatusRequest,SetClosingStatementRequest,AddCasePhotosRequest,TakeDownCaseRequest,RestoreCaseRequest}.java` | Create | D5, D7, D8, D11 |
| `modules/casefile/internal/lifecycle/CaseLifecyclePolicy.java` | Create | D3, D4 (pure matrix) |
| `modules/casefile/internal/lifecycle/LifecycleText.java` | Create | D7, D8 (strip/blank/2000) |
| `modules/casefile/internal/lifecycle/CaseLifecycleServiceImpl.java` | Create | D5–D8, D13 |
| `modules/casefile/internal/lifecycle/ClosingStatementClearer.java` | Create | D7 proxied helper |
| `modules/casefile/internal/lifecycle/LivePhotoAuditor.java` | Create | D11 per-file audited helper |
| `modules/casefile/CaseFileAttachmentService.java`, `internal/CaseFileAttachmentServiceImpl.java` | Modify | D11 |
| `modules/casefile/CaseLifecycleController.java` | Create | reporter endpoints + `GET /{id}` (D14) |
| `modules/casefile/{PublicCaseResponse,ReporterCaseResponse,AdminCaseResponse}.java`, `internal/CaseMapper.java` | Modify | D9, D14 |
| `modules/governance/AdminCaseLifecycleController.java` | Create | admin endpoints |
| `modules/notification/{CaseStatusChangedEvent,CasePhotosAddedEvent,CaseTakenDownEvent,CaseRestoredEvent,NotifiedCaseStatus}.java` | Create | D12 |
| `shared/audit/AuditAction.java` | Modify | add `CASE_RESTORED("CASE.RESTORED", CASE, empty())` |
| `shared/config/OpenApiConfig.java` | Modify | document the eight operations |
| `docs/changelog.md`, `docs/TASKS_INDEX.md` | Modify | |

---

### Task 1: Schema and policy

- [ ] **Step 1 (RED):** `CaseLifecycleMigrationTest` (Postgres): every V19 check accepts its valid shapes and rejects each NULL loophole (taken-down without reason/by/at, partial takedown columns, resolved without `resolved_at`, `closed_at` on a non-closed case, statement on `MISSING`, blank or 2001-char statement); `SchemaValidationTest` stays green. `CaseLifecyclePolicyTest`: parameterized over every outcome pair × {REPORTER, ADMIN} × every review status — allowed cells exactly as D4, same status rejected, anything not `APPROVED` rejected; takedown only from `APPROVED`, restore only from `TAKEN_DOWN`. `LifecycleTextTest`: Unicode strip, blank rejected, 2000 accepted, 2001 rejected. **Existing fixtures:** move every fixture builder and direct SQL setup that persists `TAKEN_DOWN` or a resolved outcome to a V19-valid shape (takedown columns; `resolved_at`/`closed_at`), keeping the original assertions — at least `CaseReviewMutationServiceTest.java:210` and `CaseRecordRepositoryTest.java:63`; grep `src/test` for `TAKEN_DOWN`, `FOUND_SAFE`, `FOUND_DECEASED`, `CLOSED` to find the rest. Run every Postgres-backed casefile/governance suite before committing.
- [ ] **Step 2:** V19, `CaseRecord` takedown fields, `CaseLifecyclePolicy`, `LifecycleText`, `CASE_RESTORED` in `AuditAction`.
- [ ] **Step 3: Commit** `feat(casefile): constrain case lifecycle state and define the transition policy [CT-018]`.

### Task 2: Events

- [ ] **Step 1 (RED):** `LifecycleEventContractTest`: the four records' components are exactly the ids/enums in D12 (reflection allowlist, like `CaseProjectionContractTest`); factories build `eventId` as `case-status:<auditId>`, `case-photos:<auditId>`, `case-takedown:<auditId>`, `case-restore:<auditId>`.
- [ ] **Step 2:** Create the event records and `NotifiedCaseStatus`.
- [ ] **Step 3: Commit** `feat(notification): add case lifecycle event contracts [CT-018]`.

### Task 3: Status and closing statement

- [ ] **Step 1 (RED):** `CaseStatusUpdateTest` (Postgres, fixed `Clock`): every D4 cell through the production proxy for reporter and admin (allowed → new status; else `409`); stale version → `409`; foreign case on the reporter path → `404`; `TAKEN_DOWN`/pending/rejected → `409`; D6 timestamps (resolve sets, correct keeps, close/unclose, reopen clears all three); one `CASE.STATUS_CHANGED` with mapped codes and the actor's role. **Reopen with a statement** records `CASE.STATUS_CHANGED` and `CASE.CLOSING_STATEMENT_SET`. `ClosingStatementTest`: set and replace on each resolved outcome; `MISSING` → `409`; identical text → `409`; blank/2001 → `400`; one audit per call with empty metadata; text never in audit metadata.
- [ ] **Step 2:** `CaseLifecycleService` (status + statement methods), `CaseLifecycleServiceImpl`, `ClosingStatementClearer`, requests, `CaseLifecycleResponse`; publish `CaseStatusChangedEvent`.
- [ ] **Step 3: Commit** `feat(casefile): update case status and closing statements [CT-018]`.

### Task 4: Takedown and restore

- [ ] **Step 1 (RED):** `CaseTakedownTest`: admin takes down from every outcome; reason rules; columns set from actor and clock; outcome, statement, files and `case_reviews` unchanged (no review row added); registered user → `403` at the service; own case → `403`; repeat → `409`; stale → `409`; afterwards status/statement/photo calls → `409`; public read → `404`. `CaseRestoreTest`: restore → `APPROVED` with the preserved outcome; takedown columns kept; public read works again; `FOUND_DECEASED` stays withheld after restore; second takedown overwrites the columns; own case → `403`; restoring an `APPROVED` case → `409`. Both: one `CASE.TAKEN_DOWN` / `CASE.RESTORED` audit, one event.
- [ ] **Step 2:** Takedown and restore methods (`@RequiresModerator` on the concrete methods); `CaseTakenDownEvent`/`CaseRestoredEvent` published.
- [ ] **Step 3: Commit** `feat(casefile): take down and restore published cases [CT-018]`.

### Task 5: Projections

- [ ] **Step 1 (RED):** `CaseProjectionContractTest`: public JSON gains only `presentation`; for `FOUND_DECEASED` the five D9 fields are `null` in detail **and** list items and `presentation = DECEASED_NOTICE`, otherwise `STANDARD` with fields intact; reporter JSON gains `version`, `reviewStatus`, `resolvedAt`, `closedAt`; admin JSON gains the takedown fields; `takedownReason` never appears in public or reporter JSON.
- [ ] **Step 2:** `CasePresentation`, mapper and response changes.
- [ ] **Step 3: Commit** `feat(casefile): show deceased cases respectfully and expose lifecycle fields [CT-018]`.

### Task 6: Live-case photos

- [ ] **Step 1 (RED):** `LiveCasePhotoTest`: reporter adds photos on each outcome while `APPROVED`; pending, rejected and taken-down → `409`; stale version → `409`; foreign case → `404`; existing CT-015 rules still apply (wrong purpose, deleted/foreign upload, already attached, duplicate ids, cap including existing live photos) and the batch is atomic; one `CASE.PHOTO_ADDED {fileId}` **per file** (assert the count); one `CasePhotosAddedEvent` per batch with ordered ids; `attachedAt` from the clock; returned version is the real one. `CaseFileAttachmentServiceTest` stays green. Concurrency (`LifecycleConcurrencyTest`): two status changes → one success, one `409`; takedown vs status, statement and photo add → one wins and no photo lands on a taken-down case; two photo batches can't exceed the cap.
Audit enforcement: a recorder that persists the first `CASE.PHOTO_ADDED` and suppresses the second rolls back the whole batch (case version, attachments, audit rows, event).
- [ ] **Step 2:** `attachLivePhotos` under the existing parent lock; lifecycle `addPhotos` method calling `LivePhotoAuditor` once per file; inject `Clock` into the attachment service.
- [ ] **Step 3: Commit** `feat(casefile): let reporters add photos to live cases [CT-018]`.

### Task 7: Transactions, HTTP and contract

- [ ] **Step 1 (RED):** `LifecycleAuditFailureTest`/`LifecycleAuditMissingTest`: recorder failure or a missing record rolls back case, files and events for every operation (CT-017 pattern, production proxy). Reopen isolation: a recorder that persists `CASE.STATUS_CHANGED` but suppresses only `CASE.CLOSING_STATEMENT_SET` rolls back status, statement, timestamps, version, audit rows and event delivery. `LifecycleEventDeliveryTest`: events reach an after-commit listener only on commit, never on rollback. `CaseLifecycleEndpointTest` (reporter): guest `401`; `GET /{id}` own vs foreign (`404`); actor from the token, never the body; validation `400`; error codes match the D13 order. `AdminCaseLifecycleEndpointTest`: registered user `403`; moderator and Super Admin allowed; own-case takedown/restore `403`. `OpenApiContractTest`: the eight operations and schemas, including `presentation`.
- [ ] **Step 2:** `CaseLifecycleController`, `AdminCaseLifecycleController`, OpenAPI. Run `CrowdtraceModulesTest`.
- [ ] **Step 3: Commit** `feat(casefile): expose case lifecycle endpoints [CT-018]`.

### Task 8: Docs and verification

- [ ] Changelog (Unreleased) with the handoffs below; tick CT-016, CT-017, CT-018 and CT-020 in `TASKS_INDEX.md`. `CrowdtraceModulesTest` and the full suite pass with the OrbStack env (see memory).
- [ ] **Commit** `docs(casefile): record CT-018 decisions [CT-018]`.

---

## Handoffs

- **CT-021/022:** public list and detail must go through `CaseMapper`'s public projection so D9's withholding and `presentation` apply; taken-down cases stay excluded by the `APPROVED` filter.
- **CT-023:** photo delivery must refuse files of a taken-down case. Any path that removes or replaces evidence must lock the case and force-increment its version (CT-017 handoff, still binding).
- **CT-026:** block new follows on a case that isn't `APPROVED`; keep existing follow rows so a restore needs no repair; allow unfollow.
- **CT-027:** consume `CaseStatusChangedEvent` and `CasePhotosAddedEvent` for followers; `CaseTakenDownEvent`/`CaseRestoredEvent` for the reporter (generic text, never the reason). Dedup on `eventId`.
- **CT-028:** reporter email listens to `CaseStatusChangedEvent`, including reporter-initiated changes; look up the address at send time.
- **CT-032:** retention eligibility is `resolved_at`/`closed_at` older than 12 months; a reopen clears them (D6).
- **Future:** live-case field edits need their own review and CT-014 re-detection design; police closing statements need a privileged role.
