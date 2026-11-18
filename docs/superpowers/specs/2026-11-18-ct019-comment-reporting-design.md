# CT-019 — Comment reporting and moderation resolution

**Issue:** #19 · **Spec:** product-spec US-06 · **Depends on:** CT-016 (schema), CT-020 (audit), CT-025 (comments)

## Why

Discussions are live, but nobody can flag an abusive comment and moderators can't act on one. CT-019 lets a
registered user report a comment, gives moderators a queue to remove it or keep it, and keeps every decision
on the record.

## Goals

- A registered user reports a visible comment with a reason.
- Reported comments appear in a moderator queue, one row per comment.
- A moderator removes the comment, which needs a resolution note, or keeps it ("Keep comment").
- A removed comment shows as "Removed by a moderator". Its text and author are never public, and its replies
  stay readable.
- Each reporter whose report led to a removal gets an in-app notification.
- Every decision is audited in the same transaction as the change.

## Non-goals

- Telling reporters when a comment is kept (Edem: say nothing about dismissals).
- Emailing about removals. This may come later; leave it out for now (Edem).
- Telling the comment author that their comment was removed.
- Action against the author's account. Account sanctions belong to CT-034.
- Reopening or editing a resolved report.

## Anti-goals

- A removed comment's text or author reaching any public response.
- A decision committing without its audit event, or an audit event without its decision.
- Hiding replies that did nothing wrong.

## Constraints already in place

- `content_reports` (V14, V17): reasons `SPAM | HARASSMENT | MISINFORMATION | PRIVACY | OTHER`, with details
  required for `OTHER` (max 1000). A trigger allows exactly one update, OPEN → RESOLVED, and only the resolution
  fields may change. A partial unique index allows one OPEN report per (comment, reporter).
- `comments` (V22, V23): `moderation_status VISIBLE | REMOVED`, `removed_at` (set iff REMOVED), `report_count`.
  Author deletion stays `VISIBLE`, sets `deleted_at` and scrubs the body.
- Audit actions exist: `COMMENT.REMOVED` (target COMMENT, `resolvedReports` count) and
  `CONTENT_REPORT.DISMISSED` (target CONTENT_REPORT, `commentId`). Audit rows are append-only.
- Module rule: no imports of another module's `internal`. Cross-module writes go through public services, and side
  effects run in after-commit events.

## Approaches

| | Shape | Trade-off |
|---|---|---|
| **A — chosen** | Governance owns `content_reports`, the report and moderation endpoints, resolution and audit. Community exposes a `CommentModerationService` (lock, report-count, remove, batch read). Governance opens the transaction and calls it. | Same pattern as case review (`ReviewServiceImpl` → `CaseReviewMutationService`). Adds a small community API. |
| B | Community owns comments and reports; governance's moderation endpoints delegate to it. | Fewer cross-module calls, but moderation persistence moves out of governance, unlike every other decision. |

Codex (gpt-6.1-sol) and Claude both picked A. Codex proposed hiding a removed comment's whole branch and
dismissing reports one at a time. Edem chose the placeholder and a single "Keep comment" action instead.

## Design

### Reporting — `POST /api/user/comments/{commentId}/reports` (`@RequiresRegisteredUser`)

Body: `{ reason, details? }`. Details are trimmed, max 1000 characters, and required for `OTHER`.

In one transaction, take the case's shared lock as `CommentServiceImpl.create` does, then lock the comment row:

| Situation | Result |
|---|---|
| Comment missing, on a non-public case, removed or author-deleted | 404 |
| Caller wrote the comment | 403 |
| Caller already has an OPEN report on it | 200, existing report returned unchanged, count unchanged |
| Otherwise | 201, insert report, `report_count + 1` |

The response is `{ reportId, status }`. A user may report again after a "Keep comment", which creates a new row.
Rate limit: reuse the USER rate-limit scope at 10 submissions per hour per user, duplicates included. Attempts
refused with 404 or 403 give the slot back, like `CaseUploadRateLimitGuard` *(Claude's call)*. Over the limit
returns 429. If the rate-limit store fails, the request is refused, matching the existing policy.

### Queue — `GET /api/admin/moderation/comments` (`@RequiresModerator`)

One row per comment with at least one OPEN report, oldest open report first, then comment id. Each row shows: comment
id, case id and name, body excerpt (or "deleted by author"), author pseudonym and badge, open report count,
counts per reason, and oldest and newest open-report times. Paging uses `PagedResponse`: default size 20, max 50.
Aggregate in `content_reports` first, then enrich the page with one batched community call and one batched case
call (no cross-module joins).

Reports stay in the queue after their case is taken down or otherwise stops being public, and moderators can
still decide them *(Claude's call)*. A restored case would bring the comment back, so open reports must not be
stranded. `CaseQueryService` only returns public summaries, so casefile gains a moderator-facing batch lookup
(id → name and status) for the queue and detail.

### Detail — `GET /api/admin/moderation/comments/{commentId}` (`@RequiresModerator`)

The comment's full body (kept on removal; already scrubbed if the author deleted it), moderation status, author
pseudonym and account id, case reference, and all of its reports (open and resolved): reason, details, status,
resolution, moderator, notes and times. The report list is not paginated, because reports per comment are few.

### Remove — `POST /api/admin/moderation/comments/{commentId}/remove` (`@RequiresModerator`)

Body: `{ notes }`, required, trimmed, 1–2000 characters. In one transaction, marked `@AuditedOperation`:
1. Lock the comment. If it is already REMOVED, return 409. If it has no OPEN reports, return 409. If the
   moderator wrote it, return 403.
2. Set `moderation_status = REMOVED`, `removed_at = now`, `report_count = 0`.
3. Resolve every OPEN report on it: `status RESOLVED, resolution REMOVED, moderator_id, resolution_notes,
   resolved_at`. Use an update limited to `status = 'OPEN'` rows that sets all of these fields, so the V17
   trigger passes.
4. Write one `COMMENT.REMOVED` audit event with `resolvedReports = N`.
5. Publish `CommentReportsRemovedEvent(caseId, commentId, reporterIds)` for the notification module.

Return 200 with `{ commentId, resolvedReports }`.

### Keep comment — `POST /api/admin/moderation/comments/{commentId}/keep` (`@RequiresModerator`)

Body: `{ notes? }`, optional, trimmed, max 2000. In one audited transaction: lock the comment. If it has no OPEN
reports, return 409. If the moderator wrote it, return 403, the same as Remove *(Claude's call)*; otherwise a
moderator could clear every report against their own comment. The web hides both actions on the moderator's own
comments. Resolve them all as `DISMISSED` and set `report_count = 0`. Write one
`CONTENT_REPORT.DISMISSED` audit event per report, with `commentId`. Publish no event. Return 200 with
`{ commentId, dismissedReports }`.

### Removed comments in the public thread (placeholder)

This follows the author-deletion pattern:
- Root, reply and reply-count queries include REMOVED rows. The two partial indexes from V23
  (`WHERE moderation_status = 'VISIBLE' ...`) are replaced by equivalents without the status predicate.
- `CommentResponse` gains `removed: boolean`. When true, `author` and `body` are null and the author is never
  looked up. If a comment is both removed and author-deleted, `removed` wins.
- Replying to, deleting or reporting a removed comment returns 404, as it does today.
- Listing replies under a removed parent is allowed, so the branch stays readable.
- Reply notifications (`CommentRepository` ancestor walk) already skip removed authors. No change.

### Notification

- Add `NotificationType.COMMENT_REMOVED` with a migration that widens `ck_notifications_type`. The comment-id
  rule stays as is, with `comment_id` null.
- After commit, a listener (following the existing pattern, in the module that publishes the event and calling
  `InAppNotifications`) handles `CommentReportsRemovedEvent` and stores one in-app notification
  per distinct reporter: "A comment you reported was removed", linking to the case. Use a stable dedup key per
  report (`content-report:{reportId}:removed`) with the existing recipient uniqueness and retry job. The event
  carries the resolved report ids for this. If the case is no longer public, the link leads to the case's usual
  not-found page; nothing special is done for this.
- The notification never includes moderator notes, report details or the comment text.

### Web (apps/web)

- `case-discussion-thread.tsx`: a "Report" action on others' visible comments (not your own, deleted or removed
  ones), opening a reason picker with details for Other. Show feedback for 201 and 200 ("Already reported"), and
  for 429. Render the "Removed by a moderator" placeholder.
- `/admin/moderation/comments`: queue list (`RequireAuth admin`, `Panel`, `Pager`), with a row that expands to the
  detail. "Remove comment" requires notes. "Keep comment" has optional notes. Refetch after each action, and
  refetch with a message on 409.
- Add an admin nav entry. Render the `COMMENT_REMOVED` notification. Regenerate API types.

## Risks (frequency × impact, Codex estimates)

| Risk | Score | Handling |
|---|---:|---|
| Two moderators decide the same comment | 30 | Comment row lock, recheck under lock; the second gets 409 |
| Report arrives while the comment is being removed | 35 | Report and removal share the comment lock; the report waits, then gets 404 |
| Report count drifts | 25 | Only change it in the same transaction as the report insert or resolution |
| V17 trigger rejects a write | 42 | Resolve with an update limited to OPEN rows; never save a resolved entity again |
| Decision commits without audit | 27 | One transaction with `@AuditedOperation`; an audit failure rolls back |
| Report spam after "Keep comment" | 35 | 10 per hour per user |
| Notification lost or duplicated | 16 | After-commit delivery, stable dedup key, existing retry job |

## Tests (minimum)

Report authorization and eligibility (guest, own, removed, deleted, non-public case); the duplicate returns 200
and the count is unchanged; the queue grouping and order; remove resolves all open reports with a single audit
event, and leaves earlier dismissals alone; keep writes one audit event per report; an audit failure rolls back
the whole decision; two moderators racing on PostgreSQL; the public thread shows the placeholder without author
or body, and its replies stay listable; the reporter gets exactly one notification on removal and none on keep;
an E2E test of report → moderator removes → placeholder visible.

## Left to the builder

DTO and class names, the exact excerpt length, how many queries the queue enrichment uses (one per module),
admin page layout within the existing components, and the notification copy wording.
