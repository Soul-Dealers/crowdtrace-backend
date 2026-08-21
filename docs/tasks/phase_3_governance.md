# Phase 3 — Review, Moderation, Lifecycle, and Audit

**Goal:** Ensure publication, status changes, takedowns, and comment moderation are controlled,
validated, and auditable.

**Five Questions:**

- **Q1:** Only human-reviewed cases become public, and public records remain accurate and respectful.
- **Q2:** Invalid transitions, unreviewed publication, silent takedowns, and unaudited moderation decisions are impossible.
- **Q3:** Review, lifecycle, moderation, and audit services own state changes transactionally.
- **Q4:** A transition/permission matrix plus audit integration tests prove every allowed and rejected path.
- **Q5:** Do not let controllers mutate status directly or let future AI review replace human approval.

## Tasks

### CT-016 — Create review, audit, and comment-report persistence

- **Labels:** `epic:governance`, `type:infra`, `priority:p0`
- **Depends on:** CT-011
- **Issue:** Add review records, audit events, and content-report schema with reviewer, decision,
  source, notes, actor, target, metadata, timestamps, report reason/status, and resolution fields.
- **Acceptance criteria:** Review and report queues are indexable; audit metadata is structured and
  minimizes personal data; report state and review decisions are attributable.
- **Tests:** Migration and queue-query tests.

### CT-017 — Implement moderator review queue and decisions

- **Labels:** `epic:governance`, `type:feature`, `type:security`, `priority:p0`
- **Depends on:** CT-012, CT-016, CT-020
- **Issue:** Implement review listing/detail and approve, reject, and optional request-changes
  endpoints. Surface evidence access, duplicate/minor flags, require rejection notes, notify the
  reporter through the notification port, and write audit events.
- **Acceptance criteria:** Only moderators/Super Admins can decide; approve makes only safe fields
  public; reject keeps the case private; every decision records actor/time/notes; queue filters are stable.
- **Tests:** Role, decision, projection, notes, notification-event, and audit integration tests.

### CT-018 — Implement case lifecycle transitions, status updates, and takedown

- **Labels:** `epic:governance`, `type:feature`, `type:security`, `priority:p0`
- **Depends on:** CT-017
- **Issue:** Implement separate review and public-status state machines. Support reporter/admin
  updates, closing statements, admin takedown, respectful `FOUND_DECEASED` presentation metadata,
  follower events, and reporter status-change events.
- **Acceptance criteria:** Invalid transitions fail; only the reporter or admin may update a live case;
  takedown is admin-only; status changes and closing statements are audited; notifications are emitted transactionally.
- **Tests:** Full transition matrix, ownership, takedown, deceased-banner, and event tests.

### CT-019 — Implement comment reporting and moderation resolution

- **Labels:** `epic:governance`, `type:security`, `priority:p0`
- **Depends on:** CT-016, CT-025
- **Issue:** Implement authenticated comment reports, moderator queue, remove/resolution endpoint,
  resolution notes, and public filtering of removed comments.
- **Acceptance criteria:** A user can report a visible comment with a reason; duplicate report behavior
  is defined; moderators can resolve/remove; removed comments remain admin-visible and auditable only.
- **Tests:** Report authorization, queue, resolution, public-hidden, and audit tests.

### CT-020 — Centralize auditable state-change recording

- **Labels:** `epic:governance`, `type:security`, `priority:p0`
- **Depends on:** CT-016
- **Issue:** Provide a transaction-safe audit recorder used by review, lifecycle, moderation,
  verification, role, file-access, and retention operations. Define an action vocabulary and redaction rules.
- **Acceptance criteria:** Required state changes cannot succeed without an audit event; audit payloads
  exclude passwords, raw files, and unnecessary sensitive values; recorder failures roll back protected changes.
- **Tests:** Transaction rollback, redaction, action-vocabulary, and required-event tests.

## Verification

- Submit → review → approve/reject with audit and reporter event
- Live case status update, invalid transition, takedown, and respectful deceased presentation
- Reported comment → moderator resolution → hidden public response
