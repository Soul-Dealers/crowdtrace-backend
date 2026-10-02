# CT-010 — Verified-Badge Request and Admin Decision Workflow

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/10
**Phase:** 1 — Identity, Authentication, and Roles
**Depends on:** CT-006 (identity persistence), CT-008 (role-based method authorization), CT-009 (public user projection)

---

## Context

A registered user may apply to have a credential confirmed — police officer, NGO staff, subject-matter expert — and an administrator reviews the evidence privately and grants a visible badge. The badge is a **trust signal, never a permission** (`docs/product-spec.md:70`, `docs/crowdtrace-spec.md:44`): there is deliberately no privileged investigator role in the MVP.

**The persistence for this already exists and is unused.** `V1__create_identity_tables.sql` created `verification_requests`, `VerificationRequest` maps it, `VerificationRequestRepository` queries it, and `UserServiceImpl.resolveBadgeType()` already derives the public badge from it (newest decision per type wins). Nothing can write a row — there is no endpoint and no service. CT-010 is the workflow layer over a data model that was built ahead of it.

**Outcome:** a user can submit and track their own request; a moderator can work a queue and approve or reject; a Super Admin can revoke a badge or grant one directly; evidence stays private; and a badge still unlocks nothing.

---

## Decisions

Settled with the user during planning. These replace a separate spec document.

| # | Decision | Rationale |
|---|---|---|
| D1 | **`VerificationType` becomes `POLICE`, `NGO`, `SUBJECT_MATTER_EXPERT`** (replacing `IDENTITY` / `ORGANIZATION`) | `PublicUserResponse.badgeType` is public, and the product spec's wording is what a reader is meant to see — *why* a contributor is credible, not merely that they are (`docs/product-spec.md:142`, `docs/crowdtrace-spec.md:40`). |
| D2 | **"Grant" is a distinct admin-initiated action**, not a synonym for approve | Lets an admin badge a known partner (e.g. an NGO being onboarded) who never applied. Writes a normal `APPROVED` row so `resolveBadgeType` needs no change. |
| D3 | **Moderator approves/rejects and reads the queue; Super Admin revokes and grants** | `docs/product-spec.md:192` gives moderators "process verification requests". Revoking strips a public trust signal, and granting bypasses user-submitted evidence — both are rarer and more consequential, so they sit with `SUPER_ADMIN`. |
| D4 | **No audit table, no `shared/audit` work.** Decisions are recorded on the request row (`reviewer_id`, `review_notes`, `reviewed_at`) plus a structured log line | The user's call: auditing becomes its own service. Phase 3 `CT-020` is chartered to "provide a transaction-safe audit recorder" (`docs/tasks/phase_3_governance.md:58`) and `shared/audit/AuditEvent.java` is its empty placeholder. Building a second one here would be thrown away. |

### Assumptions taken during planning

**A1 — Grant is keyed by email, not user id.** No endpoint in the API returns a user id: `UserResponse` is `displayName`, `email`, `role`, `accountStatus`, `createdAt`. A `/admin/users/{userId}/verification` path would be uncallable without a database query. Grant is therefore `POST /api/v1/admin/verification-grants` with the target's `email` in the body. A Super Admin already sees emails through `/api/v1/auth/users`, and no existing contract changes. *(Alternative, if you'd rather: add `id` to `UserResponse` — but that touches `/me`, `/users`, `UserResponseTest` and the OpenAPI schema.)*

**A2 — `OTHER` is not a badge type.** It appeared in the option preview but is excluded: a public badge reading "OTHER" tells a reader nothing, which defeats the point of exposing `badgeType`. Adding a fourth value later is a one-line enum change (see "No migration"). Raise it before Task 1 if you disagree.

**A3 — A granted badge's justification is visible to the badge holder.** It is stored in `evidence_reference`, which the applicant's own view returns. Admins must be told, so the grant endpoint's `@Operation` description says so explicitly. Internal reasoning belongs in `reviewNotes`, which the own-view never returns.

### Accepted gap — deferred to CT-020

The request row holds **one** `reviewer_id` / `reviewed_at`. Approve-then-revoke overwrites who approved, so until CT-020 lands, the approver of a later-revoked badge is recoverable only from logs. This is a deliberate, named gap: the issue's audit checkbox is **partial**, and the PR body must say so.

---

## Global Constraints

- **Java 21**, Spring Boot 4.1.0, Spring Modulith, Spring Data JPA, JUnit 5, AssertJ, MockMvc. No new runtime dependencies.
- **Commits carry no Claude attribution.** No `Co-Authored-By`, no "Generated with". The user's `~/.claude/CLAUDE.md` overrides the harness reminder that asks for it.
- **`export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`** before running the suite. Without it the Testcontainers-backed Postgres tests silently do not run and the suite looks green.
- **Never return a JPA entity from a controller.** Phase exclusion boundary (`Q5`). Every response is a `record` in the public `modules.identity` package.
- **Never expose `evidence_reference` or `review_notes` publicly, and never log either.** `PublicUserResponse` does not change.
- **A badge grants nothing.** No new authority, no change to `SecurityUser.getAuthorities()`, no change to `UserRoles`.
- **`open-in-view: false`** (`application-test.yaml`). `VerificationRequest.user` and `.reviewer` are `LAZY` — every DTO mapping happens inside `@Transactional(readOnly = true)` or behind an `@EntityGraph`, or it throws `LazyInitializationException`.
- **Any migration must parse on H2.** `SchemaValidationTest` and `IdentityMigrationTest` run Flyway against H2 in PostgreSQL mode (`application-test.yaml`). H2 has no partial indexes — `CREATE UNIQUE INDEX … WHERE status = 'PENDING'` is not available. **This plan adds no migration** (see below).
- **`shared` must never import from `modules.identity`.** `CrowdtraceModulesTest` must pass after every task.
- The suite must be green at every commit.

### No migration is needed

`verification_requests.verification_type` is `VARCHAR(32)` with **no CHECK constraint** (`V1__create_identity_tables.sql`), mapped `@Enumerated(EnumType.STRING)`. Renaming the enum constants is a pure Java change. `SUBJECT_MATTER_EXPERT` is 22 characters and fits.

**This assumes no rows exist in any deployed database** — a stored `IDENTITY` value would throw on load. No endpoint has ever written one, so this should hold. **Verify before merge:**

```sql
SELECT verification_type, count(*) FROM verification_requests GROUP BY 1;
```

Zero rows → proceed. Non-zero → stop and add a `V9` backfill mapping the legacy values.

---

## Review Focus

Conditions the acceptance criteria imply but which no happy path exercises. Each is assigned to the task that owns the code.

1. **A moderator must not decide their own request.** "Administrators retain accountability" (Q1) is meaningless if a moderator can badge themselves. Not stated in the issue; enforce it anyway. → Task 5.
2. **Two moderators deciding the same request concurrently must not both succeed.** A read-then-write service check has a race window that flips a `REJECTED` request to `APPROVED`. The conditional `UPDATE … WHERE status = :from` closes it. → Task 4.
3. **Requesting another user's request by id must return 404, not 403.** 403 confirms the id exists and leaks the queue, contradicting the posture `AccountEnumerationTest` establishes. Resolved structurally: the user-facing read takes **no id at all**. → Task 3.
4. **A rejected application for a second type must not strip an existing badge.** `resolveBadgeType` handles this per-type today; a regression test must pin it, because the obvious "newest decision wins" simplification breaks it. → Task 7.
5. **Revoke must bump `reviewed_at` — for the audit record.** Every decision must be able to answer "when". *(Note: this is not a badge-visibility bug. Revoke mutates the same row and the duplicate rules allow at most one `APPROVED` row per type, so the badge disappears regardless of ordering. Do not try to reproduce a visibility failure here — it cannot happen.)* → Task 5.
6. **An APPROVED badge holder with role `REGISTERED_USER` must still get 403** on the admin queue, on every decision endpoint, and on `/api/v1/auth/users`. This is the "badge is not a permission" invariant, proven rather than asserted. → Task 7.
7. **Evidence must never reach the logs.** Output-capture test in the style of `StructuredLoggingTest`. The phase verification requires it (`docs/tasks/phase_1_identity.md`: "No private identity or verification evidence in public DTOs or logs"). → Task 7.
8. **Overlong evidence must return 400, not 500.** `evidence_reference` is `VARCHAR(1024)` and `review_notes` `VARCHAR(2000)`; without bean validation these fail as a database error. → Task 2.
9. **A malformed enum value or a non-numeric path id must return 400, not 500.** Verified during planning: `GlobalExceptionHandler` is a plain `@RestControllerAdvice` with a catch-all `@ExceptionHandler(Exception.class)` and **no** `HttpMessageNotReadableException` or `MethodArgumentTypeMismatchException` handler — so the catch-all wins before Spring's default resolver runs. The D1 rename makes stale clients sending `"IDENTITY"` a realistic case. → Task 2.
10. **The conditional update detaches the entity it just changed.** `@Modifying(clearAutomatically = true)` clears the persistence context, so the previously loaded instance is stale: mapping it returns the *old* status and touching `user.displayName` throws. The service must re-read after the update. → Task 5.

---

## Contract

All paths are under `/api/v1/…`, matching the shipped convention CT-009 established (the `/api/admin/…` placeholders in `OpenApiConfig.contractPaths()` are unshipped phase-3 stubs). `SecurityConfig.anyRequest().authenticated()` already covers them; roles come from the method annotations. **Seven paths in total.**

### User-facing — tag `Identity`

| Method | Path | Guard | Behaviour |
|---|---|---|---|
| `POST` | `/api/v1/verification-requests` | `@RequiresRegisteredUser` | Submit. Body: `verificationType`, `evidenceReference`. → 201 `VerificationRequestResponse` |
| `GET` | `/api/v1/verification-requests/me` | `@RequiresRegisteredUser` | Own requests, newest first. Keyed on `authentication.getName()`, **no id parameter**. → 200 `List<VerificationRequestResponse>` |

### Admin-facing — tag `Administration`

| Method | Path | Guard | Behaviour |
|---|---|---|---|
| `GET` | `/api/v1/admin/verification-requests` | `@RequiresModerator` | Pending queue, oldest first, paginated. → 200 `PagedResponse<AdminVerificationRequestResponse>` |
| `POST` | `/api/v1/admin/verification-requests/{id}/approve` | `@RequiresModerator` | `PENDING → APPROVED` |
| `POST` | `/api/v1/admin/verification-requests/{id}/reject` | `@RequiresModerator` | `PENDING → REJECTED` |
| `POST` | `/api/v1/admin/verification-requests/{id}/revoke` | `@RequiresSuperAdmin` | `APPROVED → REVOKED` |
| `POST` | `/api/v1/admin/verification-grants` | `@RequiresSuperAdmin` | Grant (D2, A1). Body carries the target `email`. Creates an `APPROVED` row directly. |

The three decision endpoints take an optional `reviewNotes` body. All four admin actions return `AdminVerificationRequestResponse`.

### Response projections

| Record | Fields | Deliberately absent |
|---|---|---|
| `VerificationRequestResponse` (own view) | `id`, `verificationType`, `evidenceReference`, `status`, `createdAt`, `reviewedAt` | `reviewNotes`, reviewer identity — moderators stay anonymous to applicants, and notes are reserved for future AI-assisted review (`docs/crowdtrace-spec.md:66`) |
| `AdminVerificationRequestResponse` | `id`, `userId`, `displayName`, `verificationType`, `evidenceReference`, `status`, `reviewNotes`, `createdAt`, `reviewedAt` | `email`, password, credentials |
| `PublicUserResponse` | **unchanged** | — |

### State machine

```
PENDING  ──approve──>  APPROVED  ──revoke──>  REVOKED
   │
   └────reject────>  REJECTED

grant: (no row) ──> APPROVED
```

Every other transition → **409** `ConflictException`. Re-applying after `REJECTED` or `REVOKED` is allowed and creates a new row.

### Failure map

| Condition | Status | Exception |
|---|---|---|
| Unknown request id / unknown email | 404 | `NotFoundException` |
| Invalid transition (e.g. approve an already-`APPROVED` request) | 409 | `ConflictException` |
| Lost concurrent decision race (0 rows updated) | 409 | `ConflictException` |
| Duplicate `PENDING` for the same type | 409 | `ConflictException` |
| Already holds an `APPROVED` badge of that type | 409 | `ConflictException` |
| Deciding your own request | 403 | `AccessDeniedException` |
| Granting to a non-`ACTIVE` or soft-deleted user | 409 | `ConflictException` |
| Blank / overlong / missing field | 400 | `MethodArgumentNotValidException` |
| Unparseable enum or non-numeric path id | 400 | `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` — **new handler, Task 2** |
| Wrong role | 403 | handled by `@PreAuthorize` |

Apart from the two in Task 2, no new exception types and no new handlers.

### Not in scope

No rate limiting on submission. The one-`PENDING`-per-type rule caps a user at three open requests total, which is a tighter bound than any bucket would give, and adding a policy would touch `application.yaml`, all three profile files and `RateLimitPropertiesTest` for no gain.

---

## Interfaces

Pin these exactly. Under subagent-driven execution, Task 6's agent never reads Task 5's code.

```java
package com.souldealers.crowdtracebackend.modules.identity;

public interface VerificationService {

    // actorEmail is always authentication.getName()
    VerificationRequestResponse submit(String actorEmail, SubmitVerificationRequest request);

    List<VerificationRequestResponse> getOwnRequests(String actorEmail);

    PagedResponse<AdminVerificationRequestResponse> getPendingQueue(Pageable pageable);

    AdminVerificationRequestResponse approve(String actorEmail, Long requestId,
                                            VerificationDecisionRequest decision);

    AdminVerificationRequestResponse reject(String actorEmail, Long requestId,
                                            VerificationDecisionRequest decision);

    AdminVerificationRequestResponse revoke(String actorEmail, Long requestId,
                                            VerificationDecisionRequest decision);

    AdminVerificationRequestResponse grant(String actorEmail, GrantVerificationRequest request);
}
```

```java
public record SubmitVerificationRequest(
        @NotNull VerificationType verificationType,
        @NotBlank @Size(max = 1024) String evidenceReference) {}

public record VerificationDecisionRequest(
        @Size(max = 2000) String reviewNotes) {}

public record GrantVerificationRequest(
        @NotBlank @Email String email,
        @NotNull VerificationType verificationType,
        @NotBlank @Size(max = 1024) String evidenceReference,
        @Size(max = 2000) String reviewNotes) {}

public record VerificationRequestResponse(
        Long id, VerificationType verificationType, String evidenceReference,
        VerificationStatus status, LocalDateTime createdAt, LocalDateTime reviewedAt) {}

public record AdminVerificationRequestResponse(
        Long id, Long userId, String displayName, VerificationType verificationType,
        String evidenceReference, VerificationStatus status, String reviewNotes,
        LocalDateTime createdAt, LocalDateTime reviewedAt) {}
```

**OpenAPI schema names** registered in `OpenApiConfig.reusableSchemas()` and referenced by `$ref` from the controllers — use these exact names:

| Name | Shape |
|---|---|
| `VerificationRequestResponse` | the own-view object |
| `AdminVerificationRequestResponse` | the admin object |
| `VerificationRequestListResponse` | `ApiResponse` envelope whose `data` is an array of `VerificationRequestResponse` |
| `AdminVerificationPageResponse` | `PagedResponse` whose `content` is an array of `AdminVerificationRequestResponse` |
| `AdminVerificationListResponse` | `ApiResponse` envelope whose `data` is `AdminVerificationPageResponse` |
| `AdminVerificationDecisionResponse` | `ApiResponse` envelope whose `data` is `AdminVerificationRequestResponse` |

---

## File Structure

**Created — `modules/identity/` (public API surface):**

| File | Responsibility |
|---|---|
| `VerificationService.java` | The interface above |
| `SubmitVerificationRequest.java` / `VerificationDecisionRequest.java` / `GrantVerificationRequest.java` | Request records |
| `VerificationRequestResponse.java` / `AdminVerificationRequestResponse.java` | Response records |
| `VerificationRequestController.java` | `/api/v1/verification-requests`, tag `Identity` |
| `AdminVerificationController.java` | `/api/v1/admin/…`, tag `Administration` |

**Created — `modules/identity/internal/service/VerificationServiceImpl.java`** — transitions, ownership, duplicate and self-review rules, decision logging.

**Modified:**

| File | Change |
|---|---|
| `modules/identity/VerificationType.java` | `POLICE`, `NGO`, `SUBJECT_MATTER_EXPERT` |
| `modules/identity/internal/repository/VerificationRequestRepository.java` | `@EntityGraph` on the queue query; `existsBy…`; list-form own-requests; conditional transition update |
| `shared/exception/GlobalExceptionHandler.java` | Two 400 handlers (Review Focus #9) |
| `shared/CustomMessages.java` | Message constants for the new failures |
| `shared/config/OpenApiConfig.java` | The four new reusable schemas |

**Test files that must be updated (enum rename):** `VerificationRequestRepositoryTest`, `PublicUserProjectionTest`, `UserResponseTest`. The two-type cases in `PublicUserProjectionTest` need two distinct new values — use `POLICE` and `NGO`.

**Contract tests that must gain the new paths:** `CanonicalPathTest`, `OpenApiContractTest`, `AuthorizationMatrixTest`.

---

## Task 0: Baseline

- [x] `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` **and** `export TESTCONTAINERS_RYUK_DISABLED=true`. Both are required: without the second, five Testcontainers classes error with `Container startup failed for image testcontainers/ryuk:0.14.0`.
- [x] `./mvnw test` — **baseline: 207 tests, 0 failures, BUILD SUCCESS** (2026-09-29). Every later task must leave it green.
  - Do **not** trust the shell exit code when piping Maven through `tail`/`grep` — it reports 0 on a failed build. Check for `[ERROR] Tests run:` in the output.
- [x] This plan lives at `docs/superpowers/plans/2026-09-29-ct010-verified-badge-workflow.md`, alongside the sibling `2026-09-25-otp-rate-limiting.md`. Commit it before Task 1.
- [ ] Confirm `verification_requests` is empty in dev/prod (query above). If not, stop and add the `V9` backfill.

---

## Task 1: Rename the badge types

The enum is the vocabulary every later task speaks. Nothing else can be written until it settles. No DDL.

**Files:** `modules/identity/VerificationType.java`; tests `VerificationRequestRepositoryTest`, `PublicUserProjectionTest`, `UserResponseTest`.

- [ ] **Failing test first:** update the three test files to `POLICE` / `NGO` / `SUBJECT_MATTER_EXPERT`. Keep `PublicUserProjectionTest`'s two-type scenarios genuinely two-type. The suite now fails to compile.
- [ ] Replace `IDENTITY` / `ORGANIZATION` in the enum. Add a class comment recording that these values are **public** (rendered on `PublicUserResponse.badgeType`) and that the badge is a trust signal, never a permission.
- [ ] `./mvnw test` — green, same count as Task 0. **Commit.**

---

## Task 2: Records, validation, and malformed-input handling

Pure data plus the two missing 400 handlers. Written before the service so the service compiles against fixed shapes.

**Files:** the five records from *Interfaces*; `shared/exception/GlobalExceptionHandler.java`; `shared/CustomMessages.java`.
**Tests:** `modules/identity/VerificationDtoValidationTest.java`; extend `shared/GlobalExceptionHandlerTest.java`.

- [ ] **Failing tests first:** 1025-character evidence fails validation (Review Focus #8); a body of `{"verificationType":"IDENTITY", …}` returns **400** and a `GET`/`POST` on `/api/v1/admin/verification-requests/abc/approve` returns **400** (Review Focus #9).
- [ ] Add the five records exactly as in *Interfaces*. Javadoc on `VerificationRequestResponse` stating why reviewer identity and notes are absent.
- [ ] Add `@ExceptionHandler(HttpMessageNotReadableException.class)` and `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` to `GlobalExceptionHandler`, both returning 400 through the existing private `problem(...)` helper. **Do not change any existing handler.** The detail message must not echo the raw body.
- [ ] `./mvnw test` — green. **Commit.**

---

## Task 3: Repository queries

**Files:** `internal/repository/VerificationRequestRepository.java`. **Test:** extend `VerificationRequestRepositoryTest`.

- [ ] **Failing test first.** `VerificationRequestRepositoryTest` is `@DataJpaTest`, so every test already runs inside a transaction and lazy loading would succeed vacuously. Annotate the entity-graph test `@Transactional(propagation = Propagation.NOT_SUPPORTED)` so it genuinely runs detached — otherwise it proves nothing.
- [ ] Add `@EntityGraph(attributePaths = "user")` to `findByStatusOrderByCreatedAtAsc` so the admin queue maps `displayName` without N+1 or a lazy-init failure under `open-in-view: false`.
- [ ] `boolean existsByUserIdAndVerificationTypeAndStatus(Long userId, VerificationType type, VerificationStatus status)` — backs both duplicate rules.
- [ ] `List<VerificationRequest> findByUserIdOrderByCreatedAtDesc(Long userId)` (list form, for the own-requests view).
- [ ] **Review Focus #3:** add no `findById(id)` path for user-facing reads. The own-view is keyed on the principal only.
- [ ] `./mvnw test` — green. **Commit.**

---

## Task 4: The conditional transition

The concurrency control, isolated so its test is unambiguous.

**Files:** `VerificationRequestRepository.java`. **Test:** `VerificationRequestRepositoryTest`.

- [ ] **Failing test first (Review Focus #2):** two `applyDecision` calls with the same `fromStatus` return `1` then `0`.
- [ ] Add:
  ```java
  @Modifying(clearAutomatically = true)
  @Query("""
          UPDATE VerificationRequest request
             SET request.status = :toStatus,
                 request.reviewer = :reviewer,
                 request.reviewNotes = :reviewNotes,
                 request.reviewedAt = :reviewedAt
           WHERE request.id = :id
             AND request.status = :fromStatus
          """)
  int applyDecision(@Param("id") Long id,
                    @Param("fromStatus") VerificationStatus fromStatus,
                    @Param("toStatus") VerificationStatus toStatus,
                    @Param("reviewer") User reviewer,
                    @Param("reviewNotes") String reviewNotes,
                    @Param("reviewedAt") LocalDateTime reviewedAt);
  ```
  Returns the affected row count. Runs on both H2 and PostgreSQL — no `@Version` column, no migration.
- [ ] `./mvnw test` — green. **Commit.**

---

## Task 5: `VerificationServiceImpl`

The rules live here (`Q3` — ownership belongs to the identity service layer, not scattered across controllers).

**Files:** `VerificationService.java`, `internal/service/VerificationServiceImpl.java`, `shared/CustomMessages.java`. **Test:** `internal/service/VerificationServiceImplTest.java`.

- [ ] **Failing tests first:** one per row of the failure map, with a mocked repository.
- [ ] `@Transactional` on every mutating method; `@Transactional(readOnly = true)` on both reads, so lazy mapping is safe.
- [ ] **submit:** resolve the user from the principal email; reject a duplicate `PENDING` or an existing `APPROVED` of that type with 409; persist `status = PENDING`.
- [ ] **approve / reject:** load the request (404), enforce `PENDING`, then `applyDecision`; 0 rows → 409.
- [ ] **revoke:** identical, `APPROVED → REVOKED`. Passes a non-null `reviewedAt` (Review Focus #5).
- [ ] **Review Focus #10:** after a successful `applyDecision`, **re-read the request through the entity-graph query** and map the response from that. The pre-update instance is detached and stale — mapping it returns the old status and touching `user.displayName` throws.
- [ ] **grant:** resolve the target by email (404); require `ACTIVE` and `deletedAt == null` (409); reject `actor.id.equals(target.id)` (403); reject an existing `APPROVED` or `PENDING` of that type (409 — the admin should approve the pending request instead); persist `status = APPROVED`, `reviewer = actor`, `reviewedAt = now`, evidence from the request body.
- [ ] **Review Focus #1:** approve, reject and revoke all throw `AccessDeniedException` when the actor is the request's own user.
- [ ] Log one line per decision at INFO — **request id, action, from→to, actor id only**. No email, no evidence, no notes (Review Focus #7).
- [ ] `./mvnw test` — green. **Commit.**

---

## Task 6: Controllers and OpenAPI

**Files:** both controllers, `shared/config/OpenApiConfig.java`. **Tests:** `CanonicalPathTest`, `OpenApiContractTest`.

- [ ] **Failing tests first:** extend `CanonicalPathTest`'s expected-pattern set with all **seven** new paths; extend `OpenApiContractTest` to assert each path exists, carries the right tag (`Identity` / `Administration`) and requires `bearerAuth`.
- [ ] Controllers are thin: validate, delegate, wrap in `ApiResponse.success(...)`. Submit returns 201 via `@ResponseStatus(HttpStatus.CREATED)`.
- [ ] Follow the `AuthController.getUsers` annotation pattern exactly — `@Operation(summary, description, tags)`, `@io.swagger…ApiResponse(content = @Content(schema = @Schema(ref = "#/components/schemas/…")))`, the role annotation from D3, and `@SecurityRequirement(name = "bearerAuth")`.
- [ ] The grant endpoint's `@Operation` description must state that **the badge holder can read the justification** (A3), and that internal reasoning belongs in `reviewNotes`.
- [ ] Add the four schemas named in *Interfaces* to `OpenApiConfig.reusableSchemas()`, mirroring `userPageResponseSchema` / `userListResponseSchema`. **Do not touch `contractPaths()`** — that method holds unshipped phase-3 placeholders only; real endpoints are discovered from the controllers.
- [ ] `./mvnw test` — green. **Commit.**

---

## Task 7: End-to-end behaviour and the invariants

The tests the acceptance criteria actually name. MockMvc against the full context, following `AuthorizationMatrixTest`'s JWT-minting helper.

**Test files:** `modules/identity/VerificationWorkflowTest.java`, `modules/identity/VerificationAuthorizationTest.java`, and edits to `AuthorizationMatrixTest`.

- [ ] **Ownership:** user A's `/me` never contains user B's request. A registered user gets 403 on the queue and on every admin endpoint.
- [ ] **Role (D3):** moderator approves/rejects → 200; moderator revokes → **403**; moderator grants → **403**; Super Admin does all four → 200. Unauthenticated → 401 on all **seven** paths.
- [ ] **State transitions:** the full matrix from the state machine. Every disallowed transition → 409.
- [ ] **Projection:** the applicant's own response has no `reviewNotes` and no reviewer field; the admin response has no `email`. Assert on absent JSON paths, not just on the record shape.
- [ ] **Audit (partial, D4):** after each decision the row carries the deciding actor in `reviewer_id`, a non-null bumped `reviewed_at`, and the submitted notes.
- [ ] **Review Focus #4:** approve `POLICE`, then reject `NGO` → `getPublicProfile` still returns `verified = true`, `badgeType = POLICE`.
- [ ] Rejected and revoked badges are absent from `getPublicProfile` (`verified = false`, `badgeType = null`).
- [ ] **Review Focus #6:** a `REGISTERED_USER` holding an `APPROVED` badge still gets 403 on the queue, on all four admin actions, and on `/api/v1/auth/users`.
- [ ] **Review Focus #7:** `OutputCaptureExtension` test running a full submit→approve→revoke cycle. Assert the captured output contains neither the evidence string nor the user's email.
- [ ] `CrowdtraceModulesTest` still passes — nothing in `shared` imports `modules.identity`.
- [ ] `./mvnw test` — green, count > Task 0 baseline. **Commit.**

---

## Verification

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
./mvnw test          # green; count > Task 0 baseline
```

Then manually, against `./mvnw spring-boot:run` with the dev profile — add the calls to `api-requests/` alongside the existing `auth/` files:

1. Sign up, verify OTP, log in as a registered user.
2. `POST /api/v1/verification-requests` with `SUBJECT_MATTER_EXPERT` and a recognisable evidence string → 201.
3. `GET /api/v1/verification-requests/me` → the request, with no `reviewNotes`.
4. Repeat step 2 → 409. Send `"verificationType": "IDENTITY"` → 400, not 500.
5. As a moderator, `GET /api/v1/admin/verification-requests` → the request with `displayName`, no `email`.
6. As that same registered user, hit the queue → 403 (badge unlocks nothing).
7. Approve as moderator → 200. Confirm `getPublicProfile` shows the badge.
8. Revoke as moderator → 403. Revoke as Super Admin → 200. Confirm the badge is gone.
9. Grant to a second user by email as Super Admin → 200; as moderator → 403.
10. `grep -i` the console output **from step 2 onward** for the evidence string from step 2 and the applicant's email → no hits. (Scoping matters: signup and login legitimately log other lines.)

**PR body must state** that the audit criterion is partially satisfied: decisions are recorded on the request row and in structured logs, and the append-only trail arrives with CT-020.
