# CT-020 — Centralized Auditable State-Change Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/20
**Phase:** 3 — Review, Moderation, Lifecycle, and Audit
**Depends on:** CT-016 (`audit_events`, V14)
**Unblocks:** CT-017, CT-018, CT-019, CT-023, CT-032/033, CT-034, CT-035

**Goal:** One recorder that every protected state change goes through. A protected operation that does not record its audit event fails and rolls back. A recorder failure rolls back the change it protects. Audit rows are append-only at the database level and carry no passwords, file contents, free text or personal data.

**Architecture:** `shared.audit` becomes a Modulith named interface (`AuditRecorder`, `AuditAction`, `AuditActor`, `AuditActorRole`, `AuditTargetType`, `AuditMetadata`, `@AuditedOperation`). Persistence (`AuditEvent` entity, insert-only repository) moves to `shared.audit.internal`. An aspect enforces "each `@AuditedOperation` invocation records its event". V15 makes `audit_events` immutable. V16 stops verification revocation from overwriting the approval. CT-010's verification decisions are the first consumer; every other consumer wires itself in its own task.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA, Flyway, PostgreSQL 16, Testcontainers, JUnit 5, AssertJ. **New dependency:** `spring-boot-starter-aspectj`, needed for the `@Aspect` that enforces the audit rule. Spring's transaction proxies already use spring-aop; this adds the AspectJ annotation support.

**Spec:** `docs/tasks/phase_3_governance.md` CT-020; `docs/product-spec.md` §6 (L292), retention (L306, L317); CT-016 plan D7–D8 and handoffs; CT-010 plan (grant vs approve); `docs/decisions/ADR-001-modular-monolith.md` (L33–38).

---

## Decisions

Designed by Claude and Codex ("Astra", gpt-6-astra) independently, then reconciled. Astra's refinements (D4, D6, D9) were adopted, as were the seven defects from its review of the final plan (D2b, D4c, D7b, Tasks 3, 5, 6). D7, D10 and D11 were settled by Edem. D4d records the conservative savepoint behavior established during implementation. No open disagreements.

| # | Decision | Rationale |
|---|---|---|
| D1 | **`shared.audit` is `@NamedInterface("audit")`; persistence is in `shared.audit.internal`** | Same pattern as `shared.ratelimit`. Modules depend on the recorder API, never on the entity or repository. The empty public `AuditEvent` placeholder moves to `internal`. `CrowdtraceModulesTest` asserts the boundary. |
| D2 | **API: `recorder.record(AuditActor actor, AuditAction action, long targetId, AuditMetadata metadata)`** | The target type comes from the action (each action binds exactly one `AuditTargetType`), so a caller can't pair an action with the wrong kind of target. The calling service passes the actor explicitly; the recorder never reads the `SecurityContext`, because jobs have none and services already resolve the acting user. |
| D2b | **`AuditMetadata` carries the action it was validated for; `record` rejects a mismatch** | Otherwise `AuditMetadata.none(USER_DEACTIVATED)` could go with `VERIFICATION_APPROVED`, skip its required keys, and still discharge the obligation. The check runs inside the transactional recorder, so a mismatch rolls back. |
| D2a | **`AuditActor.user(long id, AuditActorRole role)` / `AuditActor.system()`; `AuditActorRole` is owned by audit** | Shared must not import identity's `UserRoles`. Identity maps it with an exhaustive `switch` (a new role fails compilation). The invariant "system ⇔ no id" mirrors V14's `audit_events_system_actor_check`. Ids must be positive. |
| D3 | **Transaction safety:** `record` is `@Transactional(propagation = MANDATORY)` on its own proxied bean, rejects read-only transactions, `saveAndFlush`es, and throws unchecked `AuditRecordingException` | `MANDATORY` means audit can't run outside the protected change's transaction. Flushing surfaces constraint failures inside the caller's method, so the whole change rolls back. Unchecked means Spring's default rollback rules apply. Flush ≠ commit: a later failure in the caller rolls back the audit row too, which is correct. |
| D4 | **"Cannot succeed without an audit event" is enforced at runtime by `@AuditedOperation(AuditAction...)`** | An `@Around` aspect opens a per-invocation obligation (one per declared action) bound to the current transaction. `record` discharges the matching obligation of the innermost open invocation. On normal return, any undischarged obligation throws `AuditMissingException`, so the transaction rolls back. The aspect fails closed if no transaction is active. This proves omission at runtime, which ArchUnit can't. Integration tests still prove correct target and actor per operation. |
| D4c | **Obligations are isolated per transaction and survive suspension** | The obligation stack is held by a `TransactionSynchronization` that unbinds it on `suspend()` and rebinds it on `resume()`. Without this, an unannotated `REQUIRES_NEW` helper could discharge the outer operation's obligation, roll back, have its exception caught, and the outer change would commit with no event. |
| D4d | **Spring-managed savepoint rollback rejects the whole transaction once audit tracking starts** | The transaction synchronization observes `savepointRollback` and rejects `beforeCommit`, because a flushed event could otherwise roll back while its invocation still appeared discharged. This intentionally conservative policy applies to any Spring-managed savepoint rollback after tracking begins; raw JDBC savepoints bypass Spring callbacks and are unsupported. The same `beforeCommit` check rethrows a saved obligation violation if an `AuditMissingException` from an audited helper without its own transaction advice was swallowed. Caught recorder failures are separately protected by the `MANDATORY` transaction interceptor marking the transaction rollback-only. |
| D4a | **Advice order is explicit:** `@EnableTransactionManagement(order = 0)` in `AuditConfig`; the aspect is `@Order(LOWEST_PRECEDENCE)` | The transaction must wrap the aspect so the obligation check runs before commit. The no-transaction guard makes a mis-ordering fail every audited call loudly in tests rather than silently. |
| D4b | **One operation may declare several actions; a declared action that may not happen is not allowed** | E.g. `grant` declares only `VERIFICATION_GRANTED`. Operations whose action depends on input call `record` for the action they actually took and declare it via a narrower method. This keeps the check exact. |
| D5 | **Vocabulary: one enum, explicit dotted storage codes, for settled requirements only** | Each constant = `(code, targetType, metadata schema)`. Codes are listed below. Tests: codes unique, match V14's `^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)*$`, ≤ 64 chars, every action has a schema. Consumers may adjust their action's metadata in their own task, never invent a string. |
| D6 | **Redaction = fail-closed value schemas, not key allowlists** | Each action declares its keys and the type of each value: `ID` (positive long), `COUNT` (non-negative int), `FLAG` (boolean), or `CODE(EnumClass)` (a constant of one named closed enum). Required keys are required. Unknown keys, nested values, strings and other enums are rejected with an error that names the key, never the value. `AuditMetadata` copies defensively. With no free text, passwords, emails, names, notes and file bytes are unrepresentable rather than filtered. Notes stay on domain rows (`case_reviews`, `verification_requests`), and audit references them by id. |
| D6a | **Size: Java checks the serialized object ≤ 4 KB; V14's DB check stays as the backstop** | Java UTF-8 length and PostgreSQL's `jsonb::text` length differ slightly, so the DB constraint stays authoritative and both are tested at the boundary. In practice, id/count/flag/code schemas stay far below it. |
| D7 | **`audit_events` is strictly immutable and append-only (Edem)** | V15 adds `BEFORE UPDATE OR DELETE … FOR EACH ROW` and `BEFORE TRUNCATE … FOR EACH STATEMENT` triggers that raise. **There is no correction path**: a mistake is answered by a new event, never an edit. The entity is `@Immutable`; an internal insert-only repository fragment uses `EntityManager.persist` plus flush and rejects entities with an id, avoiding a merge/update path. D6 is what makes "never deletable" safe. |
| D7b | **Compensating event: `AUDIT_EVENT.CORRECTED`** targets the original event's id, with `reason: CODE(AuditCorrectionReason)` (`RECORDED_IN_ERROR`, `WRONG_ACTOR`, `WRONG_TARGET`, `WRONG_METADATA`) and `replacementEventId?: ID` | This is how Edem's rule says to fix a mistake: append, reference the original, never edit. CT-020 defines it and tests it at the recorder level. Who may issue it (SUPER_ADMIN) and the endpoint are CT-034/035. |
| D7a | **Known limit: the table owner can still drop the trigger** | Flyway and the app currently share one DB role. Real tamper-resistance needs a separate migration (owner) role and a runtime role with only `INSERT, SELECT` on `audit_events`. Handed to CT-038/039 (ops/release hardening). |
| D8 | **Correlation id is normalized in both places** | `CorrelationIdFilter` replaces a missing or invalid header (not matching `^[A-Za-z0-9._-]{1,64}$`) with a UUID before it enters the MDC. The recorder copies the MDC value only if it still matches, otherwise null. Jobs set and clear a run correlation id themselves. A valid id is caller-controlled and is not evidence of identity. |
| D9 | **First consumer: verification approve, reject, revoke and grant (CT-010)** | It exists today and is unaudited. Grant is its own action (CT-010 separates it from approve). The audit row is written in the same transaction as the request + badge change. The pre-commit `logDecision` INFO line is replaced by an after-commit log carrying the audit event id and safe ids (D12). |
| D10 | **Revocation no longer overwrites the approval (Edem)** | V16 adds `revoked_by`, `revoked_at`, `revocation_notes` to `verification_requests`. `revoke` writes those and leaves `reviewer_id`/`review_notes`/`reviewed_at` (the approval) intact. CHECK: a `REVOKED` row has `revoked_by` and `revoked_at`; any other row has all three revocation columns NULL (explicit alternatives, so partial attribution can't pass). Existing `REVOKED` rows are backfilled by copying `reviewer_id/reviewed_at/review_notes` into the revoked columns. Their original approver was already overwritten and can't be recovered, which is stated in the migration comment. The admin response gains `revokedAt` and `revocationNotes` (additive). |
| D11 | **Audit events are kept indefinitely (Edem)** | Retention (CT-032) never deletes them, which D7 enforces anyway. Metadata holds no personal data, so audit rows are outside the field-deletion scope. Revisit with counsel only through a new decision. |
| D12 | **Logging: one INFO line after commit, from the recorder** | `audit_recorded eventId= action= targetType= targetId= actorId=`, never metadata. It is registered as `afterCommit`, so a rolled-back change never logs as done. Logs are an operational copy; the table is authoritative. |
| D13 | **`occurred_at` = `LocalDateTime.now(clock)` from the injected UTC `Clock`** | Matches V14's timezone-less column and `ClockConfig`. It is recording time, not commit order; `id` breaks ties. |

### Action vocabulary (D5)

| Constant | Code | Target | Metadata (key: type, `?` = optional) | Wired by |
|---|---|---|---|---|
| `VERIFICATION_APPROVED` | `VERIFICATION.APPROVED` | `VERIFICATION_REQUEST` | `userId: ID`, `verificationType: CODE(AuditVerificationType)` | **CT-020** |
| `VERIFICATION_REJECTED` | `VERIFICATION.REJECTED` | `VERIFICATION_REQUEST` | same | **CT-020** |
| `VERIFICATION_REVOKED` | `VERIFICATION.REVOKED` | `VERIFICATION_REQUEST` | same | **CT-020** |
| `VERIFICATION_GRANTED` | `VERIFICATION.GRANTED` | `VERIFICATION_REQUEST` | same | **CT-020** |
| `CASE_APPROVED` | `CASE.APPROVED` | `CASE` | `reviewId: ID` | CT-017 |
| `CASE_REJECTED` | `CASE.REJECTED` | `CASE` | `reviewId: ID` | CT-017 |
| `CASE_STATUS_CHANGED` | `CASE.STATUS_CHANGED` | `CASE` | `fromStatus: CODE`, `toStatus: CODE` | CT-018 |
| `CASE_CLOSING_STATEMENT_SET` | `CASE.CLOSING_STATEMENT_SET` | `CASE` | — | CT-018 |
| `CASE_TAKEN_DOWN` | `CASE.TAKEN_DOWN` | `CASE` | — | CT-018 |
| `CASE_PHOTO_ADDED` | `CASE.PHOTO_ADDED` | `CASE` | `fileId: ID` | CT-018 |
| `CASE_FILE_PRIVATE_ACCESS_GRANTED` | `CASE_FILE.PRIVATE_ACCESS_GRANTED` | `CASE_FILE` | `caseId: ID` | CT-023 |
| `COMMENT_REMOVED` | `COMMENT.REMOVED` | `COMMENT` | `resolvedReports: COUNT` | CT-019 |
| `CONTENT_REPORT_DISMISSED` | `CONTENT_REPORT.DISMISSED` | `CONTENT_REPORT` | `commentId: ID` | CT-019 |
| `USER_ROLE_CHANGED` | `USER.ROLE_CHANGED` | `USER` | `fromRole: CODE(AuditActorRole)`, `toRole: CODE(AuditActorRole)` | CT-034 |
| `USER_DEACTIVATED` | `USER.DEACTIVATED` | `USER` | — | CT-034 |
| `AUDIT_EVENT_CORRECTED` | `AUDIT_EVENT.CORRECTED` | `AUDIT_EVENT` | `reason: CODE(AuditCorrectionReason)`, `replacementEventId?: ID` | CT-034/035 |
| `RETENTION_CASE_SENSITIVE_DATA_PURGED` | `RETENTION.CASE_SENSITIVE_DATA_PURGED` | `CASE` | `filesDeleted: COUNT` | CT-032 |

`CODE` enums used by audit are owned by `shared.audit` (`AuditVerificationType`, `AuditCaseStatus`, `AuditActorRole`, `AuditCorrectionReason`), mapped by the owning module with an exhaustive `switch`, for the same reason as D2a. Takedown reasons and closing statements are free text, so they stay on the case row (CT-018 decides where), not in audit. "Access granted" (CT-023) means a signed URL was issued, not that a download happened; the event must commit before the URL is returned.

**Discarded:** a correction/maintenance path for audit rows (D7). An append-only design that relies only on the app. Key-only allowlists. Free-text metadata. `RETENTION.CASE_PURGED` (case history survives; only sensitive data goes). Pulling the actor from the `SecurityContext`. A `beforeCommit`-only check (doesn't tie an event to its invocation).

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | add `spring-boot-starter-aspectj` |
| `shared/audit/package-info.java` | Create | `@NamedInterface("audit")` |
| `shared/audit/AuditRecorder.java` | Create | public API (interface) |
| `shared/audit/AuditAction.java` | Create | vocabulary + target + schema |
| `shared/audit/AuditTargetType.java`, `AuditActorRole.java`, `AuditVerificationType.java`, `AuditCaseStatus.java`, `AuditCorrectionReason.java` | Create | closed code sets |
| `shared/audit/AuditActor.java` | Create | record + `user`/`system` factories |
| `shared/audit/AuditMetadata.java`, `AuditValueType.java` | Create | builder + D6 validation |
| `shared/audit/AuditedOperation.java` | Create | annotation |
| `shared/audit/AuditRecordingException.java`, `AuditMissingException.java` | Create | unchecked failures |
| `shared/audit/AuditEvent.java` | Delete | placeholder moves to `internal` |
| `shared/audit/internal/AuditEvent.java` | Create | `@Immutable` entity |
| `shared/audit/internal/AuditEventRepository.java` | Create | insert-only |
| `shared/audit/internal/JpaAuditRecorder.java` | Create | D3, D6a, D8, D12, D13 |
| `shared/audit/internal/AuditObligations.java`, `AuditedOperationAspect.java` | Create | D4 |
| `shared/audit/internal/AuditConfig.java` | Create | D4a ordering |
| `shared/config/CorrelationIdFilter.java` | Modify | D8 |
| `db/migration/V15__make_audit_events_append_only.sql` | Create | D7 |
| `db/migration/V16__preserve_verification_approval_on_revoke.sql` | Create | D10 |
| `modules/identity/internal/model/VerificationRequest.java`, `…/repository/VerificationRequestRepository.java`, `…/service/VerificationServiceImpl.java`, `AdminVerificationRequestResponse.java` | Modify | D9, D10 |
| `modules/identity/internal/service/AuditRoleMapper.java` | Create | `UserRoles` → `AuditActorRole`, `VerificationType` → `AuditVerificationType` |
| tests (below) | Create/Modify | |
| `docs/changelog.md`, `docs/openapi` contract if checked in | Modify | |

---

### Task 1: Append-only audit table (V15)

- [x] **Step 1: Failing tests** in `AuditImmutabilityMigrationTest` (`CaseMigrationTest` harness): insert a row, then assert `UPDATE`, `DELETE` and `TRUNCATE audit_events` each throw. Assert `INSERT` still works, and that deleting the actor user is still blocked by the FK.
- [x] **Step 2: Migration**

  ```sql
  -- audit_events is strictly append-only (CT-020 D7). There is no correction path: a
  -- mistake is answered by a new event. The table owner can still drop these triggers;
  -- separating the migration and runtime roles is CT-038/039.
  CREATE FUNCTION audit_events_reject_change() RETURNS trigger
      LANGUAGE plpgsql AS
  $$
  BEGIN
      RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP
          USING ERRCODE = 'restrict_violation';
  END;
  $$;

  CREATE TRIGGER audit_events_no_update_delete
      BEFORE UPDATE OR DELETE ON audit_events
      FOR EACH ROW EXECUTE FUNCTION audit_events_reject_change();

  CREATE TRIGGER audit_events_no_truncate
      BEFORE TRUNCATE ON audit_events
      FOR EACH STATEMENT EXECUTE FUNCTION audit_events_reject_change();
  ```

  Check first whether any existing test cleans up with `TRUNCATE`/`DELETE FROM audit_events`; such tests must switch to per-test transactions or fresh containers.
- [x] **Step 3:** Run it, expect a pass. **Commit** `feat(audit): make audit events append-only in the database [CT-020]`

### Task 2: Vocabulary, actor and metadata (pure Java)

- [x] **Step 1: Failing unit tests**
  - `AuditActionTest`: codes unique, match the V14 regex, ≤ 64 chars; every constant has a target and a schema; target type codes match the regex and ≤ 32 chars.
  - `AuditActorTest`: `system()` has no id; `user(0, …)`/`user(-1, …)` are rejected; `user(id, null)` is rejected.
  - `AuditMetadataTest`: for each value type, accept a valid value and reject invalid ones (negative `ID`/`COUNT`, wrong enum class, `String`, nested `Map`/`List`, `null`). Unknown key and missing required key are rejected. The error message contains the key and **not** the value (assert with a sentinel like `"secret-value"`). Mutating the source map after building has no effect. `metadata.action()` returns the action it was built for. The 4 KB check is tested on the serializer directly with a fixture payload, because no real schema can reach that size.
- [x] **Step 2: Implement.** `AuditMetadata` is built via `AuditMetadata.of(action).put("userId", 7L).put(...).build()`, validated against `action.schema()` at `build()`, and stores an unmodifiable `LinkedHashMap<String, Object>` (enum stored by `name()`). `AuditMetadata.none(action)` is used for empty schemas.
- [x] **Step 3: Commit** `feat(audit): define the audit action vocabulary and metadata schemas [CT-020]`

### Task 3: Recorder, entity and enforcement

- [x] **Step 1:** Add `spring-boot-starter-aspectj` to `pom.xml` (version managed by the Boot BOM).
- [x] **Step 2: Failing integration tests** in `AuditRecorderTest` (Testcontainers; a test-only `@Service` in the test package with `@Transactional` + `@AuditedOperation` methods to drive scenarios):
  - `recordsEventInCallersTransaction`: the row persists with the correct code, target type, actor, metadata JSON and clock time, and is re-read through a fresh `JdbcTemplate` after commit.
  - `failsWithoutTransaction`: calling `record` with no active transaction throws (`MANDATORY`).
  - `rejectsReadOnlyTransaction`.
  - `recorderFailureRollsBackProtectedChange`: the test service writes a row to a scratch table, then `record` fails at the database (actor id that doesn't exist in `users` → FK violation). Neither row exists after.
  - `metadataForAnotherActionIsRejected` (D2b): `record(actor, VERIFICATION_APPROVED, id, AuditMetadata.none(USER_DEACTIVATED))` throws and rolls back.
  - `requiresNewHelperCannotDischargeOuterObligation` (D4c): an audited outer method calls an unannotated `REQUIRES_NEW` helper that records the outer action and then throws. The outer method catches and returns → `AuditMissingException`, outer scratch row absent.
  - `correctionEventReferencesOriginal` (D7b): `AUDIT_EVENT.CORRECTED` targeting an existing event id persists; the original row is untouched.
  - `callerFailureAfterRecordingRollsBackAudit`.
  - `caughtRecorderFailureInAuditedOperation`: an audited caller catches `AuditRecordingException` and returns normally. Expect `AuditMissingException` (the obligation was never discharged) and no rows.
  - `caughtRecorderFailureMarksRollbackOnly`: a transactional but **unaudited** driver catches the failure and returns. Expect `UnexpectedRollbackException` and no rows.
  - `auditedOperationWithoutRecordRollsBack`: method returns normally without calling `record` → `AuditMissingException`, scratch row absent.
  - `wrongActionDoesNotDischarge`: declares `CASE_APPROVED`, records `CASE_REJECTED` → fails.
  - `eachInvocationNeedsItsOwnEvent`: an outer audited operation calls an inner audited operation twice; only one inner records → fails.
  - `aspectRequiresTransaction`: audited method without `@Transactional` → fails closed.
  - `correlationIdCopiedOnlyWhenValid`: MDC `abc-123` stored; MDC `"<script>"` stored as NULL.
  - `logsOnlyAfterCommit`: the `audit_recorded` line appears on commit and is absent on rollback (`OutputCaptureExtension`, like `StructuredLoggingTest`).
  - `repositoryRefusesExistingEntity`: saving an `AuditEvent` with an id throws.
- [x] **Step 3: Implement.**
  - `internal/AuditEvent`: `@Entity @Immutable @Table("audit_events")`, all columns `updatable = false`, `metadata` mapped as `@JdbcTypeCode(SqlTypes.JSON) Map<String, Object>`, package-private factory.
  - `internal/AuditEventRepository extends Repository<AuditEvent, Long>` and a custom insert-only `AuditEventAppender` fragment: only `saveAndFlush`; the fragment uses `EntityManager.persist` plus `flush` and rejects a preassigned id before persistence (it does not use Spring Data's `save`/merge behavior).
  - `internal/JpaAuditRecorder implements AuditRecorder`: `@Transactional(propagation = MANDATORY)`. Checks `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`, rejects `metadata.action() != action` (D2b), builds the entity, `saveAndFlush` (wrapping `DataAccessException` in `AuditRecordingException`, message without metadata), calls `AuditObligations.discharge(action)`, registers the `afterCommit` log.
  - `internal/AuditObligations`: a stack of per-invocation frames bound with `TransactionSynchronizationManager.bindResource`, plus a registered `TransactionSynchronization` that unbinds on `suspend()`, rebinds on `resume()` and unbinds on completion (D4c). It records normal-return obligation violations for the `beforeCommit` backstop, and poisons the transaction after any Spring-managed savepoint rollback once tracking begins (D4d). `open(actions)` pushes a frame, `discharge(action)` marks the top frame, `close()` pops and returns any unmet actions.
  - `internal/AuditedOperationAspect`: `@Aspect @Component @Order(LOWEST_PRECEDENCE)`, `@Around("@annotation(op)")`. Fails if no actual transaction is active; opens a frame; proceeds; on normal return, throws `AuditMissingException(unmet)` if the frame is unmet; always pops in `finally`.
  - `internal/AuditConfig`: `@EnableTransactionManagement(order = 0)`.
  - Move/delete the old `shared/audit/AuditEvent.java`; add `package-info.java` with `@NamedInterface("audit")`.
- [x] **Step 4:** Extend `CrowdtraceModulesTest`: a module type referencing `shared.audit.internal` fails verification. Run `SchemaValidationTest` (the entity matches V14).
- [x] **Step 5: Commit** `feat(audit): record audit events transactionally and enforce them per operation [CT-020]`

### Task 4: Correlation id normalization

- [x] **Step 1: Failing tests** in the existing filter test (or a new `CorrelationIdFilterTest`): a valid header is echoed; a header that is too long, has spaces or has angle brackets is replaced by a UUID in both the MDC and the response header; a missing header still gets a UUID.
- [x] **Step 2: Implement** using the shared pattern constant `AuditRecorder.CORRELATION_ID_PATTERN` (or a `shared.config` constant that both use).
- [x] **Step 3: Commit** `fix(shared): replace invalid correlation ids before they reach logs or audit [CT-020]`

### Task 5: Preserve the approval on revoke (V16)

- [x] **Step 1: Failing tests**: a migration test checks that a `REVOKED` row requires `revoked_by` and `revoked_at`, and that a non-revoked row with **any one** revocation column set fails (each partial combination). In the existing `VerificationWorkflowTest`: approve by A with notes "ok", revoke by B with notes "fraud". The row keeps `reviewer = A`, `review_notes = "ok"`, and has `revoked_by = B`, `revocation_notes = "fraud"`. The admin response shows both. **Update the existing assertions** at `VerificationWorkflowTest` L402–414, which currently require revoke to overwrite the approver, and L553, which asserts the deleted `verification_decision` log line; that line becomes `audit_recorded`.
- [x] **Step 2: Migration**

  ```sql
  -- Revocation used to overwrite the approval (reviewer, notes, time). From here on it is
  -- recorded separately. Rows revoked before V16 lost their approver; the backfill copies
  -- what is left so the constraint holds, and that history cannot be recovered.
  ALTER TABLE verification_requests
      ADD COLUMN revoked_by       BIGINT REFERENCES users (id),
      ADD COLUMN revoked_at       TIMESTAMP WITHOUT TIME ZONE,
      ADD COLUMN revocation_notes VARCHAR(2000);

  UPDATE verification_requests
     SET revoked_by = reviewer_id, revoked_at = reviewed_at, revocation_notes = review_notes
   WHERE status = 'REVOKED';

  ALTER TABLE verification_requests
      ADD CONSTRAINT verification_requests_revocation_check CHECK (
          (status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
          OR (status <> 'REVOKED' AND revoked_by IS NULL AND revoked_at IS NULL
              AND revocation_notes IS NULL));
  ```

  Check first that the V1/V9 status vocabulary spells `REVOKED`, and that `reviewed_at` is non-null on revoked rows. If not, backfill `revoked_at` with `COALESCE(reviewed_at, created_at)`.
- [x] **Step 3: Implement**: add a guarded `applyRevocation(id, revokedBy, notes, at)` query (`WHERE status = 'APPROVED'`). `revoke` uses it instead of `applyDecision`. Add the three entity fields and the two response fields (`revokedAt`, `revocationNotes`). Update the OpenAPI contract test if it pins the response shape.
- [x] **Step 4: Commit** `feat(identity): keep the approval when a verification is revoked [CT-020]`

### Task 6: Audit verification decisions (first consumer)

- [x] **Step 1: Failing tests** in a new `VerificationAuditTest` (Testcontainers, real commits, re-read via `JdbcTemplate`):
  - approve / reject / revoke / grant each write exactly one event with the right code, `VERIFICATION_REQUEST` target id, actor id and role (`MODERATOR` vs `SUPER_ADMIN`), and `userId` + `verificationType` metadata.
  - `approvalAttributionSurvivesRevocation`: two events, two different actors, both present after revoke.
  - `rejectedTransitionWritesNoEvent`: deciding a non-pending request → 409 and zero events.
  - `concurrentDecisionLoserWritesNoEvent`: two approvals race; one event.
  - `auditFailureRollsBackRequestAndBadge`: replace `AuditRecorder` with a failing `@MockitoBean` that throws `AuditRecordingException`. After approve, the request is still `PENDING` and `users.badge_type` is unchanged. Same for grant: no request row, no badge.
  - `noEventNoDecision`: a test-profile `AuditRecorder` that silently does nothing → `AuditMissingException`, request unchanged (proves D4 on the real service).
- [x] **Step 2: Implement**: annotate `approve`, `reject`, `revoke`, `grant` in `VerificationServiceImpl` with `@AuditedOperation(VERIFICATION_…)`. In `decide`/`grant`, after the badge sync, call `auditRecorder.record(AuditRoleMapper.actor(actor), action, requestId, metadata)`. Delete `logDecision` (D12 replaces it). Use the injected `Clock` for `reviewedAt` while there if `Clock` is already available in identity; otherwise leave it.
- [x] **Step 3:** Run the identity and auth test suites: CT-010 behaviour must be unchanged apart from the audit rows and the revoke fields. Covered by the final clean full-suite run below.
- [x] **Step 4: Commit** the four operations separately: `e169432` approval, `fbfe885` rejection, `003ba1d` revocation, and `13e6a88` grant.

### Task 7: Docs and handoffs

- [x] Changelog (Unreleased): CT-020 records the central transactional recorder, closed schema-validated metadata, per-operation enforcement, database-enforced append-only events, normalized correlation IDs, and audited verification decisions. The admin response and OpenAPI contract include the additive revocation fields; `spring-boot-starter-aspectj` is added.
- [x] `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q clean test` passes: 103 reports, 794 tests, 0 failures, 0 errors, 0 skipped.
- [x] **Commit** `docs(audit): record CT-020 decisions [CT-020]`. Copy the handoffs into the PR body.

### Execution notes

- Tasks 1–6 implementation commits are present. The four verification operations (approve, reject, revoke, grant) were wired in four separate commits.
- The recorder API remains in the named `shared.audit` interface; persistence and its custom insert-only appender remain internal. The appender uses `persist` and `flush`, then the recorder returns the generated event id. It avoids Spring Data `save`/`merge` semantics.
- Metadata is action-bound and closed-schema validated. Actor and event time are explicit; the recorder uses the injected UTC `Clock`. Logging occurs after commit and includes only safe identifiers.
- Obligations are transaction-bound per invocation and isolated across transaction suspend/resume. A normal frame close saves missing-action violations so `beforeCommit` can reject the transaction if an audited helper's `AuditMissingException` is swallowed without its own transaction advice. A caught recorder exception is handled independently by the `MANDATORY` transaction interceptor's rollback-only behavior.
- Any Spring-managed savepoint rollback after audit tracking starts rejects the outer transaction before commit, even if no audited frame is open at rollback time. This prevents a flushed event from being rolled back while its obligation remains discharged. Raw JDBC savepoints are unsupported because they bypass Spring synchronization callbacks. Production Hibernate currently does not expose savepoints through the configured dialect; the test-only dialect harness exercises Spring's callbacks against real PostgreSQL.
- V16 cannot recover the original reviewer for historical revoked rows: the prior revoke had overwritten that attribution. The migration backfills the surviving values into revocation fields; rows with a NULL reviewer fail migration rather than fabricating an actor. The admin contract adds `revokedAt` and `revocationNotes` and its contract test matches the response.
- CT-020 implementation commits: `6b97fd8` append-only migration; `af0008f` vocabulary and metadata; `3b813b8` recorder and enforcement; `4cfc986` correlation ID normalization; `d05bec2` preserve approval on revoke; `e169432` audit approval; `fbfe885` audit rejection; `003ba1d` audit revocation; `13e6a88` audit grant; `d504bb1` align migration tests with V16 revocation attribution.
- Final verification: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -q clean test` passed with exit code 0: 103 reports, 794 tests, 0 failures, 0 errors, and 0 skipped. The log is `/tmp/ct020-full-verification-final.log`.

## Handoffs

- **Every consumer (CT-017/018/019/023/032/033/034):** annotate the protected public service method with `@AuditedOperation`, call `record` inside the same transaction after the state change, and write a required-event test, a rollback test and a "rejected transition writes no event" test like Task 6. Adjust your action's metadata schema here if needed. Never add a string action or a free-text key.
- **CT-017:** `CASE.APPROVED/REJECTED` metadata references the `case_reviews` id; notes stay on the review row.
- **CT-018:** takedown reason and closing statement text live on the case, not in audit.
- **CT-023:** record `CASE_FILE.PRIVATE_ACCESS_GRANTED` and commit before returning the signed URL.
- **CT-032/033:** S3 deletion can't roll back with Postgres. Retention needs durable intent, retry and completion semantics; audit records the committed field purge as `SYSTEM` with a run correlation id.
- **CT-035:** add a read-only audit query projection in `shared.audit` (filters by target, actor, action and date on the D8 indexes of CT-016). Never expose the repository.
- **CT-038/039:** split the DB migration role from the runtime role; grant the runtime role only `INSERT, SELECT` on `audit_events` (D7a).
