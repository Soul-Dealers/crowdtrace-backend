# CT-034 Admin User Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Super Admins list users (keyset-paged), promote/demote moderators and deactivate accounts, all audited.

**Architecture:** New `AdminUserController` + `AdminUserService` in the identity module. Mutations lock the target
row, check eligibility after the lock, and record audit in the same transaction. Listing seeks on `(created_at, id)`
with a JPA `Specification`, the same pattern as `CaseQueryServiceImpl#publicCases`. Web gets a Super-Admin-only
`/admin/users` page and refreshes the session role from `/me` on load.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, Flyway, Next.js 16, react-query, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-20-ct034-admin-user-management-design.md`

## Global Constraints

- Keyset pagination only (`shared/SeekCursor`, `shared/CursorResponse`); size default 20, max 50. No offset paging.
- Role changes only `REGISTERED_USER` ↔ `MODERATOR`. Never self; never a `SUPER_ADMIN` target.
- Audit: `AuditAction.USER_ROLE_CHANGED` (fromRole, toRole as `AuditActorRole`), `AuditAction.USER_DEACTIVATED`
  (empty). Append-only; audit failure rolls the change back.
- No reactivation, no free-text reason, no site settings.
- Commits: no attribution trailers. VERIFY_GATE is off: no `Verified:` trailer needed.
- Don't edit existing checks; the offset `/api/v1/auth/users` endpoint stays as is.

## Review Focus

1. `q` containing `%` or `_` must match literally (escape LIKE wildcards) — test in Task 2.
2. A stale profile save racing a demotion must not restore the old role — test in Task 1.
3. A user promoted/demoted while holding a token gets the new permissions on the next request — test in Task 3.
4. Two Super Admins acting on one account at once: one 200, one 409, one audit row — test in Task 3.
5. Paging with many users sharing one `created_at` must neither skip nor repeat rows — test in Task 2.

---

### Task 1: Profile save can't overwrite role or status

**Files:**
- Modify: `src/main/java/.../modules/identity/internal/repository/UserRepository.java`
- Modify: `src/main/java/.../modules/identity/internal/service/UserServiceImpl.java` (`updateProfile`)
- Test: `src/test/java/.../modules/identity/ProfileUpdateRaceTest.java`

**Interfaces:**
- Produces: `@Modifying @Query int updateDisplayName(String email, String displayName, LocalDateTime updatedAt)` on
  `UserRepository`; `@Lock(PESSIMISTIC_WRITE) Optional<User> findByIdForUpdate(Long id)` (used by Task 3).

- [ ] **Step 1: Write the failing test** `staleProfileSaveKeepsTheCurrentRole`: save a `REGISTERED_USER`, take a
  detached copy, set the DB role to `MODERATOR` via JDBC, make `findByEmail` return the stale copy
  (`@MockitoSpyBean UserRepository`), call `userService.updateProfile(email, new UpdateUserProfileRequest("New"))`.
  Assert DB `role = 'MODERATOR'`, `display_name = 'New'`; response `displayName` is `New`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=ProfileUpdateRaceTest` — Expected: FAIL (role is REGISTERED_USER).
- [ ] **Step 3: Implement**: `updateProfile` is `@Transactional`, calls `updateDisplayName` (404 if 0 rows), then
  reloads the user for the response. Add `findByIdForUpdate`.
- [ ] **Step 4: Run** the test and `CurrentUserProfileTest` — Expected: PASS.
- [ ] **Step 5: Commit** `fix(identity): save only the display name on profile update [CT-034]`

### Task 2: Keyset user listing

**Files:**
- Create: `src/main/resources/db/migration/V30__index_users_created_at_id.sql` — `CREATE INDEX idx_users_created_at_id ON users (created_at DESC, id DESC);`
- Create: `modules/identity/AdminUserService.java`, `AdminUserResponse.java`, `AdminUserController.java`,
  `internal/service/AdminUserServiceImpl.java`, `internal/repository/AdminUserSpecification.java`
- Modify: `UserRepository` (extend `JpaSpecificationExecutor<User>`), `shared/config/OpenApiConfig.java`
- Test: `src/test/java/.../modules/identity/AdminUserListTest.java`

**Interfaces:**
- Produces: `record AdminUserResponse(Long id, String displayName, String email, UserRoles role, UserStatus status, LocalDateTime createdAt)`;
  `CursorResponse<AdminUserResponse> AdminUserService.list(UserRoles role, UserStatus status, String q, String cursor, int size)`;
  `GET /api/v1/admin/users` (`@RequiresSuperAdmin`, params `role`, `status`, `q`, `cursor`, `size`), wrapped in `ApiResponse`.

- [ ] **Step 1: Write failing tests** (MockMvc + real Postgres, style of `VerificationAuthorizationTest`):
  - `listsNewestFirstAcrossPagesWithoutGapsOrRepeats`: 5 users sharing one `created_at`, plus 2 older; walk with
    `size=2` following `nextCursor`; ids collected equal the full set in `(created_at desc, id desc)` order; last
    page `hasNext=false`, `nextCursor=null`.
  - `filtersByRoleAndStatus`; `searchesEmailOrNamePrefixIgnoringCase` (`q=ALI` matches `alice@…` and display name
    `Alison`, not `Malia`); `treatsLikeWildcardsLiterally` (`q=a_` does not match `ab@…`).
  - `capsSizeAt50` (size=500 → 50 rows); `rejectsATamperedCursor` (400).
  - `onlySuperAdminsCanList`: MODERATOR and REGISTERED_USER → 403, no token → 401.
- [ ] **Step 2: Run** `./mvnw test -Dtest=AdminUserListTest` — Expected: FAIL (404 on the path).
- [ ] **Step 3: Implement** following `PublicCaseSpecification`/`CaseQueryServiceImpl#publicCases`:
  `repo.findBy(spec, f -> f.sortBy(ORDER).limit(size + 1).all())`; seek predicate
  `created_at < c.createdAt OR (created_at = c.createdAt AND id < c.id)`; `q` trimmed, lowercased, wildcards escaped,
  `lower(email) LIKE q% OR lower(display_name) LIKE q%`. Size clamped to 1..50. Register the response schemas in
  `OpenApiConfig` like `CommentCountResponse`.
- [ ] **Step 4: Run** the test, then `./mvnw test -Dtest='OpenApiContractTest,AuthorizationMatrixTest'` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): keyset-paged admin user listing [CT-034]`

### Task 3: Role change and deactivation

**Files:**
- Modify: `AdminUserService`, `AdminUserServiceImpl`, `AdminUserController`, `OpenApiConfig`
- Create: `modules/identity/ChangeRoleRequest.java` — `record ChangeRoleRequest(@NotNull UserRoles role)`
- Test: `AdminUserRoleTest.java`, `AdminUserDeactivationTest.java`, `AdminUserAuditFailureTest.java`, `AdminUserConcurrencyTest.java`

**Interfaces:**
- Consumes: `UserRepository.findByIdForUpdate` (Task 1), `AdminUserResponse` (Task 2), `AuditRoleMapper`.
- Produces: `AdminUserResponse changeRole(String actorEmail, long userId, UserRoles role)` and
  `AdminUserResponse deactivate(String actorEmail, long userId)`, both `@RequiresSuperAdmin @Transactional @AuditedOperation`;
  `POST /api/v1/admin/users/{id}/role` and `POST /api/v1/admin/users/{id}/deactivate`.

Order of checks after locking the target: missing → 404; target is actor or `SUPER_ADMIN` → 403 (`AccessDeniedException`);
new role `SUPER_ADMIN` → 400 (checked before locking); not `ACTIVE` → 409; same role / already `DEACTIVATED` → 409 (`ConflictException`).
Deactivate sets `DEACTIVATED` and increments `credentialsVersion`.

- [ ] **Step 1: Write failing tests**
  - Role: `promotesAndDemotes` (response + DB + audit row `USER.ROLE_CHANGED` with `fromRole`/`toRole`, actor = super admin);
    `promotedUserCanUseModeratorEndpointsWithTheTokenTheyHave` (token issued before → `GET /api/v1/admin/verification-requests` 200);
    `demotedModeratorLosesAccessWithTheirExistingToken` (→ 403); `cannotChangeOwnRole` 403; `cannotTouchASuperAdmin` 403;
    `cannotGrantSuperAdmin` 400; `sameRoleIs409`; `inactiveTargetIs409`; `unknownUserIs404`; `moderatorCannotChangeRoles` 403
    (including on their own id).
  - Deactivation: `deactivatedUserIsLockedOutImmediately` (existing token → 401; login → 401); audit `USER.DEACTIVATED`;
    `cannotDeactivateSelf` 403; `cannotDeactivateSuperAdmin` 403; `alreadyDeactivatedIs409`; moderator caller 403.
  - Audit failure (`@MockitoBean AuditRecorder` throwing `AuditRecordingException`): role and status unchanged for both operations.
  - Concurrency: two super admins promote the same user in parallel (latch) → one 200, one 409, exactly one `USER.ROLE_CHANGED` row.
- [ ] **Step 2: Run** `./mvnw test -Dtest='AdminUser*Test'` — Expected: the new tests FAIL (404 paths).
- [ ] **Step 3: Implement** per the Interfaces block and check order above.
- [ ] **Step 4: Run** `./mvnw test -Dtest='AdminUser*Test'` then the full `./mvnw test` — Expected: PASS, 0 failures.
- [ ] **Step 5: Commit** `feat(identity): Super Admin role changes and deactivation, audited [CT-034]`

### Task 4: Web Users page

**Files:**
- Modify: `apps/web/src/lib/api/schema.ts` (regenerate: backend on :8080, `pnpm --filter web api:types`)
- Modify: `apps/web/src/lib/auth.ts` — `isSuperAdmin(session)`; `apps/web/src/components/app/require-auth.tsx` — `superAdmin` prop
  (gate text "This page is for Super Admins only.")
- Modify: `apps/web/src/components/providers.tsx` (or a small `SessionSync` component it renders): when a session exists,
  `GET /api/v1/auth/me` once on load and `saveSession({...session, role, displayName})` if they differ.
- Modify: `apps/web/src/components/ct/side-nav.tsx` — "Users" item (`UserCog` icon, `/admin/users`) in Moderation, Super Admin only.
- Create: `apps/web/src/app/admin/users/page.tsx`, `apps/web/src/lib/admin-users.ts` (`useInfiniteQuery` on the cursor + mutations)
- Test: `apps/web/e2e/admin-users.spec.ts`

UI: role and status selects, search input (debounced), table rows (name, email, role, status, joined), "Load more"
when `hasNext`. Row actions: **Make moderator** / **Remove moderator** / **Deactivate**, hidden on own row and Super Admin
rows and for non-`ACTIVE` users. Each action asks inline ("Deactivate Hettie Fritsch? They lose access immediately."
→ **Confirm** / **Cancel**). On 409 show "Already changed by another admin" and refetch.

- [ ] **Step 1: Write failing E2E** (use two ACTIVE `REGISTERED_USER` seed accounts that no other spec uses, e.g. ids 1000002 and another):
  - `a super admin promotes a user, who then sees Moderation without logging in again`: user A logged in first in
    its own context; admin promotes A on `/admin/users`; A reloads → "Review Queue" link visible.
  - `a deactivated user is signed out on their next request`: user B logged in; admin deactivates B; B navigates to
    `/my/cases` → sign-in gate.
  - `moderators can't open the Users page`: moderator has no "Users" link; `/admin/users` shows "This page is for Super Admins only."
- [ ] **Step 2: Run** `pnpm --filter web test:e2e admin-users` (sandbox off) — Expected: FAIL.
- [ ] **Step 3: Implement** the files above.
- [ ] **Step 4: Run** `pnpm typecheck && pnpm lint && pnpm --filter web test && pnpm --filter web test:e2e && pnpm --filter web build` — Expected: all pass.
- [ ] **Step 5: Commit** `feat(web): Super Admin users page [CT-034]`
