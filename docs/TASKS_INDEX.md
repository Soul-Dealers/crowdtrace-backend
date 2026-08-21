# CrowdTrace — GitHub Task Index

**Last updated:** 2026-08-20  
**Status:** Backlog prepared for review; no implementation task is marked complete  
**Source of truth:** This index and the linked phase files

This backlog converts the CrowdTrace blueprint into GitHub-issue-sized work. It follows the
`docs/epics/cloud9/` task-index pattern, but is kept under `crowdtrace/` because Cloud9 is a
separate product.

## Source hierarchy

When documents disagree, use this order:

1. `docs/product-spec.md` — product behavior, privacy boundaries, user stories, and release criteria.
2. `docs/blueprint.md` — implementation sequencing, technical shape, and verification strategy.
3. `docs/product classification.md` — product, trust, safety, and compliance constraints.
4. `docs/crowdtrace-spec.md` — earlier technical context; update it when a decision changes.

The current application is a Spring Boot 4.1.0 / Java 21 starter. All tasks below are pending
unless implementation evidence is added to this index.

## GitHub issue conventions

Create one GitHub issue per task below. Use the task ID at the start of the issue title:
`[CT-001] Establish Spring Boot foundation and dependency baseline`.

Suggested labels:

| Label | Meaning |
|---|---|
| `epic:foundation` … `epic:release` | Owning phase/group |
| `type:feature` | User-visible or domain capability |
| `type:security` | Authorization, privacy, or safety control |
| `type:infra` | Runtime, database, storage, or deployment work |
| `type:test` | Dedicated test/verification work |
| `type:docs` | Contract, policy, or runbook work |
| `priority:p0` | Required for MVP release |
| `priority:p1` | Required for a controlled pilot or strongly supports MVP |
| `priority:p2` | Useful but can follow MVP |
| `blocked:decision` | Cannot be completed until an open product/vendor/legal decision is resolved |

## Phase map

| Phase | Group | Goal | Dependencies | Tasks | File |
|---|---|---|---|---:|---|
| 0 | Foundation | Runnable, testable Spring API with stable conventions | None | 5 | [phase_0_foundation.md](taskshase_0_foundation.md) |
| 1 | Identity | Accounts, pseudonyms, roles, and verification requests | 0 | 5 | [phase_1_identity.md](taskshase_1_identity.md) |
| 2 | Case registry | Case intake, consent, files, visibility projections, flags | 1 | 5 | [phase_2_case_registry.md](taskshase_2_case_registry.md) |
| 3 | Governance | Review, moderation, lifecycle, takedown, and audit | 2 | 5 | [phase_3_governance.md](taskshase_3_governance.md) |
| 4 | Discovery | Public search, details, photos, and safe media delivery | 3 | 4 | [phase_4_discovery.md](taskshase_4_discovery.md) |
| 5 | Community | Comments, follows, notifications, and email | 3–4 | 5 | [phase_5_community.md](taskshase_5_community.md) |
| 6 | Privacy | Private storage, consent enforcement, retention cleanup | 2–5 | 4 | [phase_6_privacy.md](taskshase_6_privacy.md) |
| 7 | Operations | Admin operations, observability, API contract, release readiness | 0–6 | 6 | [phase_7_operations.md](taskshase_7_operations.md) |
| **Total** | | | | **39** | |

## Five Questions guardrails

These are the architectural and business invariants that every issue must preserve.

| Business boundary | Q1 — Outcome | Q2 — Must never break | Q3 — Owner | Q4 — Proof | Q5 — Do not touch |
|---|---|---|---|---|---|
| Identity and roles | Users can act only as the identity and role granted to them | Private emails/passwords never become public; role boundaries cannot be bypassed | `security` + `identity` services and endpoint policy | Registration/login and role-matrix authorization tests | Public response DTOs and lifecycle rules unless the issue requires a contract change |
| Case intake and visibility | A reporter can submit useful information without exposing sensitive data | Missing consent/report blocks submission; admin-only fields never appear in public DTOs | Case application service + explicit DTO mappers | Submission validation and public/admin projection tests | Frontend-only visibility logic; entities returned directly from controllers |
| Review and lifecycle | Only reviewed, valid cases become or remain public | Invalid transitions, unaudited decisions, and unreviewed publication are impossible | Case lifecycle/review services | Transition matrix and audit-event integration tests | Retention deletion and public search semantics unless explicitly scoped |
| Community moderation | People can contribute under pseudonyms without making abuse unaccountable | Guests cannot post; removed comments stay hidden; moderation decisions remain traceable | Comment/report services | Comment authorization, removal, and report-resolution tests | Private account identity in public comment responses |
| Files and privacy | Evidence is available to authorized reviewers and removed when no longer justified | Private reports cannot be public; generated keys only; cleanup cannot delete public history | File storage + privacy/retention services | S3 access, authorization, deletion, and retry/idempotency tests | Raw storage paths, secrets, or sensitive values in logs |
| Notifications | Relevant people learn about decisions and updates without notification abuse | Status emails go only to the reporter; retries do not duplicate effects | Event/notification services | Idempotency, recipient, unread-count, and provider-failure tests | New email categories without a product decision |

## Dependency path

```text
CT-001..005 → CT-006..010 → CT-011..015 → CT-016..020 → CT-021..024
                                      ├→ CT-025..029
                                      └→ CT-030..033 → CT-034..039
```

The phases are ordered, but independent tests, documentation, and vendor setup can be prepared
in parallel when their acceptance criteria do not depend on unfinished application code.

## Release gates

- [ ] All P0 issues are closed or explicitly waived by the product owner.
- [ ] Public, authenticated-user, reporter, moderator, and Super Admin response boundaries are tested.
- [ ] No case can become public without human approval and an audit event.
- [ ] Sensitive files use private storage and time-limited authorized access.
- [ ] Retention cleanup is dry-run capable, retry-safe, observable, and legally reviewed.
- [ ] Unit, integration, security, and end-to-end scenarios from the blueprint pass.
- [ ] OpenAPI, environment setup, moderator procedures, privacy policy references, and deployment runbooks exist.
- [ ] Ghana Data Protection Commission registration and legal review are complete before public launch.

## Task status

Checkboxes use `[ ]` pending, `[~]` in progress, and `[x]` complete. A task is complete only
when its acceptance criteria and the relevant tests are verified.

### Phase 0 — Foundation

- [ ] CT-001 — Establish Spring Boot foundation and dependency baseline
- [ ] CT-002 — Configure PostgreSQL, Flyway, profiles, and local Compose
- [ ] CT-003 — Add API error, validation, pagination, CORS, and correlation conventions
- [ ] CT-004 — Add health/readiness, structured logging, and test infrastructure
- [ ] CT-005 — Define the initial OpenAPI contract and repository workflow

### Phase 1 — Identity

- [ ] CT-006 — Create users and verification-request persistence
- [ ] CT-007 — Implement password authentication and credential lifecycle
- [ ] CT-008 — Enforce role-based endpoint authorization
- [ ] CT-009 — Implement authentication and current-user endpoints
- [ ] CT-010 — Implement verified-badge request and admin decision workflow

### Phase 2 — Case registry

- [ ] CT-011 — Create case, sensitive-details, file, and consent schema
- [ ] CT-012 — Implement case entities, repositories, and visibility projections
- [ ] CT-013 — Implement validated case submission with mandatory report and consent
- [ ] CT-014 — Implement non-blocking duplicate detection and minor prioritization
- [ ] CT-015 — Implement file metadata validation and case-file association

### Phase 3 — Governance

- [ ] CT-016 — Create review, audit, and comment-report persistence
- [ ] CT-017 — Implement moderator review queue and decisions
- [ ] CT-018 — Implement case lifecycle transitions, status updates, and takedown
- [ ] CT-019 — Implement comment reporting and moderation resolution
- [ ] CT-020 — Centralize auditable state-change recording

### Phase 4 — Discovery

- [ ] CT-021 — Implement public case listing, filters, pagination, and indexes
- [ ] CT-022 — Implement public case details and canonical sharing data
- [ ] CT-023 — Implement public photo delivery and private-report access boundary
- [ ] CT-024 — Add public API safety and performance verification

### Phase 5 — Community

- [ ] CT-025 — Implement pseudonymous case discussion threads
- [ ] CT-026 — Implement idempotent case follows
- [ ] CT-027 — Implement in-app notifications and unread state
- [ ] CT-028 — Implement reporter status-change email delivery
- [ ] CT-029 — Add event idempotency and notification failure handling

### Phase 6 — Privacy

- [ ] CT-030 — Implement S3 storage service with generated keys and signed access
- [ ] CT-031 — Enforce consent, privacy-aware mapping, and sensitive-data logging rules
- [ ] CT-032 — Implement retention eligibility and field/file deletion
- [ ] CT-033 — Add retention dry-run, audit, metrics, retry, and alerting

### Phase 7 — Operations and release

- [ ] CT-034 — Implement moderator and Super Admin operations
- [ ] CT-035 — Implement admin case, moderation, audit, and support views
- [ ] CT-036 — Implement privacy-safe aggregate analytics
- [ ] CT-037 — Complete OpenAPI contract and frontend integration documentation
- [ ] CT-038 — Add production observability, dependency health, and operational runbooks
- [ ] CT-039 — Execute security, privacy, performance, failure-path, and release verification

## Open decisions that affect issues

- Authentication token model: server session or JWT/refresh-token flow.
- Transactional email provider and sender domain.
- S3 bucket layout, file size/type limits, and photo delivery policy.
- Ghana region taxonomy: fixed administrative list or controlled configuration.
- Moderator review service-level targets, escalation, correction, and takedown policy.
- Final consent/legal wording and Data Protection Commission launch requirements.

When a decision affects a task, add `blocked:decision`, link the decision issue, and update the
relevant task acceptance criteria before implementation.
