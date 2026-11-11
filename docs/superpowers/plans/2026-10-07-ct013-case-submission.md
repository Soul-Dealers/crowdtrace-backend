# CT-013 — Validated Case Submission with Mandatory Report and Consent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/13
**Phase:** 2 — Case Registry and Submission
**Depends on:** CT-009 (authenticated principal), CT-011 (`V12` schema, D2/D4/D5), CT-012 (entities, repositories, `CaseMapper`), CT-015 (`CaseFileUploadService`, `CaseFileAttachmentService`)
**Unblocks:** CT-014 (consumes `CaseSubmittedEvent`), CT-017 (cases arrive in the queue as `SUBMITTED`), CT-018 (reuses the upload endpoint for live-case photos)

**Goal:** A reporter can upload a report and photos, then submit a case. The case enters review only when it has a valid report, explicit consent on the current policy version, and valid public and sensitive details. The response confirms the case without echoing anything sensitive back.

**Architecture:** Everything lives in `modules.casefile`. Two controllers (`CaseFileController`, `CaseSubmissionController`) delegate to `CaseFileUploadService` (existing) and a new `CaseSubmissionService`. One `@Transactional` submission creates the case, its sensitive details and its consent row, then attaches the files via CT-015's `MANDATORY` attachment service, so the whole submission succeeds or fails together. Duplicate detection is decoupled: the transaction publishes `CaseSubmittedEvent`, and CT-014 consumes it after commit.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Spring Modulith (core only, so no durable event registry), PostgreSQL 16 + Testcontainers, JUnit 5, AssertJ, MockMvc. No new dependencies. **No new migration.**

**Spec:** `docs/product-spec.md` §3.2 (submission flow), §3.5 (duplicates never block), §5.1 (field visibility), §7.2 (consent), US-01; `docs/tasks/phase_2_case_registry.md`; CT-011 plan D2/D4/D5; CT-015 plan D2/D14/D15/D17 and handoff notes.

---

## Decisions

Settled by Edem. Design reviewed between Claude and Codex ("Astra"), who analysed it independently and then cross-reviewed each other's points; both agreed on every point below.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Endpoints:** `POST /api/user/case-files` (multipart: `file` + `purpose=REPORT\|PHOTO`) and `POST /api/user/cases` (JSON). Both use `@RequiresRegisteredUser` | The case API is split by audience: `/api/public`, `/api/user`, `/api/admin` (OpenAPI contract). Identity's `/api/v1/auth` is a separate, older convention and stays as it is. `/api/user/**` is authenticated by the default `anyRequest().authenticated()` rule. |
| D2 | **Request shape: `reportFileIds` (required, ≥1) + `photoFileIds` (optional, ≤5)**, not a combined `fileIds` list | Edem: a submission may carry **one or more** reports. Separate slots turn a missing report into a field-level 400 (`reportFileIds: must not be empty`), which the AC calls an "actionable validation error". The PRD only fixes the count at "a report". |
| D3 | **Slot↔purpose check lives in the attachment service.** Replace `attachSubmission(caseId, reporterId, List<Long>)` with `attachSubmission(caseId, reporterId, List<Long> reportFileIds, List<Long> photoFileIds)`. Each id must have the purpose of its slot, and ids must be distinct across both slots. All CT-015 rules stay as they are: ownership, live, unattached, case-first then id-ordered locks, `MANDATORY`, photo cap | All attach rules stay in one service. The list version has no production callers, so only `CaseFileAttachmentServiceTest` needs migrating. A PHOTO id sent in the report slot gets a `ValidationException`. Missing, foreign and deleted files keep CT-015's indistinguishable `NotFoundException`. |
| D4 | **Initial state:** `reviewStatus = SUBMITTED`, `caseStatus = null`, `submittedAt = now(clock)` | CT-011 already chose `SUBMITTED`. The V12 CHECK forbids an outcome on an unpublished case. `UNDER_REVIEW` belongs to CT-017. |
| D5 | **`priority_minor = age < 18`, set by CT-013 at creation.** CT-014 keeps duplicate detection and its confidence/reason metadata only | Edem: the flag is a pure function of age, and `idx_cases_review_queue` sorts on it from the moment of insert. Setting it after commit would leave a minor's case unprioritised if that step failed. **Update CT-014's issue and `phase_2_case_registry.md`.** |
| D6 | **Duplicate seam: publish `CaseSubmittedEvent(Long caseId)` inside the submission transaction.** CT-014 consumes it with `@TransactionalEventListener(AFTER_COMMIT)` and writes in `REQUIRES_NEW`. `duplicate_flag` stays `false` at insert | Spring logs AFTER_COMMIT listener exceptions and does not propagate them, so detection can never fail an intake ("duplicates never block"). With Modulith core only, delivery is best-effort with no replay. That is acceptable for a review *signal*; revisit if CT-014 needs guarantees. |
| D7 | **Consent:** the request carries `consent {accepted, version, source}`. `accepted` must be `true`. `version` must equal `crowdtrace.consent.sensitive-data-version` (≤32 chars, validated at startup), otherwise 400 "consent version is outdated". `source` must be one of `WEB\|MOBILE\|API`. The service stores `SENSITIVE_DATA_COLLECTION`, the authenticated `userId`, the **server's** version and `acceptedAt = now(clock)` | Covers the AC "version/timestamp/source are stored". The client can only *confirm* the version it displayed. It can't choose one. CT-011 D4 rules out IP/user agent. Source is client-declared but validated against the enum, which is low-stakes attribution. |
| D8 | **Field validation** (Bean Validation on the request, mirroring V12): required `fullName` ≤255, `age` (boxed `Integer`, required, 0..130), `gender`, `lastSeenDate` (required, not after today in an injected UTC `Clock`, not before 1900-01-01), `region`, `lastSeenLocation` ≤500, `physicalDescription`, `clothing`, `circumstances`, `publicContactNumber` ≤32. Nested `sensitiveDetails` is required: `reporterRelationship` is required and ≤100, everything else optional. File ids are positive | Age is **age at disappearance** (PRD glossary "Minor flag"). A boxed `age` tells "omitted" apart from 0. The DB CHECKs are the backstop, not the validator. Timestamps come from the clock rather than `@PrePersist`, so tests can pin them. |
| D9 | **Response: `201 ApiResponse.success({caseId, reviewStatus, submittedAt})`**, nothing more | Edem: the numeric id is the case reference (CT-011 D2). The response carries no identity, sensitive values, file keys or flags. Formatting a display reference is the frontend's job. |
| D10 | **Upload limit: 20 successful uploads per user per day.** New `RateLimitScope.USER`, policy `user-case-upload: { limit: 20, window: 24h }`, keyed by user id. Each attempt is charged before the upload, and the charge is **refunded** if the upload is rejected (`ValidationException`). Fail-closed: a store error returns 503, as in `IdentityRateLimitGuard`. Over the limit returns 429 | Edem: 20/day. One full submission is up to 6 uploads (1 report + 5 photos), and the limit leaves room for retries, a second case and CT-018 photo additions. Refunding rejected files means one bad file can't lock a reporter out. `PostgresRateLimiter` charges in `REQUIRES_NEW`, so a charge survives the upload's rollback. V8's `scope` is `VARCHAR(16)` with no CHECK, so `USER` needs no migration. |
| D11 | **Current user:** add `Long userId()` to the public `SecurityUser` record. Casefile controllers take `@AuthenticationPrincipal SecurityUser` and never call `user()` | `user()` returns identity's internal model. Calling it from casefile would break the module boundary that `CrowdtraceModulesTest` checks. |
| D12 | **Multipart errors:** add `MaxUploadSizeExceededException` → 413 and `MissingServletRequestPartException` / missing `purpose` → 400 to `GlobalExceptionHandler`, in the standard error format | Today both fall through to the generic handler as a **500**. Spring's multipart limit (10MB) and `crowdtrace.case-files.max-size-bytes` stay equal. Raise them together (CT-015 handoff). |

### Discarded

- A combined `fileIds` list → separate slots (D2).
- Exactly one report → one or more (Edem, D2).
- A separate `caseReference` string such as `"CT-<id>"`, or a public UUID → numeric `caseId` (Edem, D9). Reopening CT-011 D2 is deferred to CT-021/022 if public URLs must be non-enumerable.
- A pending-unattached-upload cap → the daily rate limit is the only quota (Edem). Orphaned uploads wait for the phase-6 sweep.
- `@RateLimit` (the IP layer) as the per-user control → it fails open and is keyed by IP. It doesn't meet the issue's "per-user" requirement.
- Client-chosen consent version or timestamp, or IP/user-agent capture → D7, CT-011 D4.
- Initial status `UNDER_REVIEW`, or an initial `caseStatus = MISSING` → D4 (V12 forbids an outcome before approval).
- Duplicate detection inside the submission transaction → D6.
- Persisting the client filename or content type → never stored (CT-015 D4).

### Out of scope

Duplicate matching and its metadata (CT-014), review queue and decisions (CT-017), lifecycle, takedown and live-case photo additions (CT-018), audit events (CT-020, see [[audit-is-its-own-service]]), photo delivery, report download and EXIF stripping (CT-023), the S3 adapter (CT-030), centralized consent and log-redaction policy (CT-031), stale-upload sweep (phase 6), notifications. No legal consent wording: the version string is a config value until counsel delivers it (PRD open question 6).

---

## Global Constraints

- **Branch:** `feat/CT-013-Case-Submission`, cut **after CT-015 merges**.
- **Commits carry no Claude attribution** (`~/.claude/CLAUDE.md`). Style: `feat(casefile): … [CT-013]`.
- **Before tests:** `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true`.
- Service and endpoint tests run against Postgres + Flyway. The test profile sets `rate-limit.enabled=false`, so the rate-limit tests opt back in (as the OTP rate-limit tests do).
- **No new migration.**
- `casefile` never imports `modules.identity.internal`, and `CrowdtraceModulesTest` stays green.
- Errors use the existing `ValidationException`, `NotFoundException`, `ConflictException`, `RateLimitExceededException` and `RateLimitUnavailableException`. No message includes keys, checksums or sensitive field values.
- Logs never contain request bodies, names, sensitive details or file contents.
- The suite is green at every commit.

## Review Focus

1. **A partial submission persisted.** A failed attach, consent check or validation must leave no `cases`, `case_sensitive_details` or `case_consents` row, and no file attached. Pinned by `CaseSubmissionServiceTest.rollsBackEverythingWhenAttachFails`.
2. **Sensitive data leaking through the response.** Pinned by `CaseSubmissionResponseContractTest` (exact JSON field allowlist).
3. **Attaching someone else's file, or a photo posing as a report.** Pinned by the D3 attachment tests and the endpoint ownership test.
4. **Duplicate detection blocking intake.** Pinned by `CaseSubmittedEventTest` with a throwing AFTER_COMMIT listener.

---

## File Structure

`C` = `src/main/java/com/souldealers/crowdtracebackend/modules/casefile/`

| File | Status | Responsibility |
|---|---|---|
| `modules/identity/SecurityUser.java` | modify | add `userId()` (D11) |
| `shared/ratelimit/RateLimitScope.java` | modify | add `USER` (D10) |
| `shared/exception/GlobalExceptionHandler.java` | modify | multipart 413/400 (D12) |
| `shared/config/OpenApiConfig.java` | modify | document both endpoints |
| `src/main/resources/application.yaml` | modify | `user-case-upload` policy; `crowdtrace.consent.sensitive-data-version` |
| `C/CaseFileAttachmentService.java` + `internal/CaseFileAttachmentServiceImpl.java` | modify | slot-aware `attachSubmission` (D3) |
| `C/CaseFileController.java` | new | `POST /api/user/case-files` |
| `C/CaseSubmissionController.java` | new | `POST /api/user/cases` |
| `C/CaseSubmissionService.java` | new | public port: `submit(Long reporterId, CaseSubmissionRequest)` |
| `C/CaseSubmissionRequest.java` (+ nested `SensitiveDetailsInput`, `ConsentInput` records) | new | validated request DTOs |
| `C/CaseSubmissionResponse.java` | new | `{caseId, reviewStatus, submittedAt}` |
| `C/CaseSubmittedEvent.java` | new | `record CaseSubmittedEvent(Long caseId)`, the public event for CT-014 |
| `C/internal/CaseSubmissionServiceImpl.java` | new | transaction orchestration, D4–D7 |
| `C/internal/ConsentProperties.java` | new | `@ConfigurationProperties("crowdtrace.consent")`, validated |
| `C/internal/CaseUploadRateLimitGuard.java` | new | charge, refund, fail-closed (D10) |
| `C/internal/LastSeenDateValidator` (+ `@ValidLastSeenDate`) | new | clock-based date rule (D8) |

---

### Task 1: Plumbing — `SecurityUser.userId()`, `USER` scope, multipart errors

- [ ] Test: `SecurityUser.userId()` returns the wrapped user's id. The `CrowdtraceModulesTest` boundary stays green.
- [ ] Test: an oversized multipart upload returns 413 and a missing `file` part returns 400, both in the standard error format, never 500.
- [ ] Implement, then commit `feat(shared): add user rate-limit scope and multipart error mapping [CT-013]`.

### Task 2: Slot-aware attachment (D3)

- [ ] Migrate `CaseFileAttachmentServiceTest` to the new signature. Add tests for: empty `reportFileIds` → validation; a PHOTO id in the report slot → validation; a REPORT id in the photo slot → validation; the same id in both slots → validation; multiple reports → accepted; >5 photos → validation (cap unchanged). Keep the existing ownership, deleted, already-attached and race tests.
- [ ] Implement in `CaseFileAttachmentServiceImpl`, keeping the case-first then id-ordered locking.
- [ ] Commit `refactor(casefile): attach submission files by report and photo slot [CT-013]`.

### Task 3: Upload endpoint + per-user limit (D1, D10)

- [ ] `CaseUploadRateLimitGuard`: `record(USER, "user-case-upload", userId)`. A denial throws `RateLimitExceededException` (429). A `DataAccessException` throws `RateLimitUnavailableException` (503). `refund` = `reset`-style release of **one** charge. If the limiter only offers whole-bucket `reset`, add a single-decrement `release` to `RateLimiter`/`PostgresRateLimiter`. Never reset the whole bucket on a rejection.
- [ ] `CaseFileController.upload(@AuthenticationPrincipal SecurityUser, @RequestParam CaseFilePurpose purpose, @RequestPart MultipartFile file, @RequestParam Optional<String> sha256)`: guard → `upload(userId, purpose, file.getInputStream(), sha256)` → on `ValidationException` refund and rethrow. Return `201 ApiResponse.success(CaseFileMetadataResponse)`.
- [ ] Endpoint tests (Postgres, rate limiting enabled): REPORT PDF → 201 with PRIVATE visibility; PHOTO JPEG → 201 with PUBLIC visibility; a PDF uploaded as PHOTO → 400; oversize → 413; unauthenticated → 401; 21st successful upload in a day → 429; rejected files don't consume the quota; response has no key or checksum (reuse the CT-015 contract).
- [ ] Commit `feat(casefile): add authenticated case-file upload with per-user daily limit [CT-013]`.

### Task 4: Submission request, validation, consent config (D7, D8)

- [ ] `CaseSubmissionRequest` with nested records and Bean Validation. `ConsentProperties` (`@Validated`, `@NotBlank @Size(max = 32)`). `@ValidLastSeenDate` uses the injected `Clock`.
- [ ] Validation tests: each missing required field; age −1/131 rejected and 0/130 accepted; tomorrow's date rejected and today's accepted; 1899-12-31 rejected; `consent.accepted=false` or missing → error naming `consent.accepted`; missing `reportFileIds` → error naming `reportFileIds`; over-length strings.
- [ ] Commit `feat(casefile): validate case submission requests and consent policy [CT-013]`.

### Task 5: `CaseSubmissionService` (D4–D7, D9)

- [ ] In one `@Transactional submit(reporterId, request)`: check the consent version (stale → `ValidationException`). Save `CaseRecord` (SUBMITTED, null outcome, `priorityMinor = age < 18`, `duplicateFlag = false`, `submittedAt = now`). Save `CaseSensitiveDetails`. Save `CaseConsent` (server version, `acceptedAt = now`). Call `attachSubmission(case.id, reporterId, reportFileIds, photoFileIds)`. Publish `CaseSubmittedEvent`. Return `CaseSubmissionResponse`.
- [ ] Service tests (Postgres): a valid submission persists all three rows plus attached files; age 17 → `priorityMinor` true and age 18 → false; consent stores the server version, the fixed-clock timestamp and the source; a stale version → 400 with nothing persisted; **`rollsBackEverythingWhenAttachFails`** (a foreign file → no case, sensitive-details or consent row, and the reporter's own files stay unattached); resubmitting the same files → `ConflictException` (this is the natural double-submit guard).
- [ ] Commit `feat(casefile): submit cases with consent and attached evidence atomically [CT-013]`.

### Task 6: Submission endpoint, event seam, contract (D1, D6, D9)

- [ ] `CaseSubmissionController.submit(@AuthenticationPrincipal SecurityUser, @Valid @RequestBody CaseSubmissionRequest)` → `201`.
- [ ] Endpoint tests: valid → 201 and `reviewStatus=SUBMITTED`; missing report → 400 naming `reportFileIds`; missing consent → 400; invalid date/age → 400; unauthenticated → 401; another user's file id → 404 (ownership).
- [ ] `CaseSubmissionResponseContractTest`: the JSON has exactly `caseId`, `reviewStatus`, `submittedAt`.
- [ ] `CaseSubmittedEventTest`: a throwing `@TransactionalEventListener(AFTER_COMMIT)` in the test context → submission still returns 201 and the case is persisted. On rollback the event is not delivered.
- [ ] Commit `feat(casefile): expose case submission endpoint and submitted-case event [CT-013]`.

### Task 7: OpenAPI + docs

- [ ] Add both paths to `OpenApiConfig`: auth, multipart schema, request/response schemas, the 400/401/404/409/413/429/503 responses, and that a duplicate never blocks. Update the contract test if it pins paths.
- [ ] Update `docs/tasks/phase_2_case_registry.md` and the CT-014 issue: minor flag → CT-013 (D5), CT-014 consumes `CaseSubmittedEvent` (D6). Add a `docs/changelog.md` entry.
- [ ] Commit `docs(casefile): document case submission and upload contract [CT-013]`.

---

## Acceptance-criteria trace

| AC | Where |
|---|---|
| Valid submissions enter review | D4; Task 5 valid test, Task 6 201 test |
| Missing report or consent returns actionable validation errors | D2, D7, D8; Task 4 field-named errors, Task 6 endpoint tests |
| Consent version/timestamp/source are stored | D7; Task 5 consent test |
| Response returns a safe case reference | D9; `CaseSubmissionResponseContractTest` |
| Possible duplicates do not block submission | D6; `CaseSubmittedEventTest` |
| Report + up to 5 photos (10 MB, JPEG/PNG, reports also PDF); only own unattached uploads attach | CT-015 D5/D14 + D3; Task 2, Task 3 endpoint tests, Task 6 ownership test |
| Tests: valid, missing file, missing consent, invalid dates/age, ownership, safe response, upload, photo cap, foreign file | Tasks 2–6 |

## Handoff notes

- **→ CT-014:** Listen for `CaseSubmittedEvent` with `@TransactionalEventListener(AFTER_COMMIT)` and write in `REQUIRES_NEW`. Set `duplicate_flag` and store the confidence/reason metadata (needs its own migration). **Do not** set `priority_minor`, which CT-013 already owns (D5). Delivery is best-effort; add the Modulith JPA event registry if replay is needed.
- **→ CT-017:** New cases arrive as `SUBMITTED` with `priority_minor` already set. Moving a case to `UNDER_REVIEW` is CT-017's job.
- **→ CT-018:** Live-case photos = `POST /api/user/case-files?purpose=PHOTO` (counts toward the 20/day limit) + `attachPhotos`.
- **→ CT-020:** Submission writes no audit event today. Add a "case submitted" action when the recorder exists, if the action vocabulary requires one.
- **→ CT-031:** Consent version is a config value (`crowdtrace.consent.sensitive-data-version`). Replace it once counsel finalises the wording. CT-031 centralizes the policy.
- **→ Phase 6:** Uploads that are never attached accumulate. Edem chose no pending cap, so the sweep job owns them.
