# CT-017 — Moderator Review Queue and Decisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Phase:** 3 — Review, Moderation, Lifecycle, and Audit
**Depends on:** CT-012 (admin projections, review queue), CT-014 (duplicate matches), CT-016 (`case_reviews`), CT-020 (audit recorder, V17 append-only reviews). Branch from `dev` once PR #52 (CT-020) is merged.
**Unblocks:** CT-018 (lifecycle after approval), CT-027 (in-app delivery of review decisions)

**Goal:** Moderators and Super Admins can list pending cases, open one with its evidence metadata, duplicate matches and review history, and approve or reject it. Each decision changes the case, appends a `case_reviews` row, records a `CASE.APPROVED`/`CASE.REJECTED` audit event and emits a reporter notification event, all in one transaction. Approval publishes only the existing public allowlist; rejection keeps the case private.

**Architecture:** `governance` orchestrates and owns review history (`CaseReview` entity, insert-only writer, history reader, `ReviewController`). `casefile` owns case state and exposes a public `CaseReviewMutationService` (`MANDATORY` transaction) that locks the case and transitions it. `identity` exposes an actor lookup (email → id, role). `notification` owns the `CaseReviewDecidedEvent` contract; nothing consumes it until CT-027.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA, PostgreSQL 16, Testcontainers, JUnit 5, AssertJ. No new dependencies.

**Spec:** `docs/tasks/phase_3_governance.md` CT-017; `docs/product-spec.md` §3.3 (L96–102); `docs/blueprint.md` L363 (review namespace); CT-016 plan D3–D6 and concurrency handoff (L49); CT-020 plan D9, D7c and handoffs.

---

## Decisions

Designed by Claude and Codex ("Astra") independently, then reconciled. Both designs agreed on ownership, the single transaction, the state machine, deferring request-changes, the notes rules, and metadata-only evidence. Astra's additions were adopted: the stale-version check (D4), the report recheck before approval (D5), the richer detail view (D9), actor lookup from the principal (D2), the notification-owned event with a dedup id (D10), and the attachment version bump (D13). D3, D10, D11 and D12 were settled by Edem. Astra's review of the final plan found six defects, all fixed: annotation placement (D15), duplicate detection bypassing the version check (D13a), the detail snapshot (D9), the filter module cycle (D16), projection/mapper work (Task 5), and the stale OpenAPI placeholders (Task 6).

| # | Decision | Rationale |
|---|---|---|
| D1 | **`governance` owns review decisions and history; `casefile` owns case state.** Governance calls the public `casefile.CaseReviewMutationService` with scalar ids. It never touches `cases` or its repository. | CT-016 assigns review mapping to governance (D1). Module rule: cross-module writes go through public services (`TASKS_INDEX.md` conventions). |
| D2 | **Actor comes from the principal, never the body.** `@RequiresModerator` on the controller and on the public `ReviewService` methods. A new public `identity.ActorLookup.byEmail(email)` returns `(userId, role)`. Actor role maps to `AuditActorRole` like `AuditRoleMapper`. | Same pattern as verification decisions (`AdminVerificationController`, `VerificationServiceImpl.findUser`). Request JSON can't spoof the actor, source or time. |
| D3 | **No self-review, including Super Admins (Edem).** Deciding a case you reported → `403`. | Matches verification (`VerificationServiceImpl.java:210`). |
| D4 | **One transaction, serialized per case:** lock case (`findByIdForUpdate`, `CaseRecordRepository.java:34`) → check state + `expectedCaseVersion` + report → transition → insert review → record audit → publish event. Mutation service is `MANDATORY`, so the lock is held until governance commits. A version mismatch or already-decided case → `409`. | Two moderators on one case get one success and one `409`. A stale detail screen can't approve a case that changed under it. Retries never append a second decision. |
| D5 | **State machine: `{SUBMITTED, UNDER_REVIEW} → APPROVED \| REJECTED`.** Approval sets `case_status = MISSING`, `approved_at = now(clock)`. Rejection leaves both null. Any other state → `409`. Approval requires a live `REPORT` file, otherwise `409`. GET never changes state. | Matches V12's checks (`case_status` only when approved). The report is mandatory evidence at submission; it must still exist when approved. |
| D6 | **Approval publishes only the existing public allowlist** (`CaseMapper` public projection). No new public fields. File visibility unchanged. | Public reads already require `APPROVED` (`CaseQueryServiceImpl.java:39`). The allowlist is the safety boundary; CT-017 only flips the status. |
| D7 | **Deferred: request-changes and re-review after rejection.** No endpoint, no surrogate rejection. A future resubmit flow appends a new review row; history is never overwritten. | `case_reviews` allows only `APPROVED`/`REJECTED` (V14) and is append-only (V17). No reporter edit flow exists. |
| D8 | **Notes:** reject requires notes, non-blank after Unicode trim, ≤ 2000. Approve notes optional; blank → null. Validated in the DTO and the service. Notes are admin-only: never in audit, events or reporter responses. | DB check is the backstop (V14). CT-020 D6: notes stay on the domain row. |
| D9 | **Detail = admin projection + case version + duplicate matches + review history,** in one read-only `REPEATABLE_READ` transaction on the outer governance method. Files show metadata plus `accessAvailable = false` in a review-specific `ReviewEvidenceResponse` wrapper (the shared `CaseFileMetadataResponse` stays unchanged), with no URL or storage key. | Moderators need matches and prior decisions to decide. Under READ COMMITTED, the separate queries (`CaseQueryServiceImpl.java:67-71` plus matches and history) could show a pending case next to a committed approval. Signed access is CT-023/030. |
| D10 | **Reporter is notified in-app for both decisions (Edem).** Governance publishes `notification.CaseReviewDecidedEvent(eventId = "case-review:" + reviewId, recipientId, caseId, reviewId, decision, occurredAt)` inside the transaction; consumers run after commit. No notes, email or evidence in the payload. **Email later is additive:** CT-028 (or a follow-up) adds a second listener that looks up the address at send time. CT-017 needs no change. | Ids-only payload keeps personal data out of events. `eventId` lets CT-029 deduplicate. A rolled-back decision emits nothing deliverable. |
| D11 | **Ships before evidence download and delivery exist (Edem),** but no operational rollout until CT-023/030 (download) and CT-027 (in-app delivery) land. | Unblocks the backend sequence without implying moderators can use it yet. |
| D12 | **Rejected reporters see no reason for now (Edem).** A reporter-safe message comes with resubmission. | Internal notes must never leak. |
| D13 | **Attaching files bumps the case version.** `CaseFileAttachmentServiceImpl` locks the case but only changes files, so `@Version` stays the same. Touch the case so the version increments. | Without it, D4's stale check misses evidence changes. |
| D13a | **Adding duplicate matches bumps the case version too.** `DuplicateDetectionServiceImpl.java:40-46` saves match rows and only sets `duplicateFlag`, which is a no-op when already true. Touch the case under its lock whenever matches are added. | Detection runs after submission commits, so a moderator can open the case before it finishes. Without the bump, a decision using the earlier version would pass D4. |
| D14 | **Case timestamps use the injected UTC `Clock`.** `CaseRecord.onCreate/onUpdate` call `LocalDateTime.now()` (JVM zone, `CaseRecord.java:117`). | Mixed zones break ordering, and V18 pinned DB defaults to UTC. Fix it as CT-017 adds mutations. |
| D15 | **Audit:** the concrete `ReviewServiceImpl.approve`/`reject` methods each carry `@Transactional` and their action-specific `@AuditedOperation` (not just the interface) and record `CASE.APPROVED`/`CASE.REJECTED` on target `CASE`/caseId with metadata exactly `{reviewId}`. `CaseReview` is `@Immutable`, saved through an insert-only writer. | Closed schemas already exist (`AuditAction.java:20`). Same pattern as verification (CT-020 Task 6, `VerificationServiceImpl.java:87`). The aspect matches `@annotation` on the concrete target method (`AuditedOperationAspect.java:18`), so an interface-only annotation would skip enforcement. |
| D16 | **Queue:** optional filters `status` (default both pending states), `priorityMinor`, `duplicateFlag`, `submittedFrom`/`submittedTo` (`[from, to)`). Fixed order `priority_minor DESC, submitted_at ASC, id ASC`; client sort ignored. Default size 20, max 50. Offset pagination; membership can change between pages (documented). Filter indexes only if PostgreSQL plans show they're needed. Casefile owns the public `CaseReviewQueueQuery` record; governance's HTTP `ReviewQueueFilter` translates into it. | Passing a governance type into `CaseQueryService` would create `casefile → governance → casefile`, which `CrowdtraceModulesTest` rejects. | Keeps CT-012's ordering. CT-016 D3 deferred filter indexes until query shapes were fixed.
| D17 | **Endpoints** under `/api/v1/admin/review/cases`. Errors reuse ProblemDetail codes: `400` validation, `401`, `403` role/self-review, `404` unknown case, `409` stale/decided/missing report, `500` audit failure. | Blueprint namespace with the `/api/v1` prefix every other controller uses. |

### Endpoints

| Method + path | Body | Response |
|---|---|---|
| `GET /api/v1/admin/review/cases` | query filters (D16) | `ApiResponse<PagedResponse<AdminCaseSummaryResponse>>` |
| `GET /api/v1/admin/review/cases/{id}` | — | `ApiResponse<ReviewCaseDetailResponse>` |
| `POST /api/v1/admin/review/cases/{id}/approve` | `ApproveCaseRequest(expectedCaseVersion, reviewNotes?)` | `ApiResponse<CaseReviewDecisionResponse>` |
| `POST /api/v1/admin/review/cases/{id}/reject` | `RejectCaseRequest(expectedCaseVersion, reviewNotes)` | `ApiResponse<CaseReviewDecisionResponse>` |

`CaseReviewDecisionResponse(reviewId, caseId, reviewStatus, caseVersion, reviewerId, reviewNotes, decidedAt)`.

---

## File Structure

Paths are under `src/main/java/com/souldealers/crowdtracebackend/`.

| Path | Action | Decision |
|---|---|---|
| `modules/governance/ReviewController.java`, `ReviewService.java` | Create | D2, D17 |
| `modules/governance/{ApproveCaseRequest,RejectCaseRequest,ReviewCaseDetailResponse,CaseReviewDecisionResponse,CaseReviewHistoryResponse,ReviewQueueFilter}.java` | Create | D8, D9, D16 |
| `modules/governance/internal/ReviewServiceImpl.java` | Create | D3–D5, D15 |
| `modules/governance/internal/model/CaseReview.java` | Create | D15 (`@Immutable`) |
| `modules/governance/internal/repository/{CaseReviewWriter,CaseReviewWriterImpl,CaseReviewRepository}.java` | Create | insert-only + history reads |
| `modules/casefile/CaseReviewMutationService.java`, `CaseReviewTransition.java`, `DuplicateMatchResponse.java`, `CaseReviewQueueQuery.java` | Create | D1, D4, D5, D9, D16 |
| `modules/governance/ReviewEvidenceResponse.java` | Create | D9 |
| `modules/casefile/internal/CaseMapper.java` | Modify | pass `version` to `AdminCaseResponse` (positional constructor, L48) |
| `modules/casefile/internal/duplicates/DuplicateDetectionServiceImpl.java` | Modify | D13a |
| `shared/config/OpenApiConfig.java` | Modify | retire `/api/admin/cases/review` and `/api/admin/cases/{caseId}/decision` placeholders (L346) |
| `modules/casefile/internal/CaseReviewMutationServiceImpl.java` | Create | D4, D5 |
| `modules/casefile/CaseQueryService.java`, `internal/CaseQueryServiceImpl.java`, `internal/repository/CaseRecordRepository.java` | Modify | D9, D16 |
| `modules/casefile/AdminCaseResponse.java` | Modify | add `version` (additive) |
| `modules/casefile/internal/CaseFileAttachmentServiceImpl.java` | Modify | D13 |
| `modules/casefile/internal/model/CaseRecord.java` | Modify | D14 |
| `modules/identity/ActorLookup.java`, `ActorView.java`, `internal/service/ActorLookupImpl.java` | Create | D2 |
| `modules/notification/CaseReviewDecidedEvent.java` | Create | D10 |
| `docs/changelog.md`, OpenAPI contract | Modify | |

---

### Task 1: Review persistence

- [ ] **Step 1 (RED):** `CaseReviewRepositoryTest`: insert approved and rejected reviews through the writer (source defaults to `HUMAN`, UTC `created_at`); the writer rejects an entity with an id; history for a case orders by `created_at, id`; mapping matches V14 (`SchemaValidationTest` stays green).
- [ ] **Step 2:** `CaseReview` (`@Immutable`, scalar `caseId`/`reviewerId`), writer using `persist` + `flush` (CT-020 appender pattern), read-only history repository.
- [ ] **Step 3: Commit** `feat(governance): map case review history [CT-017]`.

### Task 2: Casefile transition port and the version and clock fixes

- [ ] **Step 1 (RED):** `CaseReviewMutationServiceTest` (PostgreSQL): every source state × decision (allowed → transitioned, else `409`); version mismatch → `409`; missing report blocks approval only; requires an active transaction (`MANDATORY`); approval sets `MISSING` + `approved_at` from the clock. `CaseFileAttachmentServiceTest`: attaching bumps the case version. `DuplicateDetectionServiceTest`: an already-flagged case gaining another match bumps the version, and a decision using the earlier version then gets `409`. `CaseRecord` timestamps come from the clock.
- [ ] **Step 2:** Implement the port, D13, D13a and D14.
- [ ] **Step 3: Commit** in pieces: `fix(casefile): stamp case times from the UTC clock [CT-017]`, `fix(casefile): bump case version when evidence or duplicate matches change [CT-017]`, `feat(casefile): add the review transition port [CT-017]`.

### Task 3: Actor lookup

- [ ] **Step 1 (RED):** `ActorLookupTest`: active user → `(id, role)`; unknown or inactive → `UnauthorizedException`.
- [ ] **Step 2:** Implement in identity; add to `CrowdtraceModulesTest` expectations if needed.
- [ ] **Step 3: Commit** `feat(identity): look up the acting user for cross-module decisions [CT-017]`.

### Task 4: Decisions (service)

- [ ] **Step 1 (RED):** `ReviewDecisionServiceTest`: approve and reject happy paths return the decision; reject notes rules (D8); self-review → `403` for moderator and Super Admin; unknown case → `404`. `ReviewDecisionConcurrencyTest`: two moderators approve or reject at once → one success, one `409`, exactly one review row. `ReviewAuditTest`: one `CASE.APPROVED`/`REJECTED` event with `{reviewId}` and the right actor role; a rejected transition writes no review, audit or event. `ReviewAuditFailureTest`/`ReviewAuditMissingTest`: recorder failure or a missing record rolls back case, review and event (CT-020 pattern), exercised through the production proxy so enforcement on the concrete methods is proven. `ReviewNotificationEventTest`: one event per decision, delivered after commit only, ids only.
- [ ] **Step 2:** `ReviewServiceImpl` + `CaseReviewDecidedEvent`.
- [ ] **Step 3: Commit** `feat(governance): approve and reject cases with audit and reporter events [CT-017]`.

### Task 5: Queue and detail

- [ ] **Step 1 (RED):** `ReviewQueueFilterTest`: each filter, defaults, fixed order with id tie-break, size cap, client sort ignored; `EXPLAIN` checks under `enable_seqscan = off`. `ReviewDetailTest`: version, matches, history order, evidence with `accessAvailable = false` and no storage key; an approval committed between the detail's queries doesn't produce a mixed view (REPEATABLE_READ). `CaseProjectionContractTest` updated for `AdminCaseResponse.version` only. `ReviewProjectionTest`: after approval the public projection exposes only the allowlist; a rejected case stays `404` publicly.
- [ ] **Step 2:** Extend `CaseQueryService` (taking `CaseReviewQueueQuery`) + repository + `CaseMapper`; add a measured index migration only if a plan needs it. Run `CrowdtraceModulesTest` before committing.
- [ ] **Step 3: Commit** `feat(governance): filter the review queue and show review detail [CT-017]`.

### Task 6: HTTP and contract

- [ ] **Step 1 (RED):** `ReviewEndpointTest`: role matrix (guest `401`, registered user `403`, moderator and Super Admin allowed); validation `400`; error codes from D17; actor never read from the body. `OpenApiContractTest` covers the four operations, and asserts the old `/api/admin/cases/review` and `/api/admin/cases/{caseId}/decision` placeholders are gone (replacing the current positive assertions at L85).
- [ ] **Step 2:** `ReviewController`, OpenAPI (add the four operations, retire the two placeholders).
- [ ] **Step 3: Commit** `feat(governance): expose review endpoints [CT-017]`.

### Task 7: Docs and verification

- [ ] Changelog (Unreleased). `CrowdtraceModulesTest` and the full suite pass with OrbStack env (see memory). Copy the handoffs into the PR body.
- [ ] **Commit** `docs(governance): record CT-017 decisions [CT-017]`.

---

## Handoffs

- **CT-018:** lifecycle transitions start from `APPROVED`. Reuse the case lock, `expectedCaseVersion` and D14's clock. Takedown is an audit action, never a review row.
- **CT-023/030:** replace `accessAvailable = false` with authorized signed access, audited as `CASE_FILE.PRIVATE_ACCESS_GRANTED`. Required before moderator rollout (D11).
- **CT-027:** consume `CaseReviewDecidedEvent` after commit for in-app notifications; deduplicate on `eventId`. Required before rollout (D11).
- **CT-028 / follow-up:** a review-decision email is a second listener on the same event that looks up the address at send time (D10). It needs Edem's approval as a new email category.
- **CT-029:** after-commit events aren't durable. Add retry and dedup; don't claim delivery.
- **Resubmission (future):** reopen a rejected case by appending a new review row, plus a reporter-safe reason (D7, D12).
- **Out of scope, flagged:** `CaseSubmissionController` is mounted at `/api/user/cases`, not `/api/v1/...`. Fixing that is a breaking path change for its own issue.
