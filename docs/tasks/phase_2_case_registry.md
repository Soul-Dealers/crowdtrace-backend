# Phase 2 — Case Registry and Submission

**Goal:** Allow authenticated reporters to submit structured cases with mandatory evidence and
consent while preserving a strict public/admin data boundary.

**Five Questions:**

- **Q1:** A reporter can submit a credible case that enters review with the right privacy records and flags.
- **Q2:** Missing evidence or consent blocks submission; sensitive fields never cross the public projection boundary; duplicate detection never blocks intake.
- **Q3:** Case application services, repositories, validators, and explicit public/reporter/admin mappers own these rules.
- **Q4:** Submit valid/invalid cases and inspect public/reporter/admin responses through integration tests.
- **Q5:** Do not publish cases, implement moderator decisions, or infer that a duplicate is true without human review.

## Tasks

### CT-011 — Create case, sensitive-details, file, and consent schema

- **Labels:** `epic:case-registry`, `type:infra`, `type:security`, `priority:p0`
- **Depends on:** CT-006
- **Issue:** Add Flyway migrations for cases, sensitive details, case files, and consent records.
  Model separate review and public statuses, reporter ownership, public contact, admin-only data,
  minor/duplicate flags, timestamps, file visibility, and consent version/source.
- **Acceptance criteria:** Foreign keys and indexes support reporter/status/review queries; status
  enums are explicit; consent and file records are attributable; sensitive fields are not nullable
  by accident where the product requires them.
- **Tests:** Migration and repository tests for ownership, status filters, consent history, and file association.

### CT-012 — Implement case entities, repositories, and visibility projections

- **Labels:** `epic:case-registry`, `type:security`, `priority:p0`
- **Depends on:** CT-011
- **Issue:** Implement case entities/repositories and separate `PublicCaseResponse`,
  `ReporterCaseResponse`, and `AdminCaseResponse` mappers. Controllers must never serialize entities directly.
- **Acceptance criteria:** Public mapping excludes every admin-only field and private identity; reporter mapping is ownership-scoped; admin mapping requires role authorization; pagination is stable.
- **Tests:** Projection contract tests that assert sensitive fields are absent from public JSON.

### CT-013 — Implement validated case submission with mandatory report and consent

- **Labels:** `epic:case-registry`, `type:feature`, `type:security`, `priority:p0`
- **Depends on:** CT-009, CT-011, CT-012, CT-015
- **Issue:** Implement `POST /api/user/cases` with public details, sensitive details, public contact,
  report-file reference, explicit consent, age/date validation, and initial review status.
- **Acceptance criteria:** Valid submissions enter review; missing report or consent returns actionable
  validation errors; consent version/timestamp/source are stored; response returns a safe case reference;
  possible duplicates do not block submission.
- **Tests:** Valid submission, missing file, missing consent, invalid dates/age, ownership, and safe-response tests.

### CT-014 — Implement non-blocking duplicate detection and minor prioritization

- **Labels:** `epic:case-registry`, `type:feature`, `priority:p1`
- **Depends on:** CT-012
- **Issue:** Add normalized-name plus reported/last-seen date matching, confidence/reason metadata,
  and a minor flag for age under 18. Store results for reviewers without rejecting intake.
- **Acceptance criteria:** Detection is deterministic for the same data; missing dates are handled;
  age 17 is flagged and age 18 is not; a possible match marks the case but does not block it.
- **Tests:** False-positive, missing-date, boundary-age, and non-blocking submission tests.

### CT-015 — Implement file metadata validation and case-file association

- **Labels:** `epic:case-registry`, `type:security`, `type:infra`, `priority:p0`
- **Depends on:** CT-011
- **Issue:** Define a storage port and validate file type, size, checksum/metadata, visibility, and
  generated-reference requirements before a case can reference a mandatory report or public photo.
- **Acceptance criteria:** User-provided paths cannot become storage keys; invalid type/size metadata
  is rejected; private report and public-photo visibility are explicit; persistence stores only safe metadata.
- **Tests:** File validator, unsafe-key, size/type, visibility, and case association tests.

## Verification

- Authenticated user submits a valid case with consent and a report reference
- Missing report/consent is rejected
- Public, reporter, and admin projections are tested against the privacy table
- Duplicate and minor flags are review signals, not automatic decisions
