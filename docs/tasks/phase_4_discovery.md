# Phase 4 — Public Discovery and Case APIs

**Goal:** Provide a stable, searchable public registry that returns only approved public data and
delivers media through the correct access boundary.

**Five Questions:**

- **Q1:** A guest can find and safely view approved cases and share a canonical case URL.
- **Q2:** Pending/rejected/taken-down cases and admin-only fields never appear publicly; public APIs remain paginated and bounded.
- **Q3:** Public query services, projection mappers, and media authorization own these rules.
- **Q4:** API contract, field-exclusion, authorization, and query-performance tests prove them.
- **Q5:** Do not expose raw S3 keys, private report URLs, or frontend-only filtering as a security control.

## Tasks

### CT-021 — Implement public case listing, filters, pagination, and indexes

- **Labels:** `epic:discovery`, `type:feature`, `priority:p0`
- **Depends on:** CT-018
- **Issue:** Implement `GET /api/public/cases` with name search, region, age range, gender, status,
  last-seen/reported date filters, stable ordering, and pagination. Query only live/resolved public cases.
- **Acceptance criteria:** Guests can search; all MVP filters validate; excluded statuses and fields never
  return; empty results are stable; indexes support the documented filters.
- **Tests:** Filter combination, boundary, empty-state, projection, and query-plan tests.

### CT-022 — Implement public case details and canonical sharing data

- **Labels:** `epic:discovery`, `type:feature`, `priority:p0`
- **Depends on:** CT-021
- **Issue:** Implement `GET /api/public/cases/{id-or-slug}` with public identity/details/status,
  safe contact number, resolution metadata, comment/follow summary, and canonical Copy Link URL.
- **Acceptance criteria:** Only approved/live or resolved cases are accessible; takedown returns the
  documented non-public response; private identities and admin fields are absent; canonical URL is stable.
- **Tests:** Visibility, projection, status-banner, canonical-link, and not-found/taken-down tests.

### CT-023 — Implement public photo delivery and private-report access boundary

- **Labels:** `epic:discovery`, `type:security`, `type:infra`, `priority:p0`
- **Depends on:** CT-015, CT-022, CT-030
- **Issue:** Define photo delivery and authorized private-report download behavior. Keep raw S3 keys
  internal, generate time-limited URLs, enforce visibility and actor permissions, and validate media metadata.
- **Acceptance criteria:** Public photos are available only for approved cases; reports are restricted
  to authorized reviewers/administrators; URLs expire; object paths are generated and never user-controlled.
- **Tests:** Public/private access matrix, URL expiry, key-generation, and unauthorized-download tests.

### CT-024 — Add public API safety and performance verification

- **Labels:** `epic:discovery`, `type:test`, `type:security`, `priority:p0`
- **Depends on:** CT-021, CT-022, CT-023
- **Issue:** Add contract and regression tests for all public endpoints, including response-field
  allowlists, pagination bounds, status exclusions, authorization, and search performance targets.
- **Acceptance criteria:** Public responses contain zero admin-only fields; pagination cannot be abused
  for unbounded reads; representative search meets the agreed pilot target; OpenAPI matches behavior.
- **Tests:** MockMvc/integration suite plus indexed query/load verification.

## Verification

- Guest search → filter → details → canonical link
- Public photo works; private report is denied to guests and allowed only to authorized admins
- No raw keys or admin-only fields in public JSON
