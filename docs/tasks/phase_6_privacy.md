# Phase 6 — Privacy, Files, and Retention

**Goal:** Protect sensitive evidence and fields during normal operation and remove them predictably
after the defined retention window.

**Five Questions:**

- **Q1:** Authorized reviewers can use evidence while CrowdTrace minimizes retained sensitive data.
- **Q2:** Private reports cannot be public; storage keys are generated; cleanup never removes public history or runs twice destructively.
- **Q3:** File storage, privacy policy enforcement, and retention services own these rules.
- **Q4:** Storage access, field redaction, deletion plan, dry-run, retry, and audit tests prove them.
- **Q5:** Do not log raw files/secrets, expose object keys, or delete public historical fields as part of sensitive cleanup.

## Tasks

### CT-030 — Implement S3 storage service with generated keys and signed access

- **Labels:** `epic:privacy`, `type:infra`, `type:security`, `priority:p0`
- **Depends on:** CT-015, CT-020
- **Issue:** Configure public-photo and private-report storage policy, implement generated storage keys,
  upload/delete, metadata validation, and time-limited authorized download URLs behind a storage port.
- **Acceptance criteria:** User filenames never become paths; private reports require an authorized actor;
  public photos obey case visibility; URLs are short-lived; provider failures do not leave inconsistent metadata.
- **Tests:** Mock S3 upload/download/delete, key-generation, visibility, expiry, and failure tests.

### CT-031 — Enforce consent, privacy-aware mapping, and sensitive-data logging rules

- **Labels:** `epic:privacy`, `type:security`, `type:test`, `priority:p0`
- **Depends on:** CT-012, CT-020, CT-030
- **Issue:** Centralize consent checks, response mappers, authorization checks, sensitive-file access,
  correction/takedown records, and log redaction. Prepare policy references for legal review.
- **Acceptance criteria:** Consent is required for sensitive intake; public/user/admin boundaries are
  enforced server-side; sensitive values do not enter ordinary logs; protected access is auditable.
- **Tests:** Authorization matrix, consent-version, log-redaction, projection, and file-access tests.

### CT-032 — Implement retention eligibility and field/file deletion

- **Labels:** `epic:privacy`, `type:security`, `type:feature`, `priority:p0`
- **Depends on:** CT-018, CT-030, CT-031
- **Issue:** Implement retention planning for cases resolved/closed more than 12 months ago. Delete
  sensitive fields and private reports while preserving public historical fields and recording affected data.
- **Acceptance criteria:** Only eligible cases are cleaned; active/recent cases are skipped; public
  identity/status/history remain; private files are deleted; cleanup is idempotent and auditable.
- **Tests:** Eligibility boundaries, partial-failure, idempotency, field-preservation, and storage-delete tests.

### CT-033 — Add retention dry-run, audit, metrics, retry, and alerting

- **Labels:** `epic:privacy`, `type:infra`, `type:test`, `priority:p0`
- **Depends on:** CT-032, CT-038
- **Issue:** Schedule retention cleanup, add dry-run mode, execution identity, scanned/cleaned/skipped/
  failed metrics, retries, and alerts for failed jobs.
- **Acceptance criteria:** Operators can preview changes without deleting; failed items retry safely;
  each execution is traceable; metrics and alerts distinguish skipped, completed, and failed work.
- **Tests:** Scheduler, dry-run, retry, metrics, alert, and recovery integration tests.

## Verification

- Authorized reviewer downloads a private report through an expiring URL
- Guest cannot access the report or raw key
- Closed case older than 12 months loses sensitive fields/file but retains public history
- Failed cleanup resumes without duplicate destructive work
