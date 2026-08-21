# Phase 5 — Community, Follows, and Notifications

**Goal:** Enable accountable pseudonymous discussion, case following, in-app updates, and narrowly
scoped reporter email notifications.

**Five Questions:**

- **Q1:** Registered users can contribute useful tips and track cases without exposing their account email.
- **Q2:** Guests cannot post; comments cannot attach to unavailable cases; notification retries cannot duplicate effects.
- **Q3:** Comment, follow, notification, and email services own these rules behind event contracts.
- **Q4:** Authorization, pseudonym projection, idempotency, unread-state, and provider-failure tests prove them.
- **Q5:** Do not add direct messaging, push notifications, or follower email without a product decision.

## Tasks

### CT-025 — Implement pseudonymous case discussion threads

- **Labels:** `epic:community`, `type:feature`, `priority:p0`
- **Depends on:** CT-009, CT-022
- **Issue:** Add comments and implement authenticated create plus public paginated read endpoints.
  Responses show display name and verified badge only; comments belong to exactly one available case.
- **Acceptance criteria:** Guests cannot post; authenticated users can post on live cases; removed or
  unavailable-case comments are hidden; email and private account fields never appear.
- **Tests:** Comment authorization, case-status, projection, pagination, and ownership association tests.

### CT-026 — Implement idempotent case follows

- **Labels:** `epic:community`, `type:feature`, `priority:p0`
- **Depends on:** CT-022
- **Issue:** Add unique user/case follow persistence and follow, unfollow, and list endpoints.
- **Acceptance criteria:** Follow/unfollow is idempotent; private/rejected/taken-down cases cannot be
  followed; users can list only their own follows; duplicate rows are impossible.
- **Tests:** Unique constraint, idempotency, authorization, and unavailable-case tests.

### CT-027 — Implement in-app notifications and unread state

- **Labels:** `epic:community`, `type:feature`, `priority:p0`
- **Depends on:** CT-018, CT-025, CT-026
- **Issue:** Add notification persistence and endpoints for list, unread count, and mark-read. Emit
  notifications for followed-case comments and status changes, excluding the comment author.
- **Acceptance criteria:** Only recipients can read/mark notifications; unread counts are correct;
  event payloads contain no sensitive fields; comment authors do not receive self-notifications.
- **Tests:** Event-to-recipient, unread-count, mark-read, isolation, and payload-redaction tests.

### CT-028 — Implement reporter status-change email delivery

- **Labels:** `epic:community`, `type:feature`, `type:infra`, `priority:p0`
- **Depends on:** CT-018
- **Issue:** Define an `EmailService` port and provider adapter. Send a safe status-change template
  only to the original reporter, excluding admin-only data.
- **Acceptance criteria:** Recipient is the reporter's private account contact; email content contains
  only approved information; provider failures are recorded; the lifecycle request is not blocked by a slow provider.
- **Tests:** Recipient, template-redaction, async/failure, and provider-adapter tests.

### CT-029 — Add event idempotency and notification failure handling

- **Labels:** `epic:community`, `type:test`, `type:infra`, `priority:p1`
- **Depends on:** CT-027, CT-028
- **Issue:** Add stable event IDs or deduplication records, retry policy, failure state, and operational
  visibility for notifications and email delivery.
- **Acceptance criteria:** Retried lifecycle/comment events do not duplicate notifications or emails;
  failed deliveries can be retried; permanent failures are observable without leaking message content.
- **Tests:** Duplicate-event, retry, dead-letter/failure, and recovery integration tests.

## Verification

- Registered user comments → followers receive in-app notification
- Status update → followers receive in-app notification and reporter receives one safe email
- Provider failure/retry does not duplicate side effects
