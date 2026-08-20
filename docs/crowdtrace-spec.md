# CrowdTrace — Technical Specification

**Version:** 1.0 (MVP)
**Date:** August 19, 2026
**Author:** Edem (Product Owner)
**Status:** Draft for developer handoff

---

## 1. Overview

CrowdTrace is a community-driven missing persons platform, launching in Ghana with a design that allows future expansion across Africa. It combines two functions:

1. **A missing persons registry** — verified case listings for people reported missing, reviewed and approved by admins before going live.
2. **A crowd-investigation community** — a public discussion thread per case (Websleuths-style) where the community can share tips, updates, and leads that help investigators and families.

The platform launches **web-only**; a native mobile app is planned as an explicit Phase 2.

### 1.1 Goals
- Give families a straightforward, verifiable way to publish a missing person case.
- Give the public a structured space to help — not just comment, but genuinely contribute to finding someone.
- Keep sensitive personal data protected and compliant with Ghana's Data Protection Act, 2012 (Act 843).
- Build a foundation that can scale to other African countries later without a rebuild.

### 1.2 Non-Goals (MVP)
- Native mobile apps (Phase 2)
- Multi-language support beyond English (future consideration)
- Official law enforcement data integration or elevated law-enforcement access
- Payment processing / crowdfunding features
- Real-time chat or push notifications

---

## 2. User Roles

| Role | Description | Access |
|---|---|---|
| **Guest (unauthenticated)** | Anyone browsing the site | View approved case listings and public case fields. Cannot comment, submit tips, or submit cases. |
| **Registered User** | Logged-in member (email + password) | Can submit a case, comment/post in case discussion threads, follow cases, receive in-app notifications, report abusive content. |
| **Verified Badge Holder** | Registered user verified by admin as police, NGO, or subject-matter expert (e.g. DNA analyst) | Same access as Registered User, plus a visible "Verified" badge on their profile/posts. **No elevated data access** — badge is a trust signal only (see §4.3). |
| **Moderator** | Admin-appointed | Reviews and approves/rejects submitted cases, reviews reported comments, can take down live cases, cannot manage other admins or site settings. |
| **Super Admin** | Platform owner/operator | Full access: manage moderators, manage site settings, all moderator capabilities. |

**Design note:** There is intentionally no "Investigator" role with special data permissions. This mirrors how Websleuths operates — police and experts can participate and be visibly verified, but the platform does not grant them backend access to private tips or reporter contact info. Keep this in mind if you later formalize a partnership with Ghana Police Service; it would need a new role and legal data-sharing agreement, out of scope for MVP.

---

## 3. Case Lifecycle

```
[Submitted] → [Under Review] → [Approved/Live] → [Resolved: Found Safe / Found Deceased / Closed]
                    ↓
                [Rejected]
```

### 3.1 Submission
- Any registered user can submit a case.
- **A missing person report (uploaded document/image) is mandatory** at submission. This is the primary evidence anchor for admin review.
- Reporter fills out a structured case form (see §5 Data Model).
- Submission triggers a duplicate-check (see §3.4) and, if the missing person is a minor, a **priority flag** (see §3.5).

### 3.2 Admin Review
- Case enters a moderation queue, visible only to Moderators/Super Admins.
- Admin reviews the uploaded report and submitted details.
- Admin either **Approves** (case goes live) or **Rejects** (reporter notified, case does not appear publicly).
- **Future phase:** this review step can be partially automated with AI-assisted document verification, with admin retaining final sign-off. Design the review queue/data model so an "AI review" step can be inserted later without restructuring (e.g. a `review_notes` / `review_source` field capable of holding either human or AI-generated notes).

### 3.3 Status Updates (Post-Approval)
- Status can be changed by: **the original reporter**, or an **admin** — whichever happens first. Admin has final override authority in all cases (e.g. can correct a status if a reporter update is inaccurate).
- Valid statuses: `Missing` → `Found Safe` / `Found Deceased` / `Closed` (case closed for other reasons, e.g. determined to be a hoax, or reporter request).
- **On transition to `Found Deceased`:**
  - The public case page shows a respectful notice banner in place of granular "missing" details.
  - The reporter (or a verified police account, if involved) may optionally add a **closing statement** — a free-text note explaining the outcome, thanking contributors, etc. This is shown publicly if provided.
  - Sensitive admin-only fields are queued for deletion per the retention policy (§8).
- **On transition to `Found Safe` or `Closed`:** similar closing statement option is available; same retention policy applies.

### 3.4 Duplicate Detection
- On submission, the system auto-checks for possible duplicates by matching **name (fuzzy match) + date reported/last seen**.
- If a possible match is found, the new submission is flagged in the review queue for the admin to confirm as duplicate or distinct before approval. This does not block submission — it surfaces a warning to the reviewing admin.

### 3.5 Minor Cases (Priority Handling)
- If the missing person's age (at time of disappearance) is under 18, the case is **auto-flagged for priority review** in the moderation queue (e.g. sorted to the top, visually flagged) — given elevated trafficking risk in the Ghanaian context. No additional field restrictions beyond the standard public/admin-only split (§5.2) are applied at this stage — this can be revisited later.

### 3.6 Fake / Hoax Cases
- If discovered to be fake **before approval**: admin rejects it; it never goes live.
- If discovered to be fake **after going live**: admin takes it down.
- **No automatic account bans or system-level penalties** for MVP — this remains a manual, case-by-case admin judgment call. (Open item: you may want to define an escalation policy — e.g. repeated hoaxes — post-launch.)

---

## 4. Community Features (The "CrowdTrace" Layer)

### 4.1 Case Discussion Thread
- Each live case has an **open, public comment thread** (forum-style) — this is the primary mechanism for tips and updates, not a separate private form.
- Posting requires a registered account (no anonymous posting).
- Users post under a **pseudonym** (display name); real identity (email) is known only to admins. This mirrors the Websleuths model — it lowers the barrier for people with sensitive information to speak up, while admins retain the ability to trace/moderate bad actors.

### 4.2 Following & Notifications
- Registered users can **"Follow"** a case.
- **In-app notifications** (Twitter/X-style notification bell + dedicated notifications page) fire for:
  - New comments on a followed case
  - Status changes on a followed case
- **The original reporter additionally receives an email** for major events only (i.e. status changes) — not for every new comment. This ensures they don't miss a critical update even if they're not logged in regularly, without over-notifying them.
- No email notifications for general followers in MVP (in-app only).

### 4.3 Verified Badges
- Users can apply to be verified (e.g. police officer, NGO staff, subject-matter expert).
- Admin manually reviews and confirms identity privately, then grants a visible "Verified" badge on that user's public profile and posts.
- **Verified status grants no additional data access or platform permissions** — it is purely a credibility signal to other users reading the thread.

### 4.4 Comment Moderation
- Any user can **report** a comment as abusive/inappropriate.
- Reported comments enter a moderation queue visible to Moderators/Super Admins.
- Moderators can remove comments and, if warranted, take further action against the user (manual, no automated bans in MVP).

### 4.5 Sharing
- Each case page has a **"Copy Link"** button for sharing (e.g. via WhatsApp, Facebook — user shares the link manually through their own device's share options). No dedicated per-platform share buttons in MVP.

---

## 5. Data Model

### 5.1 Case Fields

| Field | Type | Public / Admin-only |
|---|---|---|
| Full name | string | Public |
| Photo(s) | image | Public |
| Age | integer | Public |
| Gender | enum | Public |
| Last seen date | date | Public |
| Last seen location | string/geo | Public |
| Physical description | text | Public |
| Clothing worn (last seen) | text | Public |
| Circumstances of disappearance | text | Public |
| Case status | enum (`Missing`, `Found Safe`, `Found Deceased`, `Closed`) | Public |
| Closing statement (optional, added on resolution) | text | Public |
| **Contact number(s) if found** | string | **Public** — this is the number the public should call, distinct from the reporter's private account contact |
| Medical conditions | text | Admin-only |
| Known associates | text | Admin-only |
| Vehicle info | text | Admin-only |
| Social media handles | text | Admin-only |
| Reporter's relationship to missing person | string | Admin-only |
| Reporter's personal contact info (from account) | string | Admin-only |
| Uploaded missing person report (police report file) | file | Admin-only |
| Minor flag (auto-computed from age) | boolean | Admin-only (drives review queue sorting) |
| Duplicate-match flag | boolean | Admin-only |

**Note on "Contact if found" vs. "Reporter contact":** these are deliberately separate fields. The "if found" number is what the public sees and calls — it might be a family member, the reporter, or a hotline number the reporter designates. The reporter's own account contact info stays private, protecting them from unwanted public contact while still letting them designate whatever number *they* want the public to use.

### 5.2 Public vs. Admin-Only Field Split — Rationale
Following your correction mid-spec: medical conditions, known associates, vehicle info, social handles, and reporter's personal contact details carry real safety/privacy risk if public (e.g. revealing a vulnerable person's medical needs, or exposing a grieving family member to harassment). These are visible to admins only, for internal verification and coordination — the public instead engages via the "if found" number or the comment thread.

### 5.3 User Account Fields
- Email (login credential, private)
- Password (hashed)
- Display name / pseudonym (public)
- Verified badge status + type (public, if granted)
- Role (Registered User / Moderator / Super Admin)
- Followed cases (list)

### 5.4 Comment / Tip Fields
- Case ID (FK)
- Author (user ID)
- Body text
- Timestamp
- Reported flag (boolean) + report count
- Moderation status (visible / removed)

---

## 6. Search & Discovery

Case listing supports:
- **Filters:** region, age range, gender, status (Missing / Found)
- **Date range:** filter by last-seen date or date reported
- **Free-text search:** by name

*(Sort options like "most recent" / "most discussed" were considered but scoped out of MVP filters — flag as a cheap future add if useful.)*

---

## 7. Moderation & Trust/Safety Summary

| Concern | MVP Approach |
|---|---|
| Case authenticity | Mandatory report upload + mandatory admin review before going live |
| Duplicate cases | Auto-flag by name+date match, admin confirms |
| Minor safety | Auto-flag for priority review queue |
| Abusive comments | User-reported, admin reviews queue (no auto-filter in MVP) |
| Hoax cases | Manual admin takedown/rejection; no auto-bans |
| Sensitive data exposure | Public/admin-only field split (§5) |
| User accountability | Pseudonymous but account-gated (admin can trace real identity via email) |

---

## 8. Data Retention & Legal Compliance (Ghana Data Protection Act, 2012 — Act 843)

- **Active/open cases:** all data (including report file, medical info) retained in full — necessary for the case's ongoing purpose.
- **Resolved/closed cases:** retained in full for **12 months** after closure (to allow for reopening if needed), after which:
  - Sensitive admin-only fields are auto-deleted: uploaded police report file, medical conditions, known associates, vehicle info, social handles, reporter's personal contact info.
  - The public-facing historical record is retained indefinitely (name, photo, public description fields, resolution outcome, closing statement) as a public-interest archive — this is not classified as sensitive data requiring erasure.
- **Consent:** at submission, require an explicit consent checkbox acknowledging collection of sensitive data (medical conditions) per Act 843's requirements for sensitive personal data.
- **Registration:** before launch, register CrowdTrace as a **data controller with Ghana's Data Protection Commission** — this is a legal prerequisite for processing personal data in Ghana, not optional.
- **Open item:** consider implementing a scheduled job (e.g. quarterly cron) that identifies cases past the 12-month post-closure window and executes the field-level deletion automatically, with an audit log entry.

---

## 9. Technical Architecture

### 9.1 Backend
- **Framework:** Spring Boot
- **Database:** PostgreSQL
- **Migrations:** Flyway (consistent with your existing SoftMelon conventions)
- **API:** REST API — frontend is being built separately by another developer, so the backend must expose a clean, documented REST contract (consider OpenAPI/Swagger generation from the start).
- **File storage:** AWS S3 — for photos and uploaded missing person report documents. Admin-only files (police reports) should use private S3 buckets/objects with signed URL access, not public buckets.
- **Auth:** Email + password. Standard hashed password storage (BCrypt or similar), session/JWT-based auth for the API.

### 9.2 Frontend
- **Out of scope for this spec** — being handled by a separate frontend developer against the REST API. Backend team should prioritize a stable, well-documented API contract.

### 9.3 Deployment
- **Local development:** Docker Compose (matches your existing OrbStack-based workflow).
- **Production:** Docker container deployed to a **managed PaaS** (e.g. Render, Railway, or AWS Elastic Beanstalk) — reduces ops overhead vs. self-managing a VM, while keeping the same Docker image used in local dev for consistency.
- **File storage:** AWS S3 (separate from compute — works with any of the above PaaS options).

### 9.4 Notifications
- **In-app:** notification records generated on relevant events (new comment on followed case, status change), displayed via a notifications endpoint/page.
- **Email:** triggered only for reporter + status-change events. Use a transactional email service (e.g. AWS SES, given S3 is already AWS) — not specified by user, flag as implementation choice.

---

## 10. Platform & Language Scope

- **Platform:** Responsive web app only for MVP. **Native mobile app is an explicit Phase 2 deliverable**, not a "maybe."
- **Language:** English only for MVP. Structure copy/content with future localization in mind (e.g. externalized strings) even if not implemented now, since multi-country expansion is a stated long-term goal.

---

## 11. Admin Panel

- **Two roles:** Super Admin (manages moderators, site settings, full access) and Moderator (case review, comment moderation only — cannot manage other admins or site config).
- **Core admin capabilities needed:**
  - Case review queue (approve/reject, with duplicate/minor flags visible)
  - Live case management (edit, take down)
  - Reported comment queue
  - User verification requests (grant/revoke verified badges)
  - (Super Admin only) Moderator account management, site settings

---

## 12. Open Items for Future Decision

These were raised during scoping but intentionally left open — flagging so they don't get lost:

1. **Hoax escalation policy** — no auto-bans defined for MVP; you may want a manual escalation policy (e.g. repeated offenses) post-launch.
2. **AI-assisted case review** — explicitly planned as a future enhancement to the admin approval step; data model should accommodate it (see §3.2).
3. **Multi-language support** — not in MVP, but likely needed for wider Ghana adoption (Twi, Ga, Ewe) and essential for pan-African expansion.
4. **Native mobile app** — Phase 2, no timeline set yet.
5. **Law enforcement partnership** — if formalized later, will require a new role with defined elevated permissions and a data-sharing/legal agreement; current architecture deliberately avoids baking this in prematurely.
6. **Scheduled data-deletion job** — for enforcing the 12-month post-closure retention policy; needs to be built but implementation details (cron schedule, audit logging) not yet specified.
7. **Sort options** (most recent/most discussed) for case listings — nice-to-have, not in MVP filter set.

---

## 13. MVP Feature Checklist

- [ ] User registration/login (email + password)
- [ ] Case submission form + mandatory report upload
- [ ] Admin review queue (approve/reject)
- [ ] Duplicate detection (auto-flag on submission)
- [ ] Minor auto-flag (priority queue sorting)
- [ ] Public case listing with filters (region, age, gender, status, date range) + name search
- [ ] Case detail page (public/admin-only field split)
- [ ] Case status update flow (reporter or admin, admin override)
- [ ] Closing statement on resolution
- [ ] Public comment/discussion thread per case (pseudonymous posting)
- [ ] Comment reporting + moderation queue
- [ ] Follow case + in-app notifications (bell + notifications page)
- [ ] Email notification to reporter on status change only
- [ ] Verified badge request/grant flow
- [ ] Copy-link sharing
- [ ] Admin panel (Super Admin + Moderator roles)
- [ ] S3 integration for photos + report files (private access for sensitive files)
- [ ] 12-month post-closure retention/deletion logic
- [ ] Data Protection Commission registration (legal/business task, not engineering — but a launch blocker)

---

*End of specification.*
