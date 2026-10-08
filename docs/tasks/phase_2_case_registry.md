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
  one or more `reportFileIds`, optional `photoFileIds` (up to 5), explicit current-version consent,
  and age/date validation. Create cases as `SUBMITTED` with no public outcome, set
  `priority_minor = age < 18` at insert, and publish `CaseSubmittedEvent(caseId)` inside the submission
  transaction. Also implement `POST /api/user/case-files` (multipart report/photo upload via CT-015's
  services) with a limit of 20 successful uploads per user per 24-hour window; rejected files refund
  their quota charge.
- **Acceptance criteria:** Valid submissions enter review; missing report or consent returns actionable
  validation errors; consent version/timestamp/source are stored; response returns a safe case reference;
  possible duplicates do not block submission; reporters can upload a report and up to 5 photos and attach
  only their own unattached uploads in the matching report/photo slots. Case, sensitive details,
  consent and attachments commit atomically. The response contains only `caseId`, `reviewStatus`
  and `submittedAt`; age 17 receives minor priority and age 18 does not.
- **Tests:** Valid submission, missing file, missing consent, invalid dates/age, ownership, safe-response,
  upload endpoint/quota/refund, photo attachment/cap, foreign-file, atomic rollback, boundary-age,
  and throwing-after-commit-listener tests.

### CT-014 — Implement non-blocking duplicate detection

- **Labels:** `epic:case-registry`, `type:feature`, `priority:p1`
- **Depends on:** CT-012, CT-013
- **Issue:** Add normalized-name plus last-seen date matching, confidence/reason metadata,
  and the duplicate review signal. Consume CT-013's `CaseSubmittedEvent(caseId)` with
  `@TransactionalEventListener(AFTER_COMMIT)` and write results in `REQUIRES_NEW`. Delivery is
  best-effort with no replay while the application uses Modulith core only. CT-013 owns minor
  priority at creation; CT-014 does not change `priority_minor`. Only the new submission is flagged;
  existing cases keep their flags and versions. All review/case statuses and same-reporter cases
  are candidates. Detection locks the new case so concurrent redelivery is a clean no-op.
- **Matching rules:** Unicode accent/case/punctuation normalization and sorted tokens (keeping
  repeats); code-point Levenshtein similarity ≥ 0.90 and last-seen dates within 7 days by default.
  A single-token name requires an exact normalized match; missing dates produce no match.
  `submitted_at` is never a matching date. Thresholds are startup-validated under
  `crowdtrace.duplicates` (`date-window-days`: 0–32767, `name-threshold-permille`: 0–1000).
- **Metadata:** Directional match rows store ids, integer scores, fixed reason codes and algorithm
  version `v1`, without name snapshots. Confidence is a ranking aid, not a probability:
  `roundHalfUp(100 × (0.8 × N + 0.2 × (1 − days/(window + 1))))`.
  With the default 7-day window this is the agreed `days/8` formula. Similarity is rounded to
  permille only for storage; threshold comparison uses the exact fraction. Review APIs and
  moderator duplicate/distinct decisions belong to CT-017. Logs contain only case id and
  exception type; failures never reject intake.
- **Acceptance criteria:** Detection is deterministic for the same data; missing dates are handled;
  a possible match marks the case for reviewers; listener failures cannot reject or roll back intake.
- **Tests:** False-positive, missing-date, deterministic matching, duplicate metadata, after-commit
  processing, concurrent/idempotent delivery, existing-case immutability, configuration validation,
  and non-blocking listener failure tests. Minor age boundaries are covered by CT-013.

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
