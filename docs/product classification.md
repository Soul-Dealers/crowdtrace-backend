# CrowdTrace - Product Classification

> A structured classification of CrowdTrace as a civic-safety, community contribution, and moderated public-information platform.

---

## 1. Classification Summary

| Dimension | Classification |
|---|---|
| Primary product type | Civic-safety and missing-persons information platform |
| Secondary product type | Moderated community investigation and user-generated content platform |
| Delivery model | Responsive web application backed by a REST API |
| Launch market | Ghana |
| Expansion model | Country-aware platform that can expand across Africa |
| Primary users | Reporters, families, community contributors, moderators, and Super Admins |
| Content model | Structured case records plus pseudonymous case discussions |
| Trust model | Human approval, account-gated contribution, moderation, and auditability |
| Monetization | No payment or revenue model defined for MVP |
| Data sensitivity | High; includes sensitive personal and case information |
| Compliance profile | Ghana Data Protection Act, 2012 (Act 843), plus platform safety policies |
| Technical complexity | Medium-high due to privacy, moderation, lifecycle, storage, and retention requirements |
| MVP platform scope | Web-only; native mobile applications are Phase 2 |

### Executive Classification

CrowdTrace is not simply a public directory and not an unrestricted social network. It is a **moderated civic-safety platform** with a public discovery layer, a private case-management layer, and a controlled community-contribution layer.

The product's defining characteristic is the boundary between information that helps the public identify or locate a missing person and information that must remain available only to authorized administrators for verification and safeguarding.

---

## 2. Product Category and Taxonomy

### 2.1 Primary Category

**Civic technology / public-interest safety platform**

CrowdTrace supports a public-interest outcome: helping communities share reliable information about missing persons while reducing the risks created by unmoderated disclosure of sensitive data.

### 2.2 Secondary Categories

| Category | Why CrowdTrace belongs here |
|---|---|
| Missing-persons registry | The platform stores and publishes structured cases with lifecycle statuses |
| Community investigation | Registered users contribute observations, tips, and updates in case threads |
| Moderated user-generated content | Comments and case submissions require review, reporting, or moderation controls |
| Case-management system | Reporters and administrators manage structured records and status changes |
| Public information service | Guests can search approved cases without an account |
| Privacy-sensitive application | The platform handles medical information, identity data, reports, and contact details |

### 2.3 Explicit Non-Categories

CrowdTrace is not classified as:

- A law-enforcement records system.
- A private investigator marketplace.
- A real-time emergency dispatch service.
- A social network with unrestricted public posting.
- A donation, payment, or crowdfunding platform.
- A legal determination or official source of truth for disappearance cases.

---

## 3. Market, Geography & Expansion Classification

| Dimension | MVP classification | Future direction |
|---|---|---|
| Geography | Ghana | Additional African countries |
| Language | English | Twi, Ga, Ewe, and other localized content |
| Legal regime | Ghana Data Protection Act, 2012 (Act 843) | Country-specific privacy and safety reviews |
| Platform | Responsive web | Native mobile applications in Phase 2 |
| Institutional relationship | No official law-enforcement integration | Possible formal partnership with new legal permissions |
| Data architecture | Country-ready fields and policies | Country-specific configuration and retention rules |

Expansion should be treated as a policy and data-governance problem as well as a technical problem. A country added later may require different consent language, retention periods, region taxonomies, emergency contacts, and moderation procedures.

---

## 4. Target Users and Access Classification

| User class | Authentication | Public identity | Private data access | Primary capabilities |
|---|---|---|---|---|
| Guest | None | None | None | Browse and search approved public cases |
| Registered User | Email and password | Pseudonym | Own account data | Submit cases, comment, follow, notify, report content |
| Verified Badge Holder | Email and password plus admin verification | Pseudonym plus badge | No additional access | Same as Registered User with credibility signal |
| Moderator | Protected admin authentication | Admin identity | Review and moderation data | Review cases, moderate comments, process verification |
| Super Admin | Protected admin authentication | Admin identity | Full platform data | Manage moderators, settings, and all moderator functions |

### Role Classification Decision

The verified badge is classified as **reputational metadata**, not an authorization role. It changes how a contribution is presented but does not unlock private case fields, police reports, reporter contacts, or other sensitive data.

---

## 5. Problem and Value Classification

### 5.1 Core Problem

Missing-person information is often fragmented across social posts, messaging groups, and informal networks. Families need a durable and credible public case page, while community members need a focused channel for contributing useful information.

### 5.2 Value Proposition

| Audience | Value provided |
|---|---|
| Families and reporters | A structured submission process, public reach, case updates, and community response |
| Community contributors | Searchable cases, clear public contact information, and a focused discussion thread |
| Moderators | Review queues, duplicate/minor flags, moderation tools, and audit history |
| Platform operators | A scalable, privacy-aware foundation for public-interest case coordination |
| Future partners | A documented case and contribution model that could support approved collaboration later |

### 5.3 Primary Outcome

The primary product outcome is not account growth or time spent. It is **higher-quality, safer circulation of actionable missing-person information**.

---

## 6. Capability Classification

### 6.1 Core MVP Capabilities

| Capability | Classification | Rationale |
|---|---|---|
| User registration and login | Core | Required for accountable submissions and contributions |
| Structured case submission | Core | Main reporter workflow |
| Mandatory report upload | Core | Evidence anchor for moderator review |
| Explicit sensitive-data consent | Core | Privacy and legal requirement |
| Human case approval | Core | Prevents unreviewed cases from becoming public |
| Public case search and filters | Core | Main guest discovery experience |
| Public/admin-only field separation | Core | Primary safety boundary |
| Case status lifecycle | Core | Keeps public records current and respectful |
| Pseudonymous comments | Core | Main community contribution mechanism |
| Comment reports and moderation | Core | User-generated content safety |
| Case follows and in-app notifications | Core | Enables continued community engagement |
| Reporter status-change email | Core | Ensures important updates reach the reporter |
| Verified badge workflow | Core | Provides a trust signal without privileged access |
| Private sensitive-file storage | Core | Protects police reports and admin-only material |
| Retention and sensitive-data deletion | Core for launch | Required by the defined privacy policy |

### 6.2 Enabling Capabilities

| Capability | Classification | Supports |
|---|---|---|
| REST/OpenAPI contract | Enabling | Separate frontend implementation |
| Audit events | Enabling | Moderation, legal review, incident response, and accountability |
| Duplicate detection | Enabling | Review quality and reduced duplicate publication |
| Minor priority flag | Enabling | Safer moderation prioritization |
| File metadata validation | Enabling | Secure storage and operational reliability |
| Analytics | Enabling | Review performance and product learning |
| Scheduled jobs | Enabling | Retention cleanup and operational automation |

### 6.3 Future Capabilities

- AI-assisted document or duplicate review with human final approval.
- Formal law-enforcement partnership and a legally defined elevated-access role.
- Multi-language support.
- Native mobile applications.
- Automated hoax escalation and graduated account enforcement.
- Richer discovery sorting and dedicated platform sharing actions.

---

## 7. Workflow Classification

| Workflow | Type | Initiator | Review / control point | Outcome |
|---|---|---|---|---|
| Submit case | High-sensitivity intake | Registered user | Mandatory report, consent, moderator review | Case enters review queue |
| Approve case | Governance workflow | Moderator | Evidence and duplicate/minor flags | Case becomes public |
| Reject case | Governance workflow | Moderator | Review notes and notification | Case remains private |
| Update status | Public-record workflow | Reporter or admin | Lifecycle validation and audit | Public case record changes |
| Comment | Community contribution | Registered user | Account gate and later reporting | Pseudonymous case contribution |
| Report comment | Safety workflow | Registered user | Moderator queue | Content reviewed and resolved |
| Verify user | Trust workflow | User and admin | Private identity review | Badge granted, rejected, or revoked |
| Retention cleanup | Compliance workflow | Scheduled job | Eligibility check and audit | Sensitive data deleted |

### Workflow Risk Classification

The highest-risk workflows are:

1. Case submission, because it collects sensitive personal data.
2. Case approval, because it determines what becomes public.
3. Status updates, particularly `Found Deceased`.
4. Private file access and retention deletion.
5. Comment moderation and user accountability.

These workflows require stronger audit, authorization, and failure handling than ordinary read-only browsing.

---

## 8. Data Classification

| Data class | Examples | Visibility | Protection level |
|---|---|---|---|
| Public case data | Name, photo, age, last-seen details, description, public status | Guests and users | Validate, moderate, and prevent unauthorized edits |
| Public contact data | Contact number if found | Guests and users | Explicit reporter designation and abuse-aware display |
| Sensitive case data | Medical conditions, known associates, vehicle information, social handles | Moderators and Super Admins only | Strict RBAC, private responses, audit access |
| Sensitive files | Uploaded missing-person report | Authorized admins only | Private S3 objects, signed URLs, retention deletion |
| Reporter identity | Email, account contact, relationship | Reporter and authorized admins | Private, never included in public DTOs |
| Community content | Comments, reports, moderation state | Public or admins depending on state | Account attribution, reporting, moderation, audit |
| Trust metadata | Verification type, badge status | Badge public; evidence private | Admin approval and revocation workflow |
| Operational data | Notifications, audit events, job metrics | User/admin/operator as appropriate | Minimize personal data and restrict access |

### Data Boundary Rule

Every API response must be classified as one of:

- **Public:** safe for unauthenticated case discovery.
- **Authenticated user:** includes the user's own account and permitted interactions.
- **Reporter:** includes permitted information about cases submitted by that reporter.
- **Moderator:** includes sensitive case and moderation data needed for review.
- **Super Admin:** includes platform-management data and all moderator capabilities.

No frontend visibility rule may replace backend authorization or response projection.

---

## 9. Trust, Safety & Compliance Classification

### 9.1 Trust Model

CrowdTrace uses a **moderated-accountable** trust model:

- Public browsing is open.
- Case submission and commenting require an account.
- Public posting uses a pseudonym rather than an email address.
- Case publication requires human approval.
- Comments can be reported and removed.
- Admin actions are auditable.
- Verified badges communicate credibility without granting private access.

### 9.2 Safety Controls

| Control | Classification | Required for MVP |
|---|---|---|
| Mandatory evidence upload | Authenticity control | Yes |
| Human approval before publication | Governance control | Yes |
| Public/admin-only field split | Privacy control | Yes |
| Minor priority flag | Safeguarding control | Yes |
| Duplicate warning | Quality control | Yes |
| Account-gated comments | Accountability control | Yes |
| User comment reporting | Content-safety control | Yes |
| Manual takedown | Incident-response control | Yes |
| Automated account bans | Enforcement control | No; future/manual policy |

### 9.3 Compliance Classification

| Area | Classification |
|---|---|
| Data protection | High priority; design for Ghana Act 843 obligations |
| Sensitive data consent | Explicit consent required at submission |
| Data controller registration | Launch prerequisite with Ghana Data Protection Commission |
| Retention | Full data for 12 months after closure, then sensitive-field deletion |
| Public archive | Public historical fields may remain after sensitive data deletion |
| Legal review | Required before launch; this document is not legal advice |

---

## 10. Technical Complexity Classification

| Area | Complexity | Reason |
|---|---|---|
| Public browsing and filters | Medium | Search, pagination, public projections, and indexing |
| Authentication and RBAC | Medium | Multiple roles, private identity, and admin boundaries |
| Case submission | High | Sensitive data, mandatory files, consent, and duplicate/minor detection |
| Moderation | High | Queues, decisions, takedowns, reports, and audit history |
| Community discussions | Medium | Pseudonyms, case association, reports, and pagination |
| Notifications | Medium | Event generation, unread state, and email delivery |
| File storage | High | Private objects, signed access, validation, and deletion |
| Retention cleanup | High | Field-level deletion, idempotency, auditability, and legal sensitivity |
| Analytics | Medium | Aggregate queries without exposing sensitive details |
| Future country expansion | High | Country-specific policy, data, language, and legal differences |

### Overall Technical Classification

**Medium-high complexity MVP.** The core CRUD and search flows are straightforward, but privacy, moderation, auditability, file access, and retention make the platform materially more complex than a standard directory application.

---

## 11. Business and Operating Model Classification

| Dimension | Classification |
|---|---|
| Revenue model | Not defined for MVP; no payments or crowdfunding |
| Primary value exchange | Families and communities exchange information under platform safeguards |
| Content ownership | User-submitted content governed by platform terms and moderation policy |
| Operational dependency | Requires trained moderators and documented escalation procedures |
| Institutional dependency | Data Protection Commission registration and legal review |
| Support model | Admin-assisted case correction, takedown, moderation, and privacy requests |
| Growth model | Public case discovery and user sharing; no dedicated platform share buttons in MVP |
| Success orientation | Quality, safety, actionable contribution, and case currency rather than engagement alone |

### Operating Risk

CrowdTrace's reliability depends on human operations as much as software. A technically correct system can still fail if moderation queues are unattended, privacy requests are not handled, or public status updates are inaccurate. Moderator staffing and runbooks are therefore launch dependencies, not optional administrative extras.

---

## 12. MVP Boundary Classification

### In Scope

- Email/password registration and authentication.
- Pseudonymous user profiles.
- Case submission with mandatory report upload and consent.
- Human case review and approval/rejection.
- Duplicate and minor priority flags.
- Public case search and filters.
- Public/admin-only field separation.
- Reporter/admin status updates and closing statements.
- Pseudonymous case comments.
- Comment reporting and moderation.
- Case follows and in-app notifications.
- Reporter email on status change.
- Verified badge request and approval.
- Private sensitive-file storage.
- 12-month post-closure sensitive-data deletion.
- Moderator and Super Admin administration.

### Out of Scope

- Native mobile applications.
- Languages other than English.
- Official law-enforcement data integration.
- Privileged investigator access.
- Payments, donations, and crowdfunding.
- Real-time chat, direct messaging, and push notifications.
- Automated bans or fully automated case authenticity decisions.
- Dedicated social-platform share integrations.

### Future Classification

| Feature | Phase | Classification |
|---|---|---|
| AI-assisted review | Future | Decision-support capability with human approval |
| Law-enforcement partnership | Future | New governed data-sharing product surface |
| Multi-language support | Future | Localization and country-expansion capability |
| Native mobile apps | Phase 2 | New client platforms |
| Hoax escalation | Future | Trust-and-safety enforcement capability |

---

## 13. Product Lifecycle Classification

### Case Lifecycle

```text
Review state:
SUBMITTED → UNDER_REVIEW → APPROVED
                   └──────→ REJECTED

Public case status after approval:
MISSING → FOUND_SAFE / FOUND_DECEASED / CLOSED

Visibility override:
APPROVED → TAKEN_DOWN
```

### Platform Lifecycle

```text
Foundation → Controlled Pilot → MVP Launch → Ghana Expansion Readiness → Pan-African Expansion
```

The controlled-pilot stage should validate moderation volume, case quality, privacy workflows, and notification reliability before broad public launch.

---

## 14. Current Repository Classification

| Area | Current state |
|---|---|
| Framework | Spring Boot 4.1.0 |
| Language | Java 21 |
| Application code | Spring Boot application entry point only |
| Persistence | Not yet implemented |
| Authentication | Not yet implemented |
| REST domain API | Not yet implemented |
| File storage | Not yet implemented |
| Notifications | Not yet implemented |
| Moderation | Not yet implemented |
| Retention jobs | Not yet implemented |
| Tests | Starter context-load test only |
| Product source | `docs/crowdtrace-spec.md` and `docs/product-spec.md` |

This classification distinguishes product requirements from implementation status. The presence of a requirement in this document does not imply that the current codebase already supports it.

---

## 15. Classification Decisions and Assumptions

1. CrowdTrace is classified as a civic-safety platform, not a law-enforcement system.
2. Verified status is a public trust signal and does not grant elevated permissions.
3. The public registry is open to guests, while contributions and submissions require accounts.
4. Public case data and sensitive case data are separate product surfaces and API representations.
5. Human review remains the final authority for case publication in MVP.
6. No payment or revenue model is assumed because none is defined in the CrowdTrace specification.
7. The backend is the source of truth for authorization, lifecycle transitions, data visibility, and retention deletion.
8. Legal interpretations must be validated by qualified counsel and the Ghana Data Protection Commission before launch.

---

## 16. Open Classification Questions

1. What precise service level should apply to review of ordinary versus minor cases?
2. Which Ghana regions and location taxonomy should be standardized in the first release?
3. What correction, objection, or deletion process should be offered to reporters and affected people?
4. What evidence types are acceptable beyond a police report, and which are mandatory by case category?
5. Which notification and email provider will satisfy delivery, privacy, and local operational needs?
6. What escalation policy should apply to repeated hoaxes, harassment, or coordinated misinformation?
7. Which country-specific configuration boundaries must be present before the first expansion outside Ghana?

---

*Classification derived from the CrowdTrace Product Specification and Implementation Blueprint.*
