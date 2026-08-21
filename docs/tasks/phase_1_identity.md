# Phase 1 — Identity, Authentication, and Roles

**Goal:** Provide secure accounts, private identity data, pseudonymous public profiles, and
authorization boundaries for every later workflow.

**Five Questions:**

- **Q1:** A user can authenticate and contribute under a pseudonym while administrators retain accountability.
- **Q2:** Passwords, emails, credentials, and private verification evidence never appear in public responses; role checks cannot be bypassed.
- **Q3:** Authentication services, security configuration, endpoint policy, and identity repositories own these rules.
- **Q4:** Registration/login/current-user flows and a complete role matrix pass integration tests.
- **Q5:** Do not return JPA entities directly; do not grant verified users moderator permissions.

## Tasks

### CT-006 — Create users and verification-request persistence

- **Labels:** `epic:identity`, `type:infra`, `priority:p0`
- **Depends on:** CT-002
- **Issue:** Add the users and verification-request migrations, UUID identifiers, private email and
  password hash fields, public display name, role, account status, verification status/type, and
  indexes for email, role, and pending verification work.
- **Acceptance criteria:** Email uniqueness is enforced; lifecycle enums are explicit; password
  hashes cannot serialize; verification evidence is private; repository queue queries are defined.
- **Tests:** Migration, unique-email, enum, and pending-queue repository tests.

### CT-007 — Implement password authentication and credential lifecycle

- **Labels:** `epic:identity`, `type:security`, `priority:p0`
- **Depends on:** CT-006
- **Issue:** Implement password hashing with BCrypt or an equivalent approved algorithm, account
  registration, credential verification, failed-auth handling, and the selected session or JWT
  credential lifecycle. Record the decision if the token model changes.
- **Acceptance criteria:** Plain passwords are never persisted or logged; invalid credentials do
  not reveal whether an email exists; inactive accounts cannot authenticate; credentials expire or
  can be revoked according to the selected model.
- **Tests:** Hashing, registration, invalid-login, inactive-account, expiry/revocation tests.

### CT-008 — Enforce role-based endpoint authorization

- **Labels:** `epic:identity`, `type:security`, `priority:p0`
- **Depends on:** CT-007
- **Issue:** Define endpoint policies for guests, registered users, moderators, and Super Admins.
  Keep verified badge status as presentation metadata, not an authorization role.
- **Acceptance criteria:** Public discovery is unauthenticated; case submission/comments/follows
  require users; review/moderation requires moderators; moderator management requires Super Admins.
- **Tests:** Parameterized authorization matrix covering allow and deny cases for every role.

### CT-009 — Implement authentication and current-user endpoints

- **Labels:** `epic:identity`, `type:feature`, `priority:p0`
- **Depends on:** CT-007, CT-008
- **Issue:** Implement `POST /api/auth/register`, `POST /api/auth/login`, the selected refresh or
  logout endpoint, `GET /api/auth/me`, and safe authenticated-user profile responses.
- **Acceptance criteria:** Successful login resolves the current user; public responses expose only
  the pseudonym and permitted badge metadata; logout/revocation invalidates credentials when applicable.
- **Tests:** MockMvc integration tests for registration, login, me, logout/refresh, and response fields.

### CT-010 — Implement verified-badge request and admin decision workflow

- **Labels:** `epic:identity`, `type:feature`, `type:security`, `priority:p1`
- **Depends on:** CT-006, CT-008, CT-009
- **Issue:** Add user request and status endpoints plus moderator/Super Admin approval, rejection,
  grant, and revocation actions. Keep evidence private and every decision auditable.
- **Acceptance criteria:** Users can submit and view only their own request; authorized admins can
  decide; rejected/revoked badges are not displayed; badge status never unlocks private fields.
- **Tests:** Ownership, role, state-transition, response-projection, and audit tests.

## Verification

- Registration → login → current user → logout/credential invalidation
- All role boundaries tested through the API
- No private identity or verification evidence in public DTOs or logs
