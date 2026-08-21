# Phase 7 — Administration, Observability, and Release Hardening

**Goal:** Give operators the controls, documentation, metrics, and verification needed for a safe
controlled pilot and public launch.

**Five Questions:**

- **Q1:** Moderators can operate the platform safely and operators can detect, investigate, and recover from failures.
- **Q2:** Admin actions remain least-privilege and auditable; metrics never expose sensitive case details; release gates cannot be bypassed silently.
- **Q3:** Admin services/controllers, aggregate analytics, observability, OpenAPI, and runbooks own these rules.
- **Q4:** Admin authorization, aggregate-query, health, failure-path, and release verification tests prove them.
- **Q5:** Do not add frontend authorization substitutes, law-enforcement access, payments, mobile APIs, or AI-only decisions.

## Tasks

### CT-034 — Implement moderator and Super Admin operations

- **Labels:** `epic:release`, `type:feature`, `type:security`, `priority:p0`
- **Depends on:** CT-008, CT-010, CT-017
- **Issue:** Implement admin user listing, moderator create/deactivate, verification decision access,
  and Super Admin-only site/account operations. Audit role and verification changes.
- **Acceptance criteria:** Only Super Admins manage moderators; moderators cannot escalate themselves;
  deactivation prevents access; every change has actor/time/reason where required.
- **Tests:** Role escalation, deactivation, ownership, audit, and response-projection tests.

### CT-035 — Implement admin case, moderation, audit, and support views

- **Labels:** `epic:release`, `type:feature`, `type:security`, `priority:p0`
- **Depends on:** CT-017, CT-019, CT-020, CT-030
- **Issue:** Add admin endpoints for case review/lifecycle filters, sensitive case detail, evidence
  access, reported-comment queue, audit-event search, correction, and takedown support actions.
- **Acceptance criteria:** Admin views apply least privilege; sensitive details are never returned to
  non-admins; evidence access is signed/audited; correction/takedown actions require explicit permissions.
- **Tests:** Admin endpoint authorization, audit search, evidence access, and support-action tests.

### CT-036 — Implement privacy-safe aggregate analytics

- **Labels:** `epic:release`, `type:feature`, `type:security`, `priority:p1`
- **Depends on:** CT-018, CT-019, CT-027, CT-033
- **Issue:** Add aggregate analytics for submissions, approvals, review time, live/resolved/taken-down
  cases, comments/reports/removals, follows, notification delivery, and retention jobs.
- **Acceptance criteria:** Queries aggregate in the database; payloads contain no case-sensitive details,
  reporter identity, or private file metadata; access is restricted to admins.
- **Tests:** Aggregate correctness, authorization, payload-redaction, and query-efficiency tests.

### CT-037 — Complete OpenAPI contract and frontend integration documentation

- **Labels:** `epic:release`, `type:docs`, `priority:p0`
- **Depends on:** CT-021, CT-022, CT-025, CT-027, CT-035
- **Issue:** Document all endpoints, roles, response projections, validation/error responses,
  pagination/filter semantics, lifecycle errors, upload/file access, and frontend integration assumptions.
- **Acceptance criteria:** Generated OpenAPI matches implemented behavior; public/admin-only fields are
  documented; frontend can generate or consume a stable client contract; breaking changes are reviewed.
- **Tests:** OpenAPI validation and contract smoke tests for representative public/private endpoints.

### CT-038 — Add production observability, dependency health, and operational runbooks

- **Labels:** `epic:release`, `type:infra`, `type:docs`, `priority:p0`
- **Depends on:** CT-004, CT-028, CT-030, CT-033
- **Issue:** Add database/S3/email health checks, job metrics/logs, request IDs, alerts for notification/
  retention failures, environment documentation, moderator runbook, deployment, rollback, and recovery procedures.
- **Acceptance criteria:** Operators can distinguish dependency failure from application failure; no secrets
  or unnecessary personal data appear in logs; runbooks cover launch, rollback, incident, and retention recovery.
- **Tests:** Health degradation, redacted-log, failed-provider, and runbook smoke-check verification.

### CT-039 — Execute security, privacy, performance, failure-path, and release verification

- **Labels:** `epic:release`, `type:test`, `type:security`, `priority:p0`
- **Depends on:** CT-001..CT-038
- **Issue:** Run the blueprint's end-to-end scenarios: registration, submission, review, public search,
  comments/follows/notifications, status changes, private files, retention, invalid transitions, and provider failures.
  Complete security, privacy, accessibility, performance, and legal-readiness checklists.
- **Acceptance criteria:** Unit, integration, security, and end-to-end suites pass; release criteria are
  checked; open decisions are resolved or explicitly waived; legal/privacy prerequisites are recorded.
- **Tests:** Full `./mvnw verify`, integration environment smoke test, performance checks, and manual moderator QA.

## Verification

- Admin operates review, moderation, verification, audit, and retention workflows
- Operators can diagnose database, S3, email, and scheduled-job failures
- OpenAPI and runbooks are complete before launch
- All release gates in `TASKS_INDEX.md` are satisfied
