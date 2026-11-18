# CT-019 Comment Reporting and Moderation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Registered users report comments. Moderators remove a comment (it then shows a placeholder) or keep it, every decision is audited, and reporters are notified in-app when a comment they reported is removed.

**Architecture:** Governance owns `content_reports`, the report and moderation endpoints, the decisions and the audit events. It drives comment changes through a new public community port, `CommentModerationService`, whose mutations require an existing transaction (the same shape as `ReviewServiceImpl` → `CaseReviewMutationService`). After commit, a governance listener calls `InAppNotifications` (the same shape as `FollowerNotifier`).

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Modulith, JPA, Flyway, PostgreSQL (embedded in tests), Next.js 16 with the generated API client, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-18-ct019-comment-reporting-design.md`

## Global Constraints

- Report reasons: `SPAM | HARASSMENT | MISINFORMATION | PRIVACY | OTHER`. Details are trimmed, max 1000 characters, and required for `OTHER`.
- Remove notes: required, trimmed, 1–2000 characters. Keep notes: optional, trimmed, max 2000.
- Rate limit: policy `user-comment-report`, `{ limit: 10, window: 1h }`, USER scope. A 404 or 403 refunds the slot. If the store fails, the request fails with 503.
- Queue page size: default 20, max 50 (`PagedResponse`).
- `report_count` means the number of OPEN reports.
- `content_reports` rows are only ever inserted, or updated OPEN → RESOLVED with all resolution fields set together (the V17 trigger). Never re-save a resolved entity.
- No imports of another module's `internal` package. Cross-module calls go only through root-package interfaces.
- Public comment responses never carry the body or author of a removed or author-deleted comment.
- Notification copy and payload never include moderator notes, report details or comment text.
- Commits: conventional messages with a `[CT-019]` suffix and no attribution trailers. The verify loop is paused (VERIFY_GATE=off).

## Review Focus

1. A report races a removal of the same comment. Expected: either the report is included in the removal, or it gets 404 afterwards. Never an OPEN report left on a REMOVED comment. Covered by a test in Task 6.
2. A reply is posted under a comment while it is being removed. Expected: 404 once the removal commits. Covered by a test in Task 1.
3. A user reports again after "Keep comment". Expected: a new OPEN row with 201, and the comment is back in the queue. Covered by a test in Task 6.
4. A comment that was author-deleted and then removed. Expected: the public view shows `removed=true` and `deleted=false`, with no body. Covered by a test in Task 1.
5. A moderator decides a report on a case that was taken down. Expected: the queue still lists it, and remove and keep still work. Covered by tests in Tasks 5 and 6.

---

### Task 1: Removed comments render as placeholders (community)

**Files:**
- Create: `src/main/resources/db/migration/V28__index_comments_with_removed.sql`
- Modify: `modules/community/CommentResponse.java`, `modules/community/internal/CommentServiceImpl.java`, `modules/community/internal/repository/CommentRepository.java`, `modules/community/internal/model/Comment.java`, `modules/community/CommentController.java` (OpenAPI description)
- Test: `src/test/java/.../modules/community/CommentEndpointTest.java`

**Interfaces:**
- Produces: `CommentResponse(Long id, Long parentCommentId, PublicUserResponse author, String body, LocalDateTime createdAt, long replyCount, boolean deleted, boolean removed)`; `Comment.isRemoved()`.

- [ ] **Step 1: Write failing tests** in `CommentEndpointTest`. Seed the removal with `jdbc.update("UPDATE comments SET moderation_status='REMOVED', removed_at=now() AT TIME ZONE 'UTC' WHERE id=?", id)`.
  - `removedRootStaysInListAsPlaceholder`: the list includes the id with `removed=true`, `body` null, `author` null, `deleted=false`.
  - `repliesUnderRemovedCommentStayListable`: GET with `parentCommentId=<removed>` returns 200 and the visible reply.
  - `replyCountIncludesRemovedReplies`: a parent with one visible and one removed reply has `replyCount=2`.
  - `authorDeletedThenRemovedShowsRemoved`: `removed=true`, `deleted=false`.
  - `cannotReplyToOrDeleteRemovedComment`: POST reply → 404; author DELETE → 404.
- [ ] **Step 2:** Run `./mvnw test -Dtest=CommentEndpointTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Migration V28.** Drop `idx_comments_case_roots_visible` and `idx_comments_children_visible`. Create `idx_comments_case_roots (case_id, created_at DESC, id DESC) WHERE parent_comment_id IS NULL` and `idx_comments_children (parent_comment_id, created_at DESC, id DESC)`.
- [ ] **Step 4: Repository and service.** Remove the `moderationStatus = VISIBLE` predicate from `findFirstRoots`, `findRootsAfter`, `findFirstReplies`, `findRepliesAfter` and `countVisibleReplies` (rename it `countReplies`). Change the parent check in `listPublic` from `findVisible` to a new `existsByIdAndCaseId(id, caseId)`. Leave `findVisible` and `findVisibleForUpdate` alone, so create, delete and report still refuse removed comments. In `toResponse`, check removed first: `author` and `body` null, `removed=true`, `deleted=false`. Look up profiles only for comments that are neither removed nor deleted.
- [ ] **Step 5:** Run `./mvnw test -Dtest='CommentEndpointTest,*Comment*'`. Expected: PASS. Run `./mvnw test -Dtest='*QueryPlan*,*Index*'` if any test pins these index names, and update the expectations to the new names.
- [ ] **Step 6: Commit** `feat(community): show removed comments as placeholders and keep their replies [CT-019]`

### Task 2: Community moderation port

**Files:**
- Create: `modules/community/CommentModerationService.java`, `modules/community/ModeratedComment.java`, `modules/community/CommentModerationView.java`, `modules/community/internal/CommentModerationServiceImpl.java`
- Modify: `modules/community/internal/repository/CommentRepository.java`
- Test: `src/test/java/.../modules/community/CommentModerationServiceTest.java` (extends `CasePostgresTestSupport`)

**Interfaces:**
- Produces:
  - `record ModeratedComment(long id, long caseId, long authorId, boolean removed, boolean deleted)`
  - `record CommentModerationView(long id, long caseId, PublicUserResponse author, long authorId, String body, boolean deleted, boolean removed, LocalDateTime createdAt)`. `body` is the stored text (`[deleted]` after author deletion), and `author` is null for deleted comments.
  - `CommentModerationService`:
    - `ModeratedComment lockReportable(long commentId)`: `MANDATORY`. Takes the case's shared lock first (`CaseQueryService.lockPublicCase`), then locks the comment row. Throws `NotFoundException` if the comment is missing, the case is not public, or the comment is removed or author-deleted.
    - `ModeratedComment lockForDecision(long commentId)`: `MANDATORY`. Locks the row in any state. Throws `NotFoundException` only if the comment is missing.
    - `void addOpenReport(long commentId)`: `MANDATORY`. `report_count + 1`.
    - `void remove(long commentId, LocalDateTime at)`: `MANDATORY`. Sets `REMOVED`, `removed_at = at`, `report_count = 0`.
    - `void clearOpenReports(long commentId)`: `MANDATORY`. Sets `report_count = 0`.
    - `@RequiresModerator Map<Long, CommentModerationView> getModerationViews(Collection<Long> commentIds)`: read-only, one query for the comments and one batched profile lookup.

- [ ] **Step 1: Failing tests:**
  - `mutationsRequireTransaction`: calling each mutation outside a transaction → `IllegalTransactionStateException`.
  - `lockReportableRejectsRemovedDeletedAndNonPublic`: each → `NotFoundException`.
  - `lockForDecisionAcceptsRemovedComment`.
  - `addOpenReportThenRemoveZeroesCount`: count 1 → `remove` → `moderation_status='REMOVED'`, `report_count=0`, `removed_at` set.
  - `moderationViewsReturnBodyOfRemovedComment`: the body is unchanged after removal.
- [ ] **Step 2:** Run `./mvnw test -Dtest=CommentModerationServiceTest`. Expected: FAIL (does not compile).
- [ ] **Step 3: Implement.** Add repository methods: a `PESSIMISTIC_WRITE` `findForUpdate(id)` with no status filter; a `@Modifying` `incrementReportCount(id)`; `markRemoved(id, at)`; `zeroReportCount(id)`; `findAllById` reuse.
- [ ] **Step 4:** Run the same test. Expected: PASS. Run `./mvnw test -Dtest=CrowdtraceModulesTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(community): expose a moderation port for locking, counting and removing comments [CT-019]`

### Task 3: Moderator case lookup (casefile)

**Files:**
- Create: `modules/casefile/ModerationCaseRef.java`
- Modify: `modules/casefile/CaseQueryService.java` and its implementation
- Test: `src/test/java/.../modules/casefile/CaseQueryServiceTest.java`

**Interfaces:**
- Produces: `record ModerationCaseRef(long id, String fullName, boolean publiclyVisible)`, plus `@RequiresModerator Map<Long, ModerationCaseRef> getModerationCaseRefs(Collection<Long> caseIds)`. `publiclyVisible` is the same predicate as `isPublicCase`.

- [ ] **Step 1: Failing tests:** `moderationCaseRefsIncludeTakenDownCases` (taken-down → present, `publiclyVisible=false`), `moderationCaseRefsRequireModerator` (registered user → `AccessDeniedException`), and `unknownIdsAreOmitted`.
- [ ] **Step 2:** Run `./mvnw test -Dtest=CaseQueryServiceTest`. Expected: FAIL.
- [ ] **Step 3: Implement** with one query by id.
- [ ] **Step 4:** Run it again. Expected: PASS.
- [ ] **Step 5: Commit** `feat(casefile): look up case names for moderators regardless of visibility [CT-019]`

### Task 4: Report a comment (governance)

**Files:**
- Create: `modules/governance/ReportCommentRequest.java`, `modules/governance/CommentReportResponse.java`, `modules/governance/ReportReason.java`, `modules/governance/CommentReportService.java`, `modules/governance/CommentReportController.java`, `modules/governance/internal/CommentReportServiceImpl.java`, `modules/governance/internal/CommentReportRateLimitGuard.java`, `modules/governance/internal/model/ContentReport.java`, `modules/governance/internal/model/ReportStatus.java`, `modules/governance/internal/model/ReportResolution.java`, `modules/governance/internal/repository/ContentReportRepository.java`
- Modify: `src/main/resources/application.yaml` (add the policy `user-comment-report: { limit: 10, window: 1h }`), `apps/web` OpenAPI types (regenerated in Task 8)
- Test: `src/test/java/.../modules/governance/CommentReportEndpointTest.java`

**Interfaces:**
- Consumes: `CommentModerationService.lockReportable`, `addOpenReport` (Task 2).
- Produces:
  - `enum ReportReason { SPAM, HARASSMENT, MISINFORMATION, PRIVACY, OTHER }`
  - `record ReportCommentRequest(@NotNull ReportReason reason, @Size(max = 1000) String details)`
  - `record CommentReportResponse(long reportId, String status)`
  - `CommentReportService.report(Long commentId, Long reporterId, ReportCommentRequest) -> ReportOutcome(CommentReportResponse response, boolean created)`
  - `ContentReport` entity: insert-only factory `open(commentId, reporterId, reason, details, createdAt)`, with no setters.
  - `ContentReportRepository.findOpenByCommentIdAndReporterId(long commentId, long reporterId) -> Optional<ContentReport>`
- Endpoint: `POST /api/user/comments/{commentId}/reports`, `@RequiresRegisteredUser`. Returns 201 for a new report and 200 for a duplicate.

- [ ] **Step 1: Failing tests:**
  - `guestGets401`
  - `reportCreates201AndIncrementsCount`: `report_count = 1` and the row is OPEN.
  - `duplicateReturns200SameIdCountUnchanged`: a different reason in the second call → same `reportId`, count still 1.
  - `ownCommentGets403`
  - `removedDeletedNonPublicGet404`
  - `otherWithoutDetailsGets400`, `detailsOver1000Gets400`
  - `eleventhReportInAnHourGets429`
  - `refusedAttemptsDoNotConsumeQuota`: 10 attempts that return 404, then a valid report → 201.
- [ ] **Step 2:** Run `./mvnw test -Dtest=CommentReportEndpointTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Copy `CaseUploadRateLimitGuard` with `POLICY = "user-comment-report"`. In the service (`@Transactional`): reserve the rate-limit slot. `lockReportable` checks the comment, then throw `AccessDeniedException` if the author is the reporter. If an OPEN report by this reporter exists, return it with `created=false`. Otherwise `save` the new report and call `addOpenReport`. On `NotFoundException` or `AccessDeniedException`, refund the slot and rethrow. Trim details and turn blank into null before validation. Enforce "OTHER requires details" in the service with a `ValidationException` (400).
- [ ] **Step 4:** Run the same test. Expected: PASS.
- [ ] **Step 5: Commit** `feat(governance): let registered users report comments [CT-019]`

### Task 5: Moderation queue and detail (governance)

**Files:**
- Create: `modules/governance/CommentModerationQueueItem.java`, `modules/governance/CommentModerationDetail.java`, `modules/governance/ContentReportView.java`, `modules/governance/ModerationService.java`, `modules/governance/ModerationController.java`, `modules/governance/internal/ModerationServiceImpl.java`
- Modify: `ContentReportRepository`
- Test: `src/test/java/.../modules/governance/ModerationQueueTest.java`

**Interfaces:**
- Consumes: `CommentModerationService.getModerationViews` (Task 2) and `CaseQueryService.getModerationCaseRefs` (Task 3).
- Produces:
  - `record CommentModerationQueueItem(long commentId, long caseId, String caseName, boolean casePublic, String bodyExcerpt, PublicUserResponse author, boolean authorDeleted, long openReports, Map<ReportReason, Long> reasons, LocalDateTime oldestOpenAt, LocalDateTime newestOpenAt)`. `bodyExcerpt` is the first 200 characters, or null when the author deleted the comment.
  - `record ContentReportView(long id, long reporterId, ReportReason reason, String details, String status, String resolution, Long moderatorId, String resolutionNotes, LocalDateTime createdAt, LocalDateTime resolvedAt)`
  - `record CommentModerationDetail(CommentModerationView comment, String caseName, boolean casePublic, List<ContentReportView> reports)`
  - `ModerationService.listQueue(Pageable) -> PagedResponse<CommentModerationQueueItem>` and `ModerationService.getDetail(Long commentId) -> CommentModerationDetail`
  - Endpoints (`@RequiresModerator`): `GET /api/admin/moderation/comments` (page/size, default 20, max 50), `GET /api/admin/moderation/comments/{commentId}` (404 if no comment).
- Repository queries:
  - A grouped native query over OPEN rows: `comment_id, count(*), min(created_at), max(created_at)`, ordered by `min(created_at), comment_id`, plus a count query.
  - Per-reason counts for the page's comment ids.
  - All reports of one comment, ordered by `created_at, id`.

- [ ] **Step 1: Failing tests:**
  - `queueGroupsByCommentOldestFirst`: three reports on A and one older report on B → B, then A with `openReports=3`.
  - `queueCountsReasons`
  - `queueExcludesResolved`
  - `queueIncludesTakenDownCase`: `casePublic=false`.
  - `queueSizeAbove50Gets400`
  - `registeredUserGets403`
  - `detailShowsAllReportsAndRemovedBody`
- [ ] **Step 2:** Run `./mvnw test -Dtest=ModerationQueueTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Aggregate first, then make one batched call each to community and casefile.
- [ ] **Step 4:** Run the same test. Expected: PASS.
- [ ] **Step 5: Commit** `feat(governance): add the reported-comment queue and detail [CT-019]`

### Task 6: Remove and keep decisions with audit (governance)

**Files:**
- Create: `modules/governance/RemoveCommentRequest.java`, `modules/governance/KeepCommentRequest.java`, `modules/governance/ModerationDecisionResponse.java`, `modules/governance/CommentRemovedEvent.java`
- Modify: `ModerationService`, `ModerationServiceImpl`, `ModerationController`, `ContentReportRepository`
- Test: `ModerationDecisionTest.java`, `ModerationAuditFailureTest.java` (modelled on `ReviewAuditFailureTest`), `ModerationConcurrencyTest.java` (modelled on `ReviewDecisionConcurrencyTest`)

**Interfaces:**
- Consumes: `lockForDecision`, `remove`, `clearOpenReports` (Task 2); `ActorLookup`, `AuditRecorder`.
- Produces:
  - `record RemoveCommentRequest(@NotBlank @Size(max = 2000) String notes)`, `record KeepCommentRequest(@Size(max = 2000) String notes)`
  - `record ModerationDecisionResponse(long commentId, String resolution, int resolvedReports)`
  - `record CommentRemovedEvent(String eventId, long caseId, long commentId, List<Long> reporterIds)` with `of(caseId, commentId, reporterIds)` → eventId `"comment-removed:" + commentId`
  - `ModerationService`:
    - `@AuditedOperation(COMMENT_REMOVED) remove(String actorEmail, Long commentId, RemoveCommentRequest)`
    - `@AuditedOperation(CONTENT_REPORT_DISMISSED) keep(String actorEmail, Long commentId, KeepCommentRequest)`
  - Endpoints (`@RequiresModerator`): `POST /api/admin/moderation/comments/{commentId}/remove` and `POST .../keep`. Both return 200.
- `ContentReportRepository` additions:
  - `findOpenByCommentIdOrderById(long commentId) -> List<ContentReport>`. Under the comment lock no report can be added at the same time, so this list is exactly the set of rows resolved next.
  - `@Modifying resolveOpen(long commentId, ReportResolution resolution, long moderatorId, String notes, LocalDateTime at) -> int`: `UPDATE ... SET status='RESOLVED', resolution, moderator_id, resolution_notes, resolved_at WHERE comment_id=:commentId AND status='OPEN'`. Assert that the returned count equals the list size.
- Rules for both decisions:
  - Use `lockForDecision`.
  - If the moderator wrote the comment → 403.
  - If there are no OPEN reports → 409.
  - Remove only: if the comment is already removed → 409.
  - Remove: `resolveOpen(REMOVED)`, then `remove(...)`, then one audit event (target comment, `resolvedReports=N`), then publish `CommentRemovedEvent` with the distinct reporter ids.
  - Keep: read the OPEN rows, `resolveOpen(DISMISSED)`, `clearOpenReports`, one audit event per report (target report id, `commentId`), and no event.
  - The audit actor role is mapped as in `ReviewServiceImpl.auditRole`.

- [ ] **Step 1: Failing tests:**
  - `removeResolvesAllOpenReportsWithOneAuditEvent`: 3 open → 3 rows RESOLVED/REMOVED with notes and moderator, 1 `COMMENT.REMOVED` with `resolvedReports=3`, comment REMOVED, count 0.
  - `removeLeavesEarlierDismissalsUntouched`
  - `keepDismissesAllWithOneAuditEventPerReport`: 2 open → 2 `CONTENT_REPORT.DISMISSED` events, comment still VISIBLE.
  - `keepNotesOptional`, `removeNotesRequired400`
  - `ownCommentGets403ForBoth`
  - `noOpenReportsGets409`, `alreadyRemovedGets409`
  - `reportAfterKeepCreatesNewOpenRow201`
  - `removeEventCarriesDistinctReporterIds`
  - `decisionsWorkOnTakenDownCase`
  - `auditFailureRollsBackRemoval`: the comment stays VISIBLE and the reports stay OPEN.
  - `twoModeratorsRemoveConcurrently`: one gets 200, the other 409.
  - `reportRacingRemovalNeverLeavesOpenReportOnRemovedComment`: run concurrently. Afterwards, `SELECT count(*) FROM content_reports r JOIN comments c ON c.id=r.comment_id WHERE r.status='OPEN' AND c.moderation_status='REMOVED'` = 0.
- [ ] **Step 2:** Run `./mvnw test -Dtest='Moderation*Test'`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4:** Run the same tests. Expected: PASS. Then run `./mvnw test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(governance): remove or keep reported comments with audit [CT-019]`

### Task 7: Notify reporters of removals (notification)

**Files:**
- Create: `src/main/resources/db/migration/V29__add_comment_removed_notifications.sql`, `modules/governance/internal/ReporterNotifier.java`
- Modify: `modules/notification/NotificationType.java`, `modules/notification/InAppNotifications.java`, `modules/notification/internal/InAppNotificationServiceImpl.java`
- Test: `src/test/java/.../modules/governance/ModerationNotificationTest.java`

**Interfaces:**
- Consumes: `CommentRemovedEvent` (Task 6).
- Produces: `NotificationType.COMMENT_REMOVED` and `InAppNotifications.notifyCommentRemoved(String eventId, Collection<Long> recipientIds, long caseId)`.
- Migration: widen `ck_notifications_type` to include `'COMMENT_REMOVED'`. Leave `ck_notifications_comment` unchanged, so `comment_id` stays null.
- Listener: `@TransactionalEventListener(AFTER_COMMIT)`, the same pattern as `FollowerNotifier`.

- [ ] **Step 1: Failing tests:**
  - `removalNotifiesEachReporterOnce`: two reports from the same user and one from another → 2 notifications.
  - `keepSendsNoNotification`
  - `redeliveryDoesNotDuplicate`: replaying the event leaves the count unchanged.
  - `notificationHasNoCommentOrNotes`: `comment_id` null.
- [ ] **Step 2:** Run `./mvnw test -Dtest=ModerationNotificationTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the same test. Expected: PASS.
- [ ] **Step 5: Commit** `feat(notification): tell reporters when a comment they reported is removed [CT-019]`

### Task 8: Web — report action, placeholder, notification

**Files:**
- Modify: `apps/web/src/components/ct/case-discussion-thread.tsx`, `apps/web/src/lib/notifications.ts`, the generated API types (regenerate them the way `d5c4ca38` did)
- Test: a unit test next to `case-discussion-thread` if one exists, otherwise `apps/web/src/components/ct/case-discussion-thread.test.tsx`

- [ ] **Step 1: Failing tests:**
  - A removed comment renders "Removed by a moderator" with no author.
  - "Report" is shown only on other people's comments that are visible and not deleted.
  - Choosing Other without details keeps Submit disabled.
  - A 200 response shows "You've already reported this comment".
  - A 429 response shows "Too many reports, try again later".
  - The `COMMENT_REMOVED` notification renders "A comment you reported was removed" and links to `/cases/{caseId}`.
- [ ] **Step 2:** Run `pnpm --filter web test`. Expected: FAIL.
- [ ] **Step 3: Implement** with the existing components. The reason picker is a small inline form under the comment.
- [ ] **Step 4:** Run `pnpm --filter web test && pnpm typecheck && pnpm lint`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): report comments and show removed-comment placeholders [CT-019]`

### Task 9: Web — admin moderation page and E2E

**Files:**
- Create: `apps/web/src/app/admin/moderation/comments/page.tsx`, `apps/web/e2e/moderation.spec.ts`
- Modify: the admin navigation (where `/admin/review` is linked), `db/dev-seed/R__dev_seed.sql` (one open report, so the page has data locally)

- [ ] **Step 1: Failing E2E** in `moderation.spec.ts`: a user reports a comment on a seeded case. The moderator opens `/admin/moderation/comments`, sees the row, expands it, enters notes and clicks Remove comment. The case page then shows "Removed by a moderator". The reporter's `/notifications` page shows the removal notice.
- [ ] **Step 2:** Run `pnpm --filter web test:e2e -- moderation` with the sandbox off. Expected: FAIL.
- [ ] **Step 3: Implement** the page with `RequireAuth admin`, `Panel` and `Pager`: a grouped row, then a detail section with the report list. Remove comment requires notes; Keep comment has optional notes. Both actions are hidden on the moderator's own comments. Refetch after each action. On 409, refetch and show "Already handled by another moderator".
- [ ] **Step 4:** Run the E2E plus `pnpm typecheck && pnpm lint`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): add the reported-comment moderation page [CT-019]`
