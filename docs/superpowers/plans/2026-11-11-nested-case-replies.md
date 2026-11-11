# Nested case replies and author deletion — implementation plan

**Status:** Planning complete. Implementation has not started; Edem explicitly requested the plan only.
**Goal:** Let signed-in users reply to comments and nested replies while preserving discussion beneath author-deleted placeholders.
**Spec:** ../specs/2026-11-11-nested-case-replies.md
**Decision record:** ../../adrs/ADR-001-nested-case-replies-and-author-deletion.md
**Stack:** Java 21, Spring Boot, PostgreSQL, Next.js App Router, React Query and TypeScript.

## Current behavior and cause

- `apps/web/src/components/ct/comment-thread.tsx` already renders static nested replies, but hides Reply unless an onReply handler is provided.
- `apps/web/src/app/cases/[id]/page.tsx` supplies neither a reply handler nor nested data.
- `CreateCommentRequest`, `CommentResponse`, `Comment` and V22 support a flat discussion only.
- The linked case, http://localhost:3000/cases/1000346, has existing comments. Preserve them and their IDs.

## Requirements and ownership

| Rule | Owner | Proof |
|---|---|---|
| Users can reply to a comment or live nested reply | Community service, DTOs and discussion UI | Three-level posting and reload checks |
| A reply belongs to exactly the same case as its parent | PostgreSQL constraint and service validation | Cross-case endpoint and database rejection tests |
| Each level remains paginated and bounded | Repository, cursor service and React Query | Root/child pagination and invalid-size tests |
| Only the author can delete a comment | Community service using authenticated user ID | Guest, non-owner and owner deletion tests |
| Deleted parent shows a placeholder and retains readable descendants | Public mapper and comment renderer | Root/nested deletion tests and UI checks |
| Deleted text and author identity never leak through the public API | Entity deletion method and public projection | Response allowlists, null fields and stored-body assertion |
| Pending/taken-down cases and moderator-removed comments stay inaccessible | Existing case visibility and moderation guards | Case-status and removed-parent tests |
| Existing comments, identity behavior and audit immutability remain intact | Migration and scope boundaries | Existing regression suite and migration checks |

## Exact behavior

### Thread structure and reading

- Add nullable `parent_comment_id`; null identifies a root comment.
- Retain arbitrary reply depth in storage. Cap visual indentation after two nested levels so mobile reading width remains usable.
- Without `parentCommentId`, GET returns roots only. With it, GET returns that parent’s direct children only.
- Retain newest-first ordering `(created_at DESC, id DESC)`, existing opaque cursor format, default size 20 and allowed sizes 1–50.
- Each response includes visible direct-child count; it is not a total-descendant count. Batch counts for a page instead of querying once per comment.
- Expand replies on demand and provide Show older replies when another page exists. Guests can read and expand threads.

### Author deletion

- The author gets Delete and an inline confirmation: “Delete this comment? Replies will stay visible.”
- Deletion erases the stored body by replacing it with `[deleted]` and records UTC `deleted_at` once. Keep the row and parent/child relationships.
- The public deleted-comment projection has `author: null`, `body: null`, and `deleted: true`. Keep ID, parent ID, creation time and reply count.
- Render exactly: **Comment has been deleted by author**. Do not render the former author’s name, avatar, badge or text.
- Keep deleted placeholders in paginated results, including deleted leaves. Show the replies control whenever children exist.
- Reading replies under an author-deleted parent remains allowed. Creating a new direct reply to a deleted parent is rejected; existing live descendants can still receive replies.
- Existing moderator removal remains separate: REMOVED rows remain excluded and cannot be used as reply targets. Do not label moderator removal as author deletion.
- Deleting twice as the author returns success without changing the deletion time. Guests receive 401; non-owners receive 404 and cannot change the row.

### API contract

| Endpoint | Change |
|---|---|
| `POST /api/user/cases/{caseId}/comments` | Accept `{ body: string, parentCommentId?: number }`; return 201 with CommentResponse. Omitted/null parent creates a root. |
| `GET /api/public/cases/{caseId}/comments` | Add optional `parentCommentId` alongside cursor and size; return a bounded page of roots or direct replies. |
| `DELETE /api/user/cases/{caseId}/comments/{commentId}` | Author-only, idempotent soft deletion with text erasure; return 204 with no response body. |

Extend CommentResponse with `parentCommentId: number | null`, `replyCount: number`, and `deleted: boolean`. Make author and body nullable for deleted placeholders. Public author fields remain display name, avatar seed and verification badge only; do not add account IDs, emails or viewer-specific canDelete fields.

Continue body validation at 1–2,000 characters and existing trimming. Malformed/nonpositive parent IDs and invalid pagination return 400. Missing, cross-case, moderator-removed or author-deleted write targets return 404. Reading a deleted parent’s children is valid, provided the case remains public.

## Task 1: Backend relationships, deletion and contract

**Modify:**
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/CreateCommentRequest.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/CommentResponse.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/CommentService.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/CommentController.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/internal/CommentServiceImpl.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/internal/model/Comment.java`
- `src/main/java/com/souldealers/crowdtracebackend/modules/community/internal/repository/CommentRepository.java`
- `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java`

**Create:** `src/main/resources/db/migration/V23__add_comment_replies_and_author_deletion.sql` and `src/test/java/com/souldealers/crowdtracebackend/modules/community/CommentReplyMigrationTest.java`.

**Test first:** Extend CommentEndpointTest and OpenApiContractTest. Assert observable response/database behavior, including these acceptance cases:

- [ ] `registeredUserCanReplyToACommentAndANestedReply`: root → reply → nested reply preserves exact parent associations after fresh reads.
- [ ] `rootAndReplyPagesRemainSeparateAndBounded`: root pages contain no replies; each parent’s pages contain only its children with stable cursor continuation and no duplicates.
- [ ] `rejectsCrossCaseMissingAndRemovedParents`: rejected writes create no rows; removed parent content remains hidden.
- [ ] `guestCannotPostRepliesOrDeleteComments`: both writes return 401.
- [ ] `onlyAuthorCanDeleteComment`: another account receives 404; original body/state remains unchanged.
- [ ] `deletingRootRedactsContentAndPreservesReplies`: author deletion returns 204, stored text is erased, public body/author are null, and children still load.
- [ ] `deletingNestedParentPreservesItsChildren`: deleting a reply leaves its parent link and nested descendants readable.
- [ ] `deletingTwiceIsIdempotent`: repeated author deletion returns 204 and preserves the first deletion timestamp.
- [ ] `deletedParentRemainsReadableButCannotReceiveNewDirectReplies`: child GET succeeds, new direct reply returns 404, live descendant can receive a reply.
- [ ] `unavailableCaseStillHasNoAccessibleThread`: root/reply reads and writes retain existing case visibility restrictions.
- [ ] `databaseRejectsCrossCaseAndSelfParentLinks`: composite foreign key and self-parent check reject invalid associations.
- [ ] `migrationPreservesExistingRootComments`: applying V23 after V22 leaves existing IDs, bodies, authors and timestamps intact, with null parent/deletion fields.
- [ ] `replyContractDocumentsNullableTombstonesAndAuthorDelete`: generated OpenAPI exposes the new parameter/fields, nullable author/body and secured DELETE responses.

**Implementation sequence:**

1. Run the nested-posting regression against current code and confirm it fails because parent associations are missing.
2. Add V23 with nullable parent_comment_id and deleted_at. Add unique `(id, case_id)` and composite foreign key `(parent_comment_id, case_id) → comments(id, case_id)`, plus a check preventing self-parent links.
3. Add an index for visible children on `(case_id, parent_comment_id, created_at DESC, id DESC)`. Keep an index supporting the existing content-report foreign key. Do not modify V22 or recreate comments.
4. Add immutable parent mapping and an entity method that records deletion and scrubs body. Permit body updates only for this deletion path; do not introduce comment editing. Add a check that author-deleted rows contain the sentinel body.
5. Extend service signatures to `listPublic(Long caseId, Long parentCommentId, String cursor, int size)`, existing create with the extended request, and `deleteByAuthor(Long caseId, Long commentId, Long authorId)`.
6. Validate parent availability/case association before insert. Acquire a write lock on the parent for posting and on the target for deletion, so a concurrent delete either follows an accepted reply or precedes a rejected one.
7. Add parent-scoped repository queries and one grouped direct-reply-count query per page; retain batched public author lookup and skip deleted authors when building that lookup.
8. Add the authenticated DELETE controller operation and all relevant OpenAPI status/schema annotations. Update existing response allowlists for the additional fields.
9. Run the endpoint, migration, OpenAPI and module-boundary checks; compare responses against the deletion/privacy requirements above.

## Task 2: Typed client and inline discussion UI

**Modify:** `apps/web/src/lib/api/schema.ts`, `apps/web/src/lib/case-comments.ts`, `apps/web/src/components/ct/comment-thread.tsx`, and `apps/web/src/app/cases/[id]/page.tsx`.
**Create:** `apps/web/src/components/ct/case-discussion-thread.tsx` for live thread state and recursive child fetching. Preserve static comment-thread rendering used by the style guide.

1. Regenerate schema.ts from the updated OpenAPI endpoint; do not hand-maintain generated types. Every caller must handle nullable author/body.
2. Extend `useCaseComments(caseId, size = 20, parentCommentId?, enabled = true)` with a query key containing case ID, parent ID and page size. Root and child pages must never share a cache entry.
3. Keep `usePostComment(caseId)` and accept mutation variables `{ body, parentCommentId? }`. Add `useDeleteComment(caseId)` accepting a comment ID; handle 204 without trying to unwrap a JSON envelope.
4. Invalidate the case’s root and reply queries after posting or deletion, so counts, expanded threads and placeholders refresh together.
5. Reuse the current-user profile request for the signed-in avatar seed to decide which live comments show Delete. Enforce ownership exclusively on the backend; do not personalize public GET responses.
6. Show Reply on each live comment for signed-in users. Clicking it opens an inline labelled composer beneath that comment; auto-focus it. Support Cancel, blank-body rejection, max length, pending state and inline errors. Keep the draft on failure, clear it on success and expand the parent’s replies.
7. Load child pages only when expanded; render each child with the same behavior. Include loading, empty, error/retry and older-replies states. Keep controls usable beyond two nesting levels without further increasing indentation.
8. Render deleted placeholders with surviving replies controls and no original author/text. Remove Reply and Delete actions from deleted placeholders.
9. Preserve the existing guest login prompt, pseudonymous badges, root composer and older-root-comments controls. Verify the style guide remains functional with static nested sample data.

**Interaction acceptance checks:**

- [ ] Reply to a root and then to that reply; reload, expand each level and verify both remain in the correct positions.
- [ ] Cancel retains the existing thread; a failed post keeps the draft and displays an error; rapid double submission cannot create duplicate replies from one pending form.
- [ ] Delete an owned root and an owned nested parent using confirmation; in each case the exact placeholder appears and descendants remain readable after reload.
- [ ] Sign in as another account and verify Delete is absent; verify rejected direct API deletion independently in Task 1.
- [ ] As a guest, read replies under deleted parents and expand older child pages.
- [ ] At a 375px viewport, read and operate a thread deeper than two levels without horizontal overflow.

Use the supported browser when available. The current session had no connected browser; record any interaction checks that cannot be executed instead of claiming they passed.

## Task 3: Integration and verification

Run only when implementation is separately requested. Read the installed Next.js guidance before changing UI code.

1. With Docker available, run focused backend checks:
   ```sh
   ./mvnw '-Dtest=CommentEndpointTest,CommentReplyMigrationTest,OpenApiContractTest,CrowdtraceModulesTest' test
   ```
   For this machine’s OrbStack setup, set DOCKER_HOST to the active context’s socket and TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock for the test process.
2. Run the full backend suite once, followed by the frontend checks:
   ```sh
   ./mvnw verify
   pnpm --filter web typecheck
   pnpm lint
   pnpm --filter web build
   git diff --check
   ```
   Report any failing classes explicitly; do not treat a partial pass as full-suite success.
3. After checks pass, rebuild/recreate only the local app service, preserving the PostgreSQL volume. Verify Flyway applied V23 and the backend is healthy.
4. Regenerate the client from the updated backend with `pnpm --filter web api:types`, then repeat typecheck/build only if generation changes the client.
5. Verify case 1000346 still displays its existing comments. Exercise creation/deletion with clearly labelled disposable fixtures; do not delete or rewrite the user’s existing comments for testing.
6. Review the final diff for bounded queries, deleted-text redaction, author-only deletion, same-case parent validation and untouched audit protections. Record exact checks run and remaining limitations.

## Out of scope and implementation exclusions

- No implementation, container rebuild, database mutation, commit, push or PR update as part of this planning-only task.
- No editing of existing migrations, case records, account credentials, login behavior, audit rows, audit triggers or audit repositories.
- No notifications, follows, direct messaging, comment editing or new moderator workflows.
- No unbounded recursive API response, cascading deletion, frontend-only permission enforcement or exposing the original deleted body through the public API.

## Completion criteria for the later implementation

All backend acceptance cases and frontend interaction checks above pass, existing automated checks remain green, original comments survive migration, and the local case discussion supports replies, nested replies and author-deleted placeholders with readable descendants. Any unavailable browser validation must be reported as outstanding.
