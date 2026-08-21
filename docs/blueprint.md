# CrowdTrace - Implementation Blueprint

> Step-by-step development guide for the CrowdTrace missing-persons registry and community investigation platform.

---

## Overview

This blueprint divides the CrowdTrace MVP into **8 iterative chunks**, each building on the previous one. Every chunk contains actionable steps sized for incremental implementation and verification.

The current repository is a minimal Spring Boot 4.1.0 / Java 21 application. The domain model, persistence, authentication, APIs, file storage, notifications, moderation, and retention workflows described below are to be implemented.

### Chunk Summary

| Chunk | Name | Dependencies | Outcome |
|---|---|---|---|
| 1 | Project Foundation | None | Runnable backend with database, migrations, configuration, and API conventions |
| 2 | Identity, Authentication & Roles | Chunk 1 | Secure accounts, pseudonyms, roles, and verified badge requests |
| 3 | Case Registry & Submission | Chunk 2 | Structured case submission, evidence upload, consent, and duplicate/minor flags |
| 4 | Review, Moderation & Case Lifecycle | Chunk 3 | Admin review queue, publication decisions, status updates, takedowns, and audit records |
| 5 | Public Discovery & Case APIs | Chunk 4 | Searchable public registry with safe field projections and filters |
| 6 | Community, Follows & Notifications | Chunks 4-5 | Case discussions, reports, follows, in-app notifications, and reporter status emails |
| 7 | Privacy, Files & Retention | Chunks 3-6 | Private sensitive files, scheduled deletion, consent records, and compliance controls |
| 8 | Admin Operations, Analytics & Release Hardening | Chunks 1-7 | Administration, metrics, API documentation, testing, deployment readiness |

### Delivery Principles

- Keep the REST contract stable and document it with OpenAPI as endpoints are introduced.
- Enforce public/admin-only visibility in backend DTOs and queries, not only in the frontend.
- Build moderation and auditability into the first version of every state-changing workflow.
- Prefer database-backed lifecycle transitions and idempotent operations for retries.
- Keep Ghana-specific policy and configuration boundaries explicit so future country expansion does not require a rewrite.

---

## Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────┐
│                         FRONTEND (Web)                              │
│  ┌────────────────┐  ┌──────────────────┐  ┌────────────────────┐  │
│  │ Public Registry│  │ Registered User  │  │ Admin / Moderator  │  │
│  │ & Case Pages   │  │ Submission +     │  │ Review Console     │  │
│  │                │  │ Community        │  │                    │  │
│  └───────┬────────┘  └─────────┬────────┘  └──────────┬─────────┘  │
└──────────┼─────────────────────┼──────────────────────┼────────────┘
           │                     │                      │
           ▼                     ▼                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         SPRING BOOT API                             │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ REST Controllers                                               │  │
│  │ /api/public/* │ /api/auth/* │ /api/user/* │ /api/admin/*       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ Service Layer                                                   │  │
│  │ Case │ Review │ Comment │ Notification │ File │ Retention       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ Security, Validation & Audit                                    │  │
│  │ Authentication │ RBAC │ Field Visibility │ Lifecycle Rules     │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
           │              │                 │                 │
           ▼              ▼                 ▼                 ▼
┌────────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐
│ PostgreSQL     │  │ AWS S3       │  │ Email        │  │ Scheduler  │
│ Domain data,   │  │ Photos and   │  │ Transactional│  │ Expiry,    │
│ audit, queues  │  │ private files│  │ notifications│  │ retention  │
└────────────────┘  └──────────────┘  └──────────────┘  └────────────┘
```

### Key Integration Points

1. **PostgreSQL** - Users, cases, comments, moderation records, follows, notifications, and audit events.
2. **AWS S3** - Public case photos and private missing-person reports, with access policy determined by file visibility.
3. **Transactional email provider** - Reporter status-change notifications and selected account/case lifecycle messages.
4. **OpenAPI** - The backend contract consumed by the separately developed frontend.
5. **Spring Scheduler** - Retention cleanup and operational maintenance jobs.

---

## Chunk 1: Project Foundation

**Goal:** Establish the backend structure, database connectivity, migration process, API conventions, and local development workflow.

### Step 1.1: Align the Spring Boot Project

```text
Tasks:
├── Keep Java 21 and Spring Boot 4.1.0 as the initial baseline
├── Add dependencies:
│   ├── Spring Web MVC
│   ├── Spring Data JPA
│   ├── Spring Security
│   ├── Bean Validation
│   ├── PostgreSQL driver
│   ├── Flyway
│   ├── S3 client
│   ├── Transactional email client
│   └── OpenAPI documentation
├── Establish package structure
│   ├── com.souldealers.crowdtracebackend.config
│   ├── com.souldealers.crowdtracebackend.controller
│   ├── com.souldealers.crowdtracebackend.dto
│   ├── com.souldealers.crowdtracebackend.entity
│   ├── com.souldealers.crowdtracebackend.repository
│   ├── com.souldealers.crowdtracebackend.security
│   ├── com.souldealers.crowdtracebackend.service
│   └── com.souldealers.crowdtracebackend.support
└── Verify the application starts successfully
```

### Step 1.2: Database Configuration

```text
Tasks:
├── Add PostgreSQL configuration using environment variables
├── Create docker-compose.yml for local PostgreSQL
├── Configure connection pooling
├── Configure Flyway migrations
├── Disable schema auto-generation outside local development
└── Verify a migration can run against a clean database
```

### Step 1.3: Development Tooling

```text
Tasks:
├── Configure structured logging with SLF4J and Logback
├── Add application profiles: local, test, staging, production
├── Configure .gitignore for secrets and local files
├── Add health and readiness endpoints
├── Create README setup instructions
└── Add a test profile with isolated database settings
```

### Step 1.4: Base API Infrastructure

```text
Tasks:
├── Create WebConfig for CORS and request limits
├── Create Jackson configuration for dates and enums
├── Create @ControllerAdvice exception handling
│   ├── Validation errors
│   ├── Authentication and authorization errors
│   ├── Not-found errors
│   ├── Invalid lifecycle transitions
│   └── Generic error response format
├── Define pagination conventions
├── Define request correlation IDs for logs
└── Add initial OpenAPI metadata
```

**Chunk 1 Deliverable:** The application runs, connects to PostgreSQL, applies migrations, exposes health checks, and returns a stable error format.

---

## Chunk 2: Identity, Authentication & Roles

**Goal:** Implement secure user accounts, pseudonymous profiles, role-based access, and verified badge requests.

### Step 2.1: Identity Schema

```text
Tasks:
├── Create migration: V1__create_users_table.sql
│   ├── id (UUID, PK)
│   ├── email (unique, private)
│   ├── password_hash
│   ├── display_name (public pseudonym)
│   ├── role (REGISTERED_USER, MODERATOR, SUPER_ADMIN)
│   ├── account_status (ACTIVE, SUSPENDED, DEACTIVATED)
│   └── created_at, updated_at
├── Create migration: V2__create_verification_requests_table.sql
│   ├── id, user_id, verification_type
│   ├── evidence_reference (private)
│   ├── status (PENDING, APPROVED, REJECTED, REVOKED)
│   ├── reviewer_id, review_notes
│   └── created_at, reviewed_at
└── Add indexes for email, role, and verification status
```

### Step 2.2: JPA Entities and Repositories

```text
Tasks:
├── Create User entity and repository
├── Create VerificationRequest entity and repository
├── Use UUID identifiers
├── Use enums for roles and lifecycle statuses
├── Protect password_hash from API serialization
└── Add repository tests for unique email and queue queries
```

### Step 2.3: Authentication and Authorization

```text
Tasks:
├── Add password encoder using BCrypt or equivalent
├── Choose session or JWT authentication for the REST API
├── Create authentication service
│   ├── Register user
│   ├── Authenticate user
│   ├── Issue and validate access credentials
│   └── Resolve current user
├── Configure endpoint authorization
│   ├── Public case browsing: unauthenticated
│   ├── Case submission and comments: registered users
│   ├── Review and moderation: moderators and Super Admins
│   └── User management and site settings: Super Admins
└── Add authorization tests for every role boundary
```

### Step 2.4: Authentication Endpoints

```text
Tasks:
├── POST /api/auth/register
├── POST /api/auth/login
├── POST /api/auth/refresh (if JWT is selected)
├── GET /api/auth/me
├── POST /api/auth/logout (if session or revocation is selected)
├── POST /api/user/verification-requests
└── GET /api/user/verification-requests/{id}
```

**Chunk 2 Deliverable:** Users can register and authenticate, public profiles use pseudonyms, and role checks prevent unauthorized access.

---

## Chunk 3: Case Registry & Submission

**Goal:** Implement the case data model, public/admin field boundary, evidence upload, consent capture, duplicate detection, and minor priority flags.

### Step 3.1: Case Database Schema

```text
Tasks:
├── Create migration: V3__create_cases_table.sql
│   ├── id (UUID, PK)
│   ├── reporter_id (FK users)
│   ├── full_name, age, gender
│   ├── last_seen_date, last_seen_location, region
│   ├── physical_description, clothing, circumstances
│   ├── public_contact_number
│   ├── review_status (SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, TAKEN_DOWN)
│   ├── case_status (MISSING, FOUND_SAFE, FOUND_DECEASED, CLOSED)
│   ├── priority_minor, duplicate_flag
│   ├── closing_statement
│   └── created_at, approved_at, resolved_at, updated_at
├── Create migration: V4__create_case_sensitive_details_table.sql
│   ├── case_id (PK/FK)
│   ├── medical_conditions
│   ├── known_associates
│   ├── vehicle_info
│   ├── social_media_handles
│   └── reporter_relationship
├── Create migration: V5__create_case_files_table.sql
│   ├── id, case_id, storage_key
│   ├── file_type, visibility, checksum
│   ├── uploaded_at, deleted_at
│   └── uploaded_by
└── Create migration: V6__create_case_consents_table.sql
    ├── id, case_id, user_id
    ├── consent_type, consent_version
    ├── accepted_at, source_ip
    └── revoked_at (nullable)
```

### Step 3.2: Case Entities and Visibility Projections

```text
Tasks:
├── Create Case, CaseSensitiveDetails, CaseFile, and CaseConsent entities
├── Create repositories with pagination and status filters
├── Create PublicCaseResponse
│   └── Contains only fields approved for public display
├── Create ReporterCaseResponse
│   └── Contains the reporter's permitted case fields
├── Create AdminCaseResponse
│   └── Includes sensitive fields and review metadata
├── Prevent entities from being returned directly by controllers
└── Add tests proving admin-only fields never appear in public responses
```

### Step 3.3: Case Submission API

```text
Tasks:
├── Create CaseSubmissionRequest DTO
│   ├── Public case details
│   ├── Sensitive details
│   ├── Public contact number
│   ├── File references or multipart upload
│   └── Explicit consent fields
├── POST /api/user/cases
├── Validate required fields and age/date formats
├── Require a missing-person report upload
├── Store initial status as SUBMITTED or UNDER_REVIEW
├── Persist consent version and timestamp
└── Return a case reference without exposing sensitive data
```

### Step 3.4: Duplicate and Minor Detection

```text
Tasks:
├── Create DuplicateDetectionService
│   ├── Compare normalized full name
│   ├── Compare last-seen and reported dates
│   └── Return possible matches with confidence/reason metadata
├── Set duplicate_flag without blocking submission
├── Create MinorPriorityService
│   └── Set priority_minor when age at disappearance is under 18
├── Store detection results for moderator review
└── Add tests for false positives, missing dates, and boundary age 18
```

**Chunk 3 Deliverable:** A registered user can submit a case with mandatory evidence and consent, while sensitive fields remain protected and review flags are generated.

---

## Chunk 4: Review, Moderation & Case Lifecycle

**Goal:** Implement human review before publication, reported-content moderation, case status transitions, takedowns, and auditable administrative actions.

### Step 4.1: Review Queue Schema

```text
Tasks:
├── Create migration: V7__create_case_reviews_table.sql
│   ├── id, case_id, reviewer_id
│   ├── decision (APPROVED, REJECTED, REQUEST_CHANGES)
│   ├── review_source (HUMAN, FUTURE_AI)
│   ├── review_notes
│   └── created_at
├── Create migration: V8__create_audit_events_table.sql
│   ├── id, actor_id, action, entity_type, entity_id
│   ├── metadata (JSON)
│   └── created_at
└── Add indexes for pending review, minor priority, duplicate flag, and date
```

### Step 4.2: Moderator Review Endpoints

```text
Tasks:
├── GET /api/admin/review/cases
│   ├── Filters: status, minor priority, duplicate flag, date
│   └── Pagination and stable ordering
├── GET /api/admin/review/cases/{id}
├── POST /api/admin/review/cases/{id}/approve
├── POST /api/admin/review/cases/{id}/reject
├── POST /api/admin/review/cases/{id}/request-changes (optional)
├── Require review notes for rejection or takedown
├── Notify the reporter of the decision
└── Write an audit event for every decision
```

### Step 4.3: Case Lifecycle Service

```text
Tasks:
├── Create CaseLifecycleService
│   ├── approve(caseId, moderatorId)
│   ├── reject(caseId, moderatorId, reason)
│   ├── updateStatus(caseId, actorId, newStatus)
│   ├── takeDown(caseId, adminId, reason)
│   └── addClosingStatement(caseId, actorId, statement)
├── Enforce separate state machines
│   ├── Review: SUBMITTED → UNDER_REVIEW → APPROVED or REJECTED
│   ├── Approved cases are published with case_status = MISSING
│   ├── Case outcome: MISSING → FOUND_SAFE, FOUND_DECEASED, or CLOSED
│   ├── Review state APPROVED → TAKEN_DOWN is an admin-only visibility action
│   └── Admin override rules are explicit and audited
├── Apply respectful public presentation for FOUND_DECEASED
├── Notify followers on status changes
└── Email the original reporter on status changes
```

### Step 4.4: Comment Moderation

```text
Tasks:
├── Create migration: V9__create_content_reports_table.sql
├── Create reported-comment queue
├── POST /api/user/comments/{id}/reports
├── GET /api/admin/moderation/comments
├── POST /api/admin/moderation/comments/{id}/remove
├── Record reason, reviewer, resolution, and timestamp
├── Hide removed comments from public responses
└── Preserve an admin-visible moderation record
```

**Chunk 4 Deliverable:** Cases cannot become public without human approval, moderators can handle reports and takedowns, and lifecycle changes are validated and auditable.

---

## Chunk 5: Public Discovery & Case APIs

**Goal:** Expose a safe, searchable public registry for approved cases and provide stable case-detail APIs for the frontend.

### Step 5.1: Public Case Listing

```text
Tasks:
├── GET /api/public/cases
│   ├── Search by name
│   ├── Filter by region
│   ├── Filter by age range
│   ├── Filter by gender
│   ├── Filter by status
│   ├── Filter by last-seen/reported date range
│   └── Pagination
├── Return only LIVE and resolved public cases
├── Exclude rejected, pending, taken-down, and admin-only data
├── Add indexes for search and common filters
└── Define stable empty and validation responses
```

### Step 5.2: Public Case Details

```text
Tasks:
├── GET /api/public/cases/{id-or-slug}
├── Return public identity, description, contact number, status, and closing statement
├── Return public photo URLs only
├── Include follow/comment summary without exposing private identities
├── Include respectful resolution banner metadata when applicable
└── Return canonical share URL for Copy Link behavior
```

### Step 5.3: Media Delivery

```text
Tasks:
├── Define photo storage and delivery policy
├── Keep missing-person reports private
├── Do not expose raw S3 keys in public APIs
├── Validate file type and size before persistence
├── Add image metadata and safe filename handling
└── Add access tests for public photos versus private reports
```

**Chunk 5 Deliverable:** The frontend can browse, search, filter, share, and display approved cases without receiving sensitive fields.

---

## Chunk 6: Community, Follows & Notifications

**Goal:** Implement case discussions, pseudonymous participation, follow state, in-app notifications, and reporter email alerts.

### Step 6.1: Discussion Threads

```text
Tasks:
├── Create migration: V10__create_comments_table.sql
│   ├── id, case_id, author_id
│   ├── body, moderation_status
│   ├── report_count
│   └── created_at, removed_at
├── POST /api/user/cases/{id}/comments
├── GET /api/public/cases/{id}/comments
├── Require authenticated users to post
├── Display pseudonym and verified badge only
├── Paginate comments
└── Prevent comments on unavailable or taken-down cases
```

### Step 6.2: Follows

```text
Tasks:
├── Create migration: V11__create_case_follows_table.sql
│   ├── user_id, case_id (composite unique key)
│   └── created_at
├── POST /api/user/cases/{id}/follow
├── DELETE /api/user/cases/{id}/follow
├── GET /api/user/follows
├── Make follow/unfollow idempotent
└── Ensure a user cannot follow a private or rejected case
```

### Step 6.3: In-App Notifications

```text
Tasks:
├── Create migration: V12__create_notifications_table.sql
│   ├── id, recipient_id, case_id
│   ├── type, payload, read_at
│   └── created_at
├── Generate notification for new comment on followed case
├── Generate notification for case status changes
├── GET /api/user/notifications
├── GET /api/user/notifications/unread-count
├── POST /api/user/notifications/{id}/read
└── Avoid notifying the comment author about their own comment
```

### Step 6.4: Email Notifications

```text
Tasks:
├── Create EmailService interface and provider adapter
├── Create reporter-status-change template
├── Send only to the original reporter for status changes in MVP
├── Add retry and failure logging
├── Do not include admin-only case fields in email content
└── Make delivery idempotent for retried lifecycle events
```

**Chunk 6 Deliverable:** Registered users can participate pseudonymously, follow cases, receive in-app updates, and reporters receive important status-change emails.

---

## Chunk 7: Privacy, Files & Retention

**Goal:** Implement private file handling, consent records, retention cleanup, and operational controls required for Ghana data protection obligations.

### Step 7.1: File Storage Service

```text
Tasks:
├── Configure private S3 bucket for missing-person reports
├── Configure photo storage and delivery policy
├── Create FileStorageService
│   ├── upload(file, visibility)
│   ├── createAuthorizedDownloadUrl(fileId, actor)
│   ├── delete(fileId)
│   └── validateMetadata(file)
├── Use generated storage keys, never user-provided paths
├── Enforce file size and MIME-type limits
└── Mock S3 in unit/integration tests
```

### Step 7.2: Consent and Privacy Controls

```text
Tasks:
├── Store consent version, timestamp, user, and source context
├── Add privacy-aware DTO mappers
├── Add authorization tests for sensitive fields and files
├── Prevent sensitive values from application logs
├── Add case correction/takedown audit records
└── Prepare policy references for legal review
```

### Step 7.3: Retention Cleanup Job

```text
Tasks:
├── Enable scheduled jobs
├── Create RetentionCleanupJob
│   ├── Find cases closed or resolved more than 12 months ago
│   ├── Delete sensitive case fields
│   ├── Delete private report files
│   ├── Preserve public historical fields
│   └── Write an audit event for each cleanup
├── Make cleanup retry-safe and idempotent
├── Add dry-run mode for staging
├── Add metrics for scanned, cleaned, failed, and skipped records
└── Alert on failed job executions
```

**Chunk 7 Deliverable:** Sensitive files and fields are protected during normal operation and removed predictably after the retention window.

---

## Chunk 8: Admin Operations, Analytics & Release Hardening

**Goal:** Complete platform operations, verification management, analytics, observability, testing, and deployment readiness.

### Step 8.1: Admin and Moderator Management

```text
Tasks:
├── GET /api/admin/users and /api/admin/users/{id}
├── POST /api/admin/moderators
├── PUT /api/admin/moderators/{id}/deactivate
├── GET /api/admin/verification-requests
├── POST /api/admin/verification-requests/{id}/approve
├── POST /api/admin/verification-requests/{id}/reject
├── POST /api/admin/verification-requests/{id}/revoke
├── Restrict moderator management to Super Admins
└── Audit every role and verification change
```

### Step 8.2: Admin Case and Platform Views

```text
Tasks:
├── Create admin case list with review and lifecycle filters
├── Create case detail view with sensitive fields and file access
├── Add reported-comment queue
├── Add audit-event search by actor, entity, action, and date
├── Add correction/takedown support actions
└── Ensure all support views apply least-privilege access
```

### Step 8.3: Analytics and Operational Metrics

```text
Tasks:
├── GET /api/admin/analytics/overview
├── Track case submissions, approvals, rejections, and review time
├── Track live, resolved, and taken-down cases
├── Track comments, reports, removals, and moderation response time
├── Track active users, follows, and notification delivery
├── Track retention job success/failure
├── Use aggregate database queries rather than loading all records
└── Avoid sensitive case details in analytics payloads
```

### Step 8.4: OpenAPI and API Contract Review

```text
Tasks:
├── Document every endpoint, role requirement, and response projection
├── Document public versus admin-only fields
├── Document validation and error responses
├── Document pagination and filtering
├── Document lifecycle transition errors
├── Generate an API client contract for frontend alignment
└── Review breaking changes before release
```

### Step 8.5: Scheduled Jobs and Observability

```text
Tasks:
├── Add job execution logs and metrics
├── Add health checks for database, S3, and email provider
├── Add structured audit logging for state changes
├── Configure alerting for failed notifications and retention jobs
├── Add request IDs to application logs
└── Verify no secrets or unnecessary personal data appear in logs
```

**Chunk 8 Deliverable:** Moderators can operate the platform, operators can measure it, and the backend is documented and hardened for launch.

---

## Frontend Integration Guide

> Frontend implementation is outside this backend repository. This section defines the backend-facing page and API needs.

### Public Registry Pages

```text
Pages:
├── Home / Case Discovery
│   ├── Search by name
│   ├── Region, age, gender, status, and date filters
│   └── Recent or featured cases if later approved
├── Case Detail
│   ├── Public case fields and photos
│   ├── Contact-if-found action
│   ├── Resolution banner where applicable
│   ├── Copy Link action
│   └── Discussion thread
└── Static Pages
    ├── Terms of Service
    ├── Privacy Policy
    ├── Content and retention policy
    └── Contact / support
```

### Registered User Pages

```text
Pages:
├── Register / Login
├── Profile and pseudonym settings
├── Case submission form
│   ├── Public details
│   ├── Sensitive details
│   ├── Report upload
│   └── Consent acknowledgement
├── My submitted cases
├── Followed cases
├── Notifications
├── Verification request
└── Case discussion composer
```

### Admin and Moderator Pages

```text
Pages:
├── Login
├── Review queue
│   ├── Minor priority and duplicate flags
│   ├── Evidence preview/download
│   ├── Approve/reject actions
│   └── Review notes
├── Live case management
├── Reported comments queue
├── Verification requests
├── Audit events
├── Analytics overview
└── Super Admin settings
    └── Moderator account management
```

---

## Deployment Checklist

### Pre-Deployment

```text
Tasks:
├── Security review
│   ├── No secrets in source control
│   ├── Authentication and authorization tests pass
│   ├── Input and file validation complete
│   ├── SQL injection and XSS defenses verified
│   └── Sensitive fields excluded from public responses and logs
├── Privacy review
│   ├── Consent capture verified
│   ├── Private S3 access verified
│   ├── Retention cleanup tested in dry-run mode
│   └── Audit events contain sufficient but minimal data
├── Performance review
│   ├── Pagination and database indexes in place
│   ├── N+1 queries resolved
│   ├── Search query plans reviewed
│   └── Connection pool configured
├── Testing complete
│   ├── Unit tests passing
│   ├── Integration tests passing
│   ├── Security tests passing
│   └── Manual moderation and failure-path QA complete
└── Documentation
    ├── API documentation published
    ├── Environment setup guide complete
    ├── Moderator runbook complete
    └── Deployment and rollback runbook complete
```

### Suggested Production Infrastructure

```text
Components:
├── Compute: Managed container PaaS or AWS ECS/Fargate
├── Database: Managed PostgreSQL
├── Storage: AWS S3
├── Email: AWS SES or selected transactional provider
├── TLS: Managed certificates
├── DNS: Managed DNS provider
├── Monitoring: Platform monitoring plus application metrics
└── Secrets: Managed secrets service
```

### Environment Variables

```text
Required:
├── DATABASE_URL
├── DATABASE_USERNAME
├── DATABASE_PASSWORD
├── AUTH_SECRET
├── AWS_REGION
├── S3_BUCKET_NAME
├── S3_PRIVATE_BUCKET_NAME
├── EMAIL_PROVIDER_API_KEY
├── EMAIL_FROM_ADDRESS
├── APP_BASE_URL
└── CORS_ALLOWED_ORIGINS
```

---

## Testing Strategy

### Unit Tests

```text
Coverage:
├── Services: case submission, lifecycle, moderation, follows, notifications
├── Security: role checks and field visibility
├── Validators: required fields, age/date rules, file metadata
├── Duplicate detection and minor priority logic
├── Retention eligibility and deletion planning
└── DTO mappers: public, reporter, and admin projections
```

### Integration Tests

```text
Coverage:
├── REST endpoints with MockMvc or equivalent
├── PostgreSQL migrations and repository queries
├── Authentication and authorization boundaries
├── File storage with mocked S3
├── Email sending with mocked provider
├── Scheduled retention cleanup
└── Audit event creation for state changes
```

### End-to-End Scenarios

```text
Scenarios:
├── Reporter: Register → Submit case → Upload report → Consent
├── Moderator: Review → Approve → Case appears publicly
├── Community: Browse → Comment → Follow → Receive notification
├── Moderator: Reported comment → Review → Remove → Audit
├── Reporter: Update status → Followers notified → Reporter email sent
├── Operator: Closed case reaches retention date → Sensitive data deleted
└── Edge cases: Duplicate warning, minor priority, invalid transition, takedown, failed email
```

---

## Risk Mitigation

| Risk | Mitigation |
|---|---|
| False or malicious submissions | Mandatory report upload, human review, audit trail, and manual takedown |
| Sensitive information leakage | DTO projections, private file storage, authorization tests, and retention cleanup |
| Abusive discussion content | Account-gated posting, pseudonyms linked to accounts, user reports, and moderation queue |
| Moderator backlog | Minor priority flags, queue metrics, clear review procedures, and staffing targets |
| Duplicate cases | Non-blocking fuzzy matching with moderator confirmation |
| Email or storage provider outage | Retry-safe adapters, failure logging, and operational alerts |
| Country expansion complexity | Keep country-specific rules and policies configurable and legally reviewed |
| Data deletion failure | Idempotent scheduled job, dry-run mode, audit records, and retry alerts |

---

## Success Metrics for MVP

- [ ] Users can register and authenticate with role boundaries enforced.
- [ ] A registered user can submit a case with a mandatory report and consent.
- [ ] Moderators can approve/reject cases and see minor/duplicate flags.
- [ ] Only approved cases and public fields appear in public APIs.
- [ ] Users can search cases using all defined MVP filters.
- [ ] Registered users can comment, report comments, follow cases, and receive notifications.
- [ ] Reporters receive status-change email notifications.
- [ ] Sensitive reports are private and authorized access is auditable.
- [ ] The 12-month retention cleanup is retry-safe and observable.
- [ ] API documentation and moderation runbooks are complete.
- [ ] Security, privacy, integration, and end-to-end tests pass.

---

*Blueprint generated from the CrowdTrace Product Specification.*
