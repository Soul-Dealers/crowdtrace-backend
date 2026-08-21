# CrowdTrace — Product Specification

**Version:** 1.0 (MVP)  
**Date:** August 20, 2026  
**Author:** Edem (Product Owner)  
**Status:** Draft for development handoff

> A community-driven missing-persons platform launching in Ghana, combining a verified public case registry with structured, pseudonymous community investigation threads.

---

## 1. Product Overview

CrowdTrace helps families publish credible missing-person cases and gives the public a structured way to contribute useful information. Every public case is reviewed by a moderator before publication. Each live case includes a discussion thread where registered users can share tips, observations, and updates.

The MVP is a responsive web platform. It is designed for Ghana first, with data, language, and deployment choices that can support expansion to other African countries later.

### 1.1 Problem

Families need a straightforward and trustworthy way to publicize a disappearance. Members of the public may have useful information, but existing channels are fragmented across social media posts, private messages, and informal groups. CrowdTrace provides a durable case page, clear public contact information, moderation, and a focused discussion space.

### 1.2 Product Principles

- **Safety before reach:** sensitive information is not exposed merely because a case is public.
- **Human review before publication:** no submitted case becomes public without moderator approval.
- **Accountability with privacy:** users post under a pseudonym, while the platform retains an account identity for moderation and abuse response.
- **Useful contribution over noise:** case threads are designed for tips and updates, not unrestricted anonymous discussion.
- **Public-interest archive:** resolved cases remain available as historical records while sensitive data is removed according to the retention policy.

### 1.3 MVP Goals

- Give families a verifiable, easy-to-use way to submit missing-person cases.
- Give the public a searchable registry and a structured way to contribute leads.
- Protect private case and reporter information under Ghana's Data Protection Act, 2012 (Act 843).
- Establish a backend foundation that can support future African-market expansion.

### 1.4 Non-Goals for MVP

- Native iOS or Android applications; planned for Phase 2.
- Languages other than English.
- Official Ghana Police Service integration or privileged law-enforcement access.
- Payment, donations, or crowdfunding.
- Real-time chat, direct messaging, or push notifications.
- Automated account bans or fully automated case authenticity decisions.

---

## 2. Users, Personas & Roles

### 2.1 Personas

| Persona | Need | Main success outcome |
|---|---|---|
| Reporter / family member | Publish accurate information and receive meaningful leads | A case is approved, discoverable, and kept up to date |
| Community contributor | Find nearby or relevant cases and share information safely | A tip or update reaches the case discussion |
| Verified professional | Contribute expertise with visible credibility | Their identity is confirmed without receiving private data access |
| Moderator | Review cases and maintain safe discussions | Authentic cases are published and harmful content is handled quickly |
| Super Admin | Operate and govern the platform | Moderation, configuration, and account administration are controlled |

### 2.2 Roles and Permissions

| Role | Description | Access |
|---|---|---|
| Guest | Unauthenticated visitor | View approved cases and public fields; cannot submit, comment, follow, or report content |
| Registered User | Authenticated member using email and password | Submit cases, comment, follow cases, receive in-app notifications, and report comments |
| Verified Badge Holder | Registered user verified by an administrator | All registered-user capabilities plus a visible badge; no additional data access |
| Moderator | Admin-appointed reviewer | Review cases, manage live cases, moderate reported comments, and process verification requests |
| Super Admin | Platform owner/operator | All moderator capabilities plus moderator management and site settings |

There is deliberately no privileged Investigator role in the MVP. A police officer, NGO worker, or subject-matter expert may receive a verified badge, but the badge is a trust signal rather than a permission boundary.

---

## 3. Case Lifecycle & Core Workflows

### 3.1 Case Lifecycle

```text
[Submitted] → [Under Review] → [Approved / Live] → [Resolved]
                    ↓                 ↓
                [Rejected]       [Taken Down]

Live case status: [Missing] → [Found Safe] / [Found Deceased] / [Closed]
```

### 3.2 Case Submission

1. A registered user signs in and opens the case submission form.
2. The reporter enters structured missing-person details and the public contact number.
3. The reporter enters sensitive, admin-only information where available.
4. The reporter uploads a missing-person report or supporting document. This upload is mandatory.
5. The reporter explicitly consents to the collection of sensitive personal data, including medical information where supplied.
6. The system creates the case with `SUBMITTED` or `UNDER_REVIEW` status.
7. The system runs duplicate detection using fuzzy name matching plus reported/last-seen date matching.
8. If the missing person was under 18 at the time of disappearance, the system sets a priority flag.
9. Moderators review the submission and either approve or reject it.

### 3.3 Admin Review

The review queue must surface the uploaded report, duplicate-match warnings, and minor priority flags. A moderator may approve or reject a case with review notes. Rejected cases remain unavailable to the public and the reporter is notified.

The data model should support a future AI-assisted review step through extensible review notes and review-source fields, while retaining human moderator approval as the final decision.

### 3.4 Status Updates and Resolution

- The original reporter or an admin may update a live case's status.
- Admins have final override authority.
- Valid public statuses are `Missing`, `Found Safe`, `Found Deceased`, and `Closed`.
- A resolved case may include an optional public closing statement.
- On `Found Deceased`, the public page displays a respectful notice banner instead of emphasizing granular missing-person details.
- A status change triggers an in-app notification for followers and an email to the original reporter.

### 3.5 Duplicate, Minor, and Hoax Handling

- Possible duplicates are flagged for human review; submission is not blocked.
- Minor cases are prioritized in the moderation queue; they do not receive a separate public data model in the MVP.
- A suspected hoax is rejected before approval or taken down after publication.
- The MVP does not automatically ban accounts for hoaxes. Enforcement remains a manual admin decision.

### 3.6 Community Contribution Flow

1. A visitor opens an approved case page.
2. A registered user posts in the case discussion thread using their display name/pseudonym.
3. Other users can follow the case and receive in-app notifications for new comments and status changes.
4. Any user can report a comment as abusive or inappropriate.
5. Moderators review reports and may remove the comment or take manual action against the account.

### 3.7 Sharing Flow

Each public case page provides a `Copy Link` action. Users can then share the copied URL through WhatsApp, Facebook, or another channel using their device or browser. Dedicated platform-specific share buttons are out of scope for MVP.

---

## 4. Functional Requirements

### 4.1 Authentication and Profiles

- Users register and sign in with email and password.
- Passwords are stored using a strong one-way hash such as BCrypt.
- Users choose a public display name/pseudonym.
- Email addresses and other account identity data remain private to administrators.
- Users can request a verified badge and select a verification type, such as police, NGO, or subject-matter expert.
- Admins can approve, reject, grant, or revoke verification status.

### 4.2 Case Registry

- Registered users can submit cases using the required structured form.
- A report upload is mandatory before submission.
- Approved cases are publicly viewable; rejected, pending, and admin-only fields are not public.
- Reporters and admins can edit permitted case information.
- Reporters and admins can update case status according to the lifecycle rules.
- Resolved cases remain searchable as historical records.

### 4.3 Search and Discovery

Case listings support:

- Free-text search by name.
- Region filter.
- Age-range filter.
- Gender filter.
- Case-status filter: Missing or resolved status.
- Date-range filter using last-seen date or date reported.

Sort by most recent or most discussed is a future enhancement, not an MVP requirement.

### 4.4 Case Discussions

- Each approved, live case has one public discussion thread.
- Only authenticated users can post.
- Posts display the author's pseudonym and verified badge, if applicable.
- Users can report comments.
- Moderators can remove reported comments.
- Removed comments are not visible to the public but remain available to moderators as an audit record where required.

### 4.5 Following and Notifications

- Registered users can follow or unfollow a case.
- Followers receive in-app notifications for new comments and case status changes.
- The original reporter receives an email for status changes only.
- General followers do not receive email notifications in MVP.
- The notification API should support unread counts and a mark-as-read action.

### 4.6 Administration

Moderators can:

- Approve or reject submitted cases.
- View duplicate and minor priority flags.
- Edit or take down live cases.
- Review reported comments.
- Process verification requests.

Super Admins can additionally:

- Create, deactivate, and manage moderator accounts.
- Manage site-level settings.
- Perform all moderator actions.

---

## 5. Data Model & Privacy Boundaries

### 5.1 Case Fields

| Field | Type | Visibility |
|---|---|---|
| Full name | String | Public |
| Photo(s) | Image | Public |
| Age | Integer | Public |
| Gender | Enum | Public |
| Last-seen date | Date | Public |
| Last-seen location / region | String or geo data | Public |
| Physical description | Text | Public |
| Clothing worn when last seen | Text | Public |
| Circumstances of disappearance | Text | Public |
| Case status | Enum | Public |
| Closing statement | Text, optional | Public |
| Contact number if found | String | Public |
| Medical conditions | Text | Admin-only |
| Known associates | Text | Admin-only |
| Vehicle information | Text | Admin-only |
| Social media handles | Text | Admin-only |
| Reporter's relationship | String | Admin-only |
| Reporter's account contact | String | Admin-only |
| Missing-person report | File | Admin-only |
| Minor priority flag | Boolean | Admin-only |
| Duplicate-match flag | Boolean | Admin-only |

The public `Contact number if found` is intentionally distinct from the reporter's private account contact. It may be a family member's number, a designated hotline, or another number chosen by the reporter for public contact.

### 5.2 Core Entities

```text
User
- id, email, password_hash, display_name
- role, verification_status, verification_type
- created_at, updated_at

Case
- id, reporter_id, status, title/name
- age, gender, last_seen_date, last_seen_location
- physical_description, clothing, circumstances
- public_contact_number, closing_statement
- priority_minor, duplicate_flag
- submitted_at, approved_at, resolved_at, closed_at

CaseSensitiveDetails
- case_id, medical_conditions, known_associates
- vehicle_info, social_handles, reporter_relationship

CaseFile
- id, case_id, storage_key, file_type, visibility
- uploaded_at, deleted_at

CaseReview
- id, case_id, reviewer_id, decision, review_source
- review_notes, created_at

Comment
- id, case_id, author_id, body, moderation_status
- report_count, created_at, removed_at

CaseFollow
- user_id, case_id, created_at

Notification
- id, recipient_id, case_id, type, read_at, created_at

ContentReport
- id, comment_id, reporter_id, reason, status
- moderator_id, resolution_notes, resolved_at
```

The exact database schema is an implementation concern, but the API must enforce the public/admin-only split at the response boundary. Sensitive fields must never be returned by public case endpoints.

---

## 6. Trust, Safety & Moderation

| Concern | MVP approach |
|---|---|
| Case authenticity | Mandatory report upload and human approval before publication |
| Duplicate cases | Fuzzy name plus date matching; admin confirmation |
| Minor safety | Automatic priority flag and queue prioritization |
| Abusive comments | User reports and moderator review |
| Hoax cases | Manual rejection or takedown; no automatic bans |
| Sensitive data exposure | Public/admin-only field split and private file storage |
| User accountability | Pseudonymous public identity linked to a private account |
| Professional credibility | Admin-reviewed verified badge with no extra permissions |

Moderation actions should record who acted, what changed, when it changed, and any moderator notes. This audit trail is especially important for takedowns, status overrides, verification decisions, and sensitive-data deletion.

---

## 7. Data Retention & Legal Compliance

CrowdTrace must be designed to support Ghana's Data Protection Act, 2012 (Act 843). Legal counsel and the Data Protection Commission should validate the final policy before launch.

### 7.1 Retention Rules

- Active cases retain all required data while the case is open.
- Resolved or closed cases retain full data for 12 months after closure.
- After 12 months, the system deletes sensitive admin-only fields and uploaded police reports.
- Public historical fields remain available indefinitely as a public-interest archive.
- Field-level deletion must produce an audit-log entry.

### 7.2 Consent and Launch Compliance

- Case submission requires explicit consent for collection of sensitive personal data.
- The platform operator must register CrowdTrace as a data controller with Ghana's Data Protection Commission before launch.
- Required public policies are: Terms of Service, Privacy Policy, and a content/retention policy.
- A merchant-style liability model does not apply; however, the Terms of Service must explain the platform's role, takedown process, and limitations of user-submitted information.

### 7.3 Scheduled Cleanup

A scheduled job should identify cases beyond the 12-month post-closure window, delete the approved sensitive fields and private files, and write an auditable record of the cleanup.

---

## 8. Notifications & Communications

| Trigger | Recipient | Channel | Content |
|---|---|---|---|
| Case submitted | Reporter | In-app and/or email | Submission received and review expectations |
| Case approved | Reporter | In-app and/or email | Case is live with public URL |
| Case rejected | Reporter | In-app and/or email | Decision and moderator guidance, where appropriate |
| New comment on followed case | Followers | In-app | Case and comment notification |
| Status change on followed case | Followers | In-app | New status and case link |
| Status change | Original reporter | Email | Important case update |
| Verification decision | Applicant | In-app and/or email | Approved, rejected, or revoked status |
| Comment report resolution | Reporting user, where appropriate | In-app | Moderation outcome |

The MVP intentionally avoids email notifications for every comment to prevent notification overload.

---

## 9. Success Metrics

The initial release should instrument the following metrics. Baselines and target values can be finalized after an initial pilot.

| Outcome | Metric |
|---|---|
| Cases can be published reliably | Submission-to-review completion rate; approval rate; median review time |
| The registry is useful | Case search-to-detail click-through; case-page visits; copy-link actions |
| The community contributes | Comments per live case; percentage of live cases with at least one contribution |
| Contributions remain safe | Reported comments per 100 comments; median moderation response time; removal rate |
| Cases stay current | Percentage of cases with a status update; reporter status-update completion |
| Privacy controls work | Public API responses containing zero admin-only fields; retention jobs completed successfully |

Analytics must avoid exposing sensitive case details or reporter identity in event payloads.

---

## 10. Non-Functional Requirements

### Security and Privacy

- Enforce authentication and role-based authorization on every private endpoint.
- Store passwords as strong one-way hashes.
- Store police reports and other sensitive files in private object storage.
- Use signed, time-limited URLs for authorized file access.
- Validate file type, file size, and upload content before storage.
- Apply server-side authorization; do not rely on frontend field hiding.
- Log security-sensitive administrative actions without logging unnecessary personal data.

### Reliability and Performance

- Public case browsing and search should support pagination.
- Long-running file processing, email delivery, and retention cleanup should not block ordinary API requests.
- Duplicate detection should be resilient and should not prevent a reporter from submitting a case.
- Status changes, comment creation, and moderation decisions must be transactionally consistent.

### Accessibility and Localization Readiness

- The web experience must be usable on mobile screens and common low-bandwidth connections.
- Forms, status labels, validation errors, and moderation actions must be keyboard accessible and screen-reader understandable.
- MVP content is English-only, but user-facing copy should be externalized to support future languages such as Twi, Ga, and Ewe.

### Observability

- Record structured application logs for authentication, case lifecycle changes, moderation, notifications, and retention cleanup.
- Expose health checks suitable for the deployment platform.
- Monitor failed email delivery, file-upload failures, and scheduled-job failures.

---

## 11. Technical Specification

### 11.1 Technology Baseline

| Layer | Technology / decision |
|---|---|
| Backend | Java Spring Boot |
| Java | Java 21 |
| API | REST API for a separately developed frontend |
| Database | PostgreSQL |
| Migrations | Flyway |
| File storage | AWS S3; private objects for sensitive reports |
| Authentication | Email/password with session- or JWT-based API authentication |
| Email | Transactional email provider, such as AWS SES |
| Local development | Docker Compose |
| Production | Docker image on a managed PaaS such as Render, Railway, or AWS Elastic Beanstalk |
| API documentation | OpenAPI/Swagger from the start |

The current repository is a Spring Boot 4.1.0 / Java 21 starter with the web MVC dependency and a context-load test. Domain models, persistence, authentication, API endpoints, storage, and integrations described above remain to be implemented.

### 11.2 Platform Access

- **Public users:** responsive web case registry.
- **Registered users:** responsive web account, case submission, comments, follows, and notifications.
- **Moderators and Super Admins:** protected web administration interface.
- **Frontend:** out of scope for this backend repository; frontend development consumes the REST contract.

### 11.3 API Contract Expectations

- Use consistent resource-oriented URLs and HTTP status codes.
- Return separate public and authenticated/admin representations where visibility differs.
- Paginate case, comment, notification, and moderation-queue collections.
- Return validation errors in a stable, documented format.
- Protect state transitions with authorization and server-side lifecycle validation.
- Document authentication, field visibility, error states, and idempotency expectations in OpenAPI.

---

## 12. User Stories and Acceptance Criteria

### US-01 — Submit a Case

**As a** reporter, **I want** to submit a structured missing-person case with supporting evidence, **so that** moderators can review and publish it.

- **Priority:** Must-have
- **Effort:** L — form, file storage, consent, duplicate detection, and review workflow

**Acceptance criteria**

- [ ] Given I am authenticated, when I submit all required fields and a report file, then the case enters review.
- [ ] Given the report file is missing or consent is not accepted, when I submit, then the case is rejected with actionable validation errors.
- [ ] Given a possible duplicate is found, when submission completes, then the case is submitted and flagged for moderator review.
- [ ] Given the person's age is under 18, when submission completes, then the case receives a priority flag.

### US-02 — Review and Publish a Case

**As a** moderator, **I want** to approve or reject submitted cases, **so that** only reviewed cases appear publicly.

- **Priority:** Must-have
- **Effort:** L — queue, file access, decisions, permissions, and notifications

**Acceptance criteria**

- [ ] Given a case is under review, when I approve it, then it becomes publicly visible with only public fields.
- [ ] Given a case is under review, when I reject it, then it remains private and the reporter receives the decision notification.
- [ ] Given duplicate or minor flags exist, when I open the queue, then those flags are visible and filterable or clearly prioritized.
- [ ] Every decision records the reviewer, timestamp, decision, and notes.

### US-03 — Find a Case

**As a** community member, **I want** to search and filter approved cases, **so that** I can find cases where I may have useful information.

- **Priority:** Must-have
- **Effort:** M — indexed fields, filters, pagination, and public endpoint

**Acceptance criteria**

- [ ] Guests can search approved cases by name.
- [ ] Users can filter by region, age range, gender, status, and date range.
- [ ] Pending, rejected, taken-down, and admin-only fields are excluded from public results.
- [ ] Results are paginated and return a stable empty state when no cases match.

### US-04 — Contribute to a Case

**As a** registered user, **I want** to post under a pseudonym in a case thread, **so that** I can share a tip without exposing my account email publicly.

- **Priority:** Must-have
- **Effort:** M — comments, identity display, authorization, and moderation status

**Acceptance criteria**

- [ ] Guests cannot create comments.
- [ ] A published comment shows the user's display name and verified badge, if applicable, but not their email.
- [ ] Comments are associated with exactly one live case and an authenticated author.
- [ ] Removed comments are no longer visible in the public thread.

### US-05 — Follow and Receive Updates

**As a** registered user, **I want** to follow a case, **so that** I can keep up with useful developments.

- **Priority:** Must-have
- **Effort:** M — follow state, event generation, unread state, and notification API

**Acceptance criteria**

- [ ] I can follow and unfollow a case without creating duplicate follow records.
- [ ] A new comment on a followed case creates an in-app notification.
- [ ] A status change creates an in-app notification for followers.
- [ ] The original reporter receives an email when the case status changes.
- [ ] I can list notifications and mark them as read.

### US-06 — Moderate Reported Content

**As a** moderator, **I want** to review reports on comments, **so that** harmful content can be removed while preserving accountability.

- **Priority:** Must-have
- **Effort:** M — reporting, queue, resolution, and audit history

**Acceptance criteria**

- [ ] Authenticated users can report a visible comment with a reason.
- [ ] Reported comments appear in the moderator queue.
- [ ] A moderator can remove a comment and record a resolution note.
- [ ] Removed comments are excluded from public responses and the moderation action is auditable.

### US-07 — Resolve a Case

**As a** reporter or admin, **I want** to update a case's outcome, **so that** the public record remains accurate and respectful.

- **Priority:** Must-have
- **Effort:** M — transition rules, permissions, public presentation, and notifications

**Acceptance criteria**

- [ ] Only the original reporter or an admin can change a live case status.
- [ ] The API rejects invalid lifecycle transitions.
- [ ] A resolved case may include a closing statement.
- [ ] `Found Deceased` displays the respectful notice behavior defined in this spec.
- [ ] The status change is audit logged and notifies followers and the reporter.

### US-08 — Remove Expired Sensitive Data

**As a** platform operator, **I want** sensitive data removed after the retention window, **so that** CrowdTrace follows its privacy obligations.

- **Priority:** Must-have for launch compliance
- **Effort:** M — scheduled job, field-level deletion, storage cleanup, and audit log

**Acceptance criteria**

- [ ] Given a case has been closed for more than 12 months, when the cleanup job runs, then sensitive fields and private report files are deleted.
- [ ] Public historical fields remain available after cleanup.
- [ ] Each deletion records the case, fields/files affected, timestamp, and job execution identity.
- [ ] A failed cleanup is observable and can be retried safely.

---

## 13. Out of Scope and Future Roadmap

### MVP Exclusions

- Native mobile apps.
- Multi-language content.
- Law-enforcement data integration or a privileged investigator role.
- Payments, donations, and crowdfunding.
- Real-time chat, direct messaging, and push notifications.
- Automatic bans and automated authenticity decisions.
- Most-recent and most-discussed sorting.

### Future Phases

- AI-assisted document and duplicate review with human final approval.
- A formal law-enforcement partnership and legally defined access role.
- Multi-language support, including Ghanaian languages.
- Native iOS and Android applications.
- Automated hoax escalation policy and graduated account enforcement.
- Dedicated platform share actions and richer discovery sorting.

---

## 14. Dependencies, Risks & Open Questions

### Dependencies

- Data Protection Commission registration and legal review before launch.
- Transactional email provider and delivery-domain configuration.
- AWS S3 account, private bucket policy, and signed-URL configuration.
- Frontend implementation aligned to the REST/OpenAPI contract.
- Moderator staffing and operating procedures.

### Risks and Mitigations

| Risk | Mitigation |
|---|---|
| False or malicious case submissions | Mandatory evidence upload, human approval, audit trail, and manual takedown |
| Sensitive data exposure | Explicit field-level visibility, private storage, server-side authorization, and deletion jobs |
| Abusive or misleading community content | Account-gated posting, pseudonyms with traceable accounts, user reports, and moderation queue |
| Moderator backlog | Minor priority flags, queue metrics, and documented review procedures |
| Expansion across countries creates inconsistent legal requirements | Keep country-specific policy/configuration boundaries explicit and complete legal review per country |
| Notification overload | In-app notifications for followers; email only for reporter status changes in MVP |

### Open Questions

1. What is the exact manual escalation policy for repeated hoaxes or abusive accounts?
2. Which transactional email provider and sender domain will be used?
3. What file size, file type, and retention limits should apply to case photos and reports?
4. What moderator service-level target should define an acceptable review time, especially for minor cases?
5. Should case-region data use a fixed Ghana administrative-region list or free text at launch?
6. What legal wording and consent records are required after counsel reviews Act 843 obligations?
7. What operational process should be used when a reporter requests correction or removal of a public historical record?

---

## 15. Release Criteria

The MVP is ready for release when:

- [ ] Authentication, roles, and server-side authorization are implemented and tested.
- [ ] A registered user can submit a case with mandatory report upload and consent.
- [ ] Moderators can review, approve, reject, edit, and take down cases.
- [ ] Public case search and filters work with the defined field visibility rules.
- [ ] Registered users can comment, follow cases, receive in-app notifications, and report comments.
- [ ] Moderators can resolve comment reports and all key actions are auditable.
- [ ] Reporters and admins can update case statuses with valid transition enforcement.
- [ ] Status-change emails are delivered to reporters.
- [ ] Sensitive files are private and accessed only through authorized, time-limited URLs.
- [ ] The 12-month retention/deletion workflow is implemented, retry-safe, and observable.
- [ ] OpenAPI documentation describes the production API contract.
- [ ] Security, privacy, accessibility, and failure-path tests pass.
- [ ] Data Protection Commission registration and required legal documents are complete.
- [ ] Moderator operating procedures and support escalation paths are documented.

---

## 16. Glossary

| Term | Definition |
|---|---|
| Case | A record describing a missing-person report and its current lifecycle status |
| Reporter | The registered user who submits a case |
| Live case | An approved case visible to the public |
| Public contact number | A number the reporter designates for information if the person is found |
| Sensitive data | Admin-only information such as medical conditions, known associates, and the uploaded report |
| Verified badge | An admin-confirmed identity signal with no extra permissions |
| Minor flag | An internal priority marker for a person under 18 at the time of disappearance |
| Take down | Remove a previously live case or comment from public visibility |
| Retention window | The 12-month period after case closure before sensitive data is deleted |

---

*This product specification is derived from `docs/crowdtrace-spec.md` and structured for the CrowdTrace backend handoff.*
