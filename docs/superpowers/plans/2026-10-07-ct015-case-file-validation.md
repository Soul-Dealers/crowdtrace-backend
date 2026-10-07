# CT-015 — File Metadata Validation and Case-File Association Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/15
**Phase:** 2 — Case Registry and Submission
**Depends on:** CT-011 (`V12` `case_files` table, decisions D9/D10), CT-012 (`CaseFile` entity, `CaseFileRepository`, `CaseFileMetadataResponse`)
**Unblocks:** CT-013 (upload endpoint + submission attaches files), CT-018 (reporter adds photos to a live case), CT-023 (photo delivery / report download), CT-030 (S3 adapter implements the port), CT-032 (file deletion uses the port)

**Goal:** A file can become case evidence only after the server has measured, hashed, typed, and stored it under a key the server generated. A case can reference only files its reporter uploaded that are live and not yet attached.

**Architecture:** Everything lives in `modules.casefile`. The application services are `CaseFileUploadService` (validate → store → record) and `CaseFileAttachmentService` (lock → check → attach). The port is `FileStorage`, with an in-memory fallback adapter; CT-030 supplies the S3 adapter. Visibility is derived from purpose. Keys and checksums never leave the module. There are **no controllers** in this task: CT-013 owns the HTTP endpoints.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL 16 + Testcontainers, JUnit 5, AssertJ. No new dependencies; file-type detection is hand-rolled for three formats.

**Spec:** `docs/product-spec.md` §5.1 (Photo = Public, report = Admin-only), §10 ("validate file type, file size, and upload content before storage"), open question §592; `docs/blueprint.md` Step 7.1; CT-011 plan D9/D10; CT-012 plan D5/D11.

---

## Decisions

Settled by Edem, with a design review between Claude and Codex ("Astra").

| # | Decision | Rationale |
|---|---|---|
| D1 | **CT-015 = port + validation + association services. No endpoints.** The upload endpoint (`POST /api/user/case-files`) moves to **CT-013**, which owns the phase-2 endpoints | Edem's call. CT-013's issue has been updated to include the endpoint and photo references. |
| D2 | **Upload first, attach at submission.** An upload creates a `case_files` row with `case_id = NULL`; CT-013 attaches files by id inside its submission transaction | Matches CT-011 D9. Being "unattached" is a database state (`case_id IS NULL`), not a stored status column. |
| D3 | **Server-side ingest only (bytes go through the API).** No presigned direct-to-S3 uploads for MVP | We must hash and type-check the actual bytes. Direct upload with post-hoc verification is possible, but rejected for complexity, not impossibility. |
| D4 | **The client's `Content-Type`, filename and extension are untrusted and never persisted.** The stored `content_type` is the type the server *detected* | AC "persistence stores only safe metadata". A filename can contain PII ("kofi_mensah_police_report.pdf") or path-traversal text. |
| D5 | **Allowed types:** REPORT = `application/pdf`, `image/jpeg`, `image/png`. PHOTO = `image/jpeg`, `image/png`. **Max 10 MB for both**, configurable | Edem's call on the size (it matches the current multipart limit). WebP is deferred: it needs a fourth detector, and phones export JPEG. |
| D6 | **Detect type from content, then check structure.** Signature *and* trailer: PDF `%PDF-` … `%%EOF`; JPEG `FFD8FF` … `FFD9`; PNG 8-byte signature + `IHDR` first … `IEND` last. Empty files, truncated files and unknown types are rejected | Astra's point: magic bytes alone accept a renamed or truncated file. Header plus trailer is cheap and catches both. We never decode images, so decompression bombs can't hurt us *in this task* (flagged for any future re-encoding). |
| D7 | **Spool to a size-bounded temp file while computing SHA-256 and counting bytes.** Stop at max+1 bytes. Hash what we store | One pass, bounded memory. The declared size is never trusted. The temp file is deleted in `finally`. |
| D8 | **A client checksum is optional and is only *compared*, never trusted.** A mismatch is rejected | It lets a client detect corruption in transit without giving it authority over stored metadata. |
| D9 | **Storage keys are generated: `reports/{uuid}` or `photos/{uuid}`.** No user input and no extension. The key is wrapped in a `StorageKey` value object validated against `^(reports\|photos)/[0-9a-f-]{36}$` | Covers AC "user-provided paths cannot become storage keys". V12 enforces uniqueness only, not format, so the value object is the format guard. The prefix lets CT-030 apply bucket/prefix policy. |
| D10 | **Visibility = `purpose.visibility()`** (REPORT→PRIVATE, PHOTO→PUBLIC). It is never an input | Mirrors the V12 CHECK. **PUBLIC is a storage policy, not "servable now":** photos are served only once a case is approved (CT-011 D10, CT-023). |
| D11 | **The port receives full metadata:** `put(StoredObject meta, Path content)` / `delete(StorageKey)`. `StoredObject` carries key, visibility, content type, size and checksum. Download/URL methods are added by CT-023/CT-030 | Astra's point: an adapter needs visibility to pick the bucket/ACL. Returning normally means fully stored. |
| D12 | **Fallback adapter:** `InMemoryFileStorage`, registered only via `@ConditionalOnMissingBean(FileStorage.class)`, logs a WARN at startup. CT-030's S3 bean replaces it | Edem: prod will have a real S3 bucket, so no fail-fast logic. The fallback only keeps dev/tests booting. Rows can outlive its objects across restarts, which is acceptable in dev only. |
| D13 | **Ordering: store the object, then insert the row. On rollback, delete the object.** The delete is registered as an `afterCompletion(STATUS_ROLLED_BACK)` synchronization, not a `catch` around `save()` | Covers failures at commit time, not just at `save()`. If the cleanup delete fails, log the file id (never the key) and leave the object for the phase-6 stale-object sweep. |
| D14 | **Attach rules (all files or none):** ids must be non-empty and distinct. Each file exists, `uploaded_by = reporter`, `deleted_at IS NULL` and `case_id IS NULL`. The case belongs to the reporter. **At least one REPORT** and **≤ 5 PHOTOs** (configurable) | A file that is missing, belongs to someone else, or is deleted all fail with the same `NotFoundException`, so the response doesn't confirm the id exists (CT-012 D7). An already-attached file fails with a `ConflictException`. |
| D15 | **Concurrency:** load files with `PESSIMISTIC_WRITE`, ordered by id. The service is `@Transactional(propagation = MANDATORY)` | Two submissions racing for one file serialize on the row lock. The second sees `case_id` set and gets a conflict. Locking in id order prevents deadlocks. `MANDATORY` forces CT-013 to call it inside its own transaction, so a failed submission rolls the attachment back. |
| D17 | **Two attach entry points sharing one rule set:** `attachSubmission(caseId, reporterId, fileIds)` (requires ≥1 REPORT, used by CT-013) and `attachPhotos(caseId, reporterId, photoIds)` (PHOTO only, no report rule, used by CT-018 for live cases). The photo cap counts **live photos already on the case** + new ones | Edem: reporters can add photos after a case goes live (CT-018). Putting the cap and ownership rules here keeps CT-018 thin. The case-state rule (e.g. no adding to a taken-down case) belongs to CT-018, not here. |
| D16 | **Harden the entity:** `attachTo` rejects null case/time and deleted files. `findByUploadedByAndCaseIdIsNull` → `findByUploadedByAndCaseIdIsNullAndDeletedAtIsNull` | Astra's finding: the current query returns soft-deleted uploads as "pending". |

### Discarded (from blueprint / earlier drafts)

- `upload(file, visibility)` with visibility supplied by the caller → visibility is derived from purpose (D10).
- A `file_type` column → V12 already has `purpose` + detected `content_type`.
- A client-supplied checksum treated as truth → compare only (D8).
- Persisting the original filename → not stored at all (D4).
- Fail-fast for prod without a provider → not needed (Edem, D12).
- Detach/delete-unattached APIs and the stale-upload sweep → phase 6 (CT-011 D9).

### Settled follow-ups (issues updated)

- **EXIF/GPS stripping** of public photos → **CT-023**, before first serve (Edem). Not in CT-015.
- **Reporters add photos to a live case** → **CT-018** (Edem), via D17's `attachPhotos`.
- **Upload endpoint + photo references at submission** → **CT-013** (Edem).

### Out of scope

HTTP endpoints, multipart config, S3 adapter, signed URLs, photo delivery, EXIF stripping, retention/stale cleanup, publication, audit (CT-020), per-user upload quota/rate limit (belongs with the CT-013 endpoint; note that `IpRateLimitInterceptor` lets requests through when it fails).

---

## Global Constraints

- **Branch:** `feat/CT-015-...` cut **after CT-012 merges** (it needs the CT-012 entity/repository). Don't mix it with CT-012's uncommitted working-tree changes.
- **Commits carry no Claude attribution** (`~/.claude/CLAUDE.md`). Style: `feat(casefile): … [CT-015]`.
- **Before tests:** `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true`.
- Repository/service tests use Postgres + Flyway (not H2), as in CT-012.
- **No new migration.** V12 already has every column and CHECK this task needs.
- `casefile` never imports `modules.identity.internal`; `CrowdtraceModulesTest` stays green.
- Errors use the existing `ValidationException` (bad content), `NotFoundException` (not yours / missing) and `ConflictException` (already attached). Messages never include keys, checksums or file contents.
- The suite is green at every commit.

## Review Focus

1. **A client value reaching `storage_key`.** Only `StorageKey.generate(purpose)` creates keys, and its constructor validates format. Pinned by `StorageKeyTest` (Task 1) and `CaseFileUploadServiceTest.ignoresClientFilenameAndType` (Task 4).
2. **A renamed or truncated file accepted.** Pinned by `FileContentInspectorTest` (Task 2).
3. **A file attached to a second case, or to another reporter's case.** Pinned by `CaseFileAttachmentServiceTest` (Task 5), including a two-thread race.
4. **An orphaned object after a rolled-back insert.** Pinned by `CaseFileUploadServiceTest.deletesStoredObjectWhenTransactionRollsBack` (Task 4).

---

## File Structure

Under `src/main/java/com/souldealers/crowdtracebackend/modules/casefile/`:

| File | Responsibility |
|---|---|
| `CaseFilePurpose.java` (modify) | add `visibility()` and `allowedStoragePrefix()` |
| `internal/files/CaseFileProperties.java` | `@ConfigurationProperties("crowdtrace.case-files")`: `maxSizeBytes` (10 MB), `maxPhotosPerCase` (5) |
| `internal/files/StorageKey.java` | record; `generate(purpose)`, validating constructor, `toString()` redacted |
| `internal/files/DetectedType.java` | enum PDF/JPEG/PNG → MIME string |
| `internal/files/FileContentInspector.java` | signature + trailer detection, purpose allowlist |
| `internal/files/SpooledUpload.java` | temp file + size + SHA-256, `AutoCloseable` (deletes the temp file) |
| `internal/storage/FileStorage.java` | port: `put(StoredObject, Path)`, `delete(StorageKey)` |
| `internal/storage/StoredObject.java` | record: key, visibility, contentType, sizeBytes, checksumSha256 |
| `internal/storage/InMemoryFileStorage.java` | fallback adapter (`@ConditionalOnMissingBean`) |
| `CaseFileUploadService.java` + `internal/CaseFileUploadServiceImpl.java` | `upload(uploaderId, purpose, InputStream, Optional<String> clientSha256)` → `CaseFileMetadataResponse` |
| `CaseFileAttachmentService.java` + `internal/CaseFileAttachmentServiceImpl.java` | `attachSubmission(caseId, reporterId, fileIds)`, `attachPhotos(caseId, reporterId, photoIds)` |
| `internal/model/CaseFile.java` (modify) | harden `attachTo` |
| `internal/repository/CaseFileRepository.java` (modify) | locking finder, fix the unattached query |

The public service interfaces live in `modules.casefile` (the module API, like `CaseQueryService`) so that CT-013's controller can call them.

---

### Task 1: Purpose→visibility, properties, `StorageKey`

- [x] `CaseFilePurposeTest`: `REPORT.visibility() == PRIVATE`, `PHOTO.visibility() == PUBLIC`; every purpose has a visibility (loop over `values()`, so a new purpose can't skip one).
- [x] `StorageKeyTest`:
  - `generate(REPORT)` matches `^reports/[0-9a-f-]{36}$`; two calls give different keys.
  - The constructor rejects: `../etc/passwd`, `reports/../x`, `/reports/<uuid>`, `reports/<uuid>.pdf`, `photos/<uuid>` when the purpose is REPORT, blank, null, and a 513-char string.
  - `toString()` doesn't contain the uuid (keeps keys out of logs).
- [x] Implement. Bind `CaseFileProperties` with defaults; add `crowdtrace.case-files.*` to `application.yaml`.
- [x] Commit `feat(casefile): derive file visibility from purpose and generate storage keys [CT-015]`.

### Task 2: `FileContentInspector`

- [x] `FileContentInspectorTest` (fixtures built in-test from byte arrays; no binary files checked in):
  - A minimal valid PDF / JPEG / PNG is detected as the right type.
  - Rejected: empty; unknown bytes; PDF missing `%%EOF`; JPEG missing `FFD9`; PNG missing `IEND`; PNG whose first chunk isn't `IHDR`; a PDF uploaded as PHOTO (type not allowed for that purpose); HTML/SVG/ZIP disguised with a `.png` name.
  - Trailing whitespace/newline after `%%EOF` is accepted (real PDFs have it).
- [x] Implement it reading the first 16 bytes and the last 1 KB of a `Path`. Return `DetectedType` or throw `ValidationException`.
- [x] Commit `feat(casefile): detect upload type from content with header and trailer checks [CT-015]`.

### Task 3: `SpooledUpload`, port, in-memory adapter

- [x] `SpooledUploadTest`: the size equals the bytes written; SHA-256 matches `MessageDigest` over the same bytes; a stream of max+1 bytes throws `ValidationException` and leaves no temp file; `close()` deletes the temp file.
- [x] `InMemoryFileStorageTest`: `put` then `delete` removes the object; putting a duplicate key throws (it's a bug, not an overwrite).
- [x] Implement `SpooledUpload.from(InputStream, maxBytes)` using `DigestInputStream` and a counting copy into `Files.createTempFile`. Implement the port, the `StoredObject` record and the adapter (startup WARN, "not for production").
- [x] Commit `feat(casefile): add file storage port with bounded spooling and checksum [CT-015]`.

### Task 4: `CaseFileUploadService`

- [x] `CaseFileUploadServiceTest` (Postgres, a recording `FileStorage` test double):
  - A valid report → row has `case_id NULL`, `uploaded_by`, `purpose REPORT`, `visibility PRIVATE`, detected `content_type`, actual `size_bytes`, server checksum, and a key matching `reports/…`. The adapter received the same metadata. The response has no key/checksum fields.
  - `ignoresClientFilenameAndType`: the API takes no filename or declared type, so assert by signature, and that a PNG uploaded "as" PDF is stored as `image/png`.
  - Oversize, empty and wrong type for the purpose → `ValidationException`, nothing stored, no row.
  - A client checksum that matches is accepted; a mismatch is rejected before storing.
  - `deletesStoredObjectWhenTransactionRollsBack`: force the insert to fail (e.g. a non-existent `uploaded_by` FK) → the object is deleted from the adapter.
  - The adapter throws on `put` → no row, and the exception surfaces as an upload failure without the key.
- [x] Implement it: `try (SpooledUpload s = …)` → inspect → `StorageKey.generate` → `storage.put` → register the rollback-delete synchronization → `repository.save` → map with `CaseMapper` to `CaseFileMetadataResponse`.
- [x] Commit `feat(casefile): validate, store, and record case file uploads [CT-015]`.

### Task 5: `CaseFileAttachmentService` + entity/repository hardening

- [x] `CaseFileTest` additions: `attachTo(null, …)` and `attachTo(id, null)` throw; attaching a deleted file throws.
- [x] `CaseFileRepositoryTest`: rename the unattached-query test, and add the case that a soft-deleted unattached file is **excluded**. Add `findAllByIdInOrderByIdAsc` with `@Lock(PESSIMISTIC_WRITE)`.
- [x] `CaseFileAttachmentServiceTest` (Postgres):
  - A report plus 2 photos attaches all of them, with the same `attached_at`.
  - No REPORT among the ids → `ValidationException` ("a missing-person report is required"); nothing attached.
  - 6 photos → `ValidationException`. Empty list / duplicate ids → `ValidationException`.
  - `attachPhotos`: works without a report; rejects a REPORT id; a case with 4 live photos accepts 1 more and rejects 2 (cap counts existing photos; soft-deleted photos don't count).
  - Another reporter's file, a deleted file, or an unknown id → `NotFoundException`, with the same message for all three.
  - The case belongs to a different reporter → `NotFoundException`.
  - A file already attached elsewhere → `ConflictException`.
  - Called outside a transaction → `IllegalTransactionStateException` (`MANDATORY`).
  - **Race:** two threads attach the same report to two cases; exactly one succeeds and the other gets `ConflictException`.
  - Any failure → no file in the batch is attached.
- [x] Implement it. Both entry points share one private `attach(…, requireReport, allowedPurposes)`. Check everything before mutating anything, then call `attachTo` on each file. Add `countByCaseIdAndPurposeAndDeletedAtIsNull` for the cap.
- [x] Commit `feat(casefile): attach owned, unattached files to a case atomically [CT-015]`.

### Task 6: Boundary guards

- [x] Architecture test: only `modules.casefile.internal..` may depend on `FileStorage` / `StorageKey`. No public `modules.casefile` type exposes `StorageKey` or a field named `storageKey`/`checksum*`.
- [x] Extend the CT-012 projection contract test so `CaseFileMetadataResponse` JSON has exactly `id, purpose, visibility, contentType, sizeBytes, uploadedAt`.
- [x] Run the full suite + `CrowdtraceModulesTest`.
- [x] Commit `test(casefile): guard storage keys and file metadata exposure [CT-015]`.

---

## Implementation notes

Two small implementation adjustments were reviewed with Claude Opus 5.5:

- Attachment locks the parent case first, then locks file rows in id order. The parent lock serializes attachment batches for the same case, so concurrent requests cannot both observe available photo capacity and exceed the aggregate cap. Ordered file locks still protect against a file being attached to two cases.
- The conditional in-memory fallback is registered through deferred `@AutoConfiguration` with `@ConditionalOnMissingBean(FileStorage.class)`. This evaluates the fallback after application storage beans are registered, allowing CT-030's adapter to replace it reliably.

Task 5 was split into two verified operation commits to match Edem's incremental commit preference: submission attachment and entity/repository hardening (`3e788ee`), followed by adding photos with the aggregate cap (`aad3a2f`). Tasks 1–4 are `998fefa`, `b0b8fcb`, `6233236`, and `5cfce9a` respectively.

Boundary guards also protect `StoredObject`, because its metadata carries the key and checksum. Negative fixtures prove the guards reject external storage dependencies, keys wrapped in generic fields or method signatures, and sensitive field names. The standalone file metadata JSON contract pins its six safe fields and excludes stored key/checksum values.

## Acceptance-criteria trace

| AC | Where |
|---|---|
| User-provided paths cannot become storage keys | D4, D9; `StorageKeyTest`, `ignoresClientFilenameAndType`, Task 6 architecture rule |
| Invalid type/size metadata is rejected | D5–D7; `FileContentInspectorTest`, `SpooledUploadTest`, upload service tests |
| Private report and public-photo visibility are explicit | D10; `CaseFilePurposeTest`, upload test asserts visibility; V12 CHECK as backstop |
| Persistence stores only safe metadata | D4, D8; detected type, measured size, server hash, generated key only; Task 6 response contract |
| Tests: validator, unsafe-key, size/type, visibility, case association | Tasks 1–5 |

## Handoff notes

- **→ CT-013:** Add `POST /api/user/case-files` (multipart, `purpose` param) → `CaseFileUploadService.upload`. Raise `spring.servlet.multipart.max-file-size` only together with `crowdtrace.case-files.max-size-bytes`. The submission takes `reportFileIds` + `photoFileIds` (or one `fileIds` list) and calls `CaseFileAttachmentService.attachSubmission` **inside** the submission transaction, after creating the case. Add a per-user upload rate limit/quota on the endpoint.
- **→ CT-018:** Add-photo on a live case = CT-013 upload + `attachPhotos`. CT-018 owns the case-state check (live, not taken down), the audit, and the follower event.
- **→ CT-023 / CT-030:** Implement `FileStorage` with S3, choosing the bucket/prefix by `StoredObject.visibility`. Add download/URL methods to the port. Photos are served only for approved cases. CT-023 strips EXIF/GPS before first serve and records the stripped checksum.
- **→ CT-032 / phase 6:** Stale unattached uploads (`idx_case_files_unattached`) and orphaned objects from failed rollback-deletes need a sweep job.
