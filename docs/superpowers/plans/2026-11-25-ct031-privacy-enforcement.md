# CT-031 Privacy Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit moderator reads of sensitive case data, keep emails and provider errors out of logs, prove every real route's access boundary, and give counsel a policy evidence index.

**Architecture:** Targeted fixes in place, no code moves to `modules/privacy`. The review detail splits into a snapshot reader bean (unchanged read-only REPEATABLE READ) and an auditing outer method in its own write transaction. Log lines drop recipients and exception messages. A new test enumerates every handler and checks it against a hand-written policy table.

**Tech Stack:** Spring Boot 4.1, Spring Security method security, MockMvc, JUnit 5, AssertJ, `OutputCaptureExtension`, embedded PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-11-25-ct031-privacy-enforcement-design.md`

## Global Constraints

- Audit is append-only; never update/delete audit rows. Audit metadata never carries sensitive values.
- Never edit an existing assertion to make it pass. Existing tests may only change call signatures/setup forced by Task 1's signature change.
- Each new test must fail against the pre-change code (or a deliberate temporary mutation), then pass.
- No retries, sleeps or skips.
- Out of scope: owner-read auditing, central log masking, upload-before-consent, moving code into `modules/privacy`.

## Review Focus

1. Unknown case id on review detail must write **no** audit row (404 first) → Task 1 test `anUnknownCaseWritesNoAudit`.
2. Audit failure must prevent disclosure (no detail returned) → Task 1 test `anAuditFailureReturnsNoDetail`.
3. A provider error whose body echoes the recipient/OTP must not reach logs → Task 2 failure-path sentinel test.
4. A newly added controller route with no policy entry must fail the build → Task 3 `everyRouteHasAPolicyEntry` (both directions).
5. A moderator/admin body route missing its `@Requires*` annotation would be hidden by a 400 from validation → Task 3 reflection check `everyProtectedRouteDeclaresItsRole`.

---

### Task 1: Audit moderator review-detail reads

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/audit/AuditAction.java` (add constant after `CASE_PHOTO_ADDED`)
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/governance/internal/ReviewDetailSnapshot.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/governance/internal/ReviewServiceImpl.java` (`getCaseDetail`, ~lines 69-95)
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/governance/ReviewService.java:27`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/governance/ReviewController.java:86-88`
- Modify (call sites only): `src/test/java/com/souldealers/crowdtracebackend/modules/governance/ReviewDetailTest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/governance/ReviewDetailAuditTest.java`

**Interfaces:**
- Produces: `AuditAction.CASE_SENSITIVE_DETAILS_VIEWED("CASE.SENSITIVE_DETAILS_VIEWED", AuditTargetType.CASE, empty())`
- Produces: `ReviewService.getCaseDetail(String actorEmail, Long caseId): ReviewCaseDetailResponse`
- Produces: `ReviewDetailSnapshot.read(Long caseId): ReviewCaseDetailResponse` (package-private bean, governance internal)

- [ ] **Step 1: Write the failing tests** in `ReviewDetailAuditTest extends ReviewDecisionTestSupport`:

```java
@Test @WithMockUser(authorities = "MODERATOR")
void eachDetailLoadAppendsOneSensitiveViewEventWithNoMetadata() {
    String email = email("viewer"); long moderator = actor(email, UserRoles.MODERATOR);
    CaseRecord c = pendingCaseWithReport();
    reviewService.getCaseDetail(email, c.getId());
    reviewService.getCaseDetail(email, c.getId());
    assertThat(jdbc.queryForList("SELECT actor_id, actor_role, metadata::text FROM audit_events "
            + "WHERE action = 'CASE.SENSITIVE_DETAILS_VIEWED' AND target_type = 'CASE' AND target_id = ?", c.getId()))
        .hasSize(2).allSatisfy(row -> {
            assertThat(row.get("actor_id")).isEqualTo(moderator);
            assertThat(row.get("actor_role")).isEqualTo("MODERATOR");
            assertThat(row.get("metadata")).isEqualTo("{}");
        });
}

@Test @WithMockUser(authorities = "MODERATOR")
void anUnknownCaseWritesNoAudit() { /* getCaseDetail(email, 999_999L) throws NotFoundException;
    count of CASE.SENSITIVE_DETAILS_VIEWED rows with target_id 999_999 is 0 */ }

@Test @WithMockUser(authorities = "MODERATOR")
void anAuditFailureReturnsNoDetail() { /* make AuditRecorder throw (follow ReviewAuditFailureTest's
    mechanism); getCaseDetail throws; no CASE.SENSITIVE_DETAILS_VIEWED row */ }

@Test
void getCaseDetailIsAnAuditedOperation() { /* reflection on ReviewServiceImpl.getCaseDetail(String, Long):
    @AuditedOperation value == CASE_SENSITIVE_DETAILS_VIEWED (pattern: ReviewAuditTest) */ }
```

Column names: check `audit_events` in `V14`/`V15` migrations and use the real ones.

- [ ] **Step 2: Run** `./mvnw test -Dtest=ReviewDetailAuditTest` → FAIL to compile (no 2-arg `getCaseDetail`, no constant).

- [ ] **Step 3: Implement.**
  - Add the `AuditAction` constant. No migration: no DB check on action codes (confirm with `grep -r "CASE.TAKEN_DOWN" src/main/resources` → no hits).
  - Move the existing snapshot body and its javadoc (the `SET TRANSACTION ... READ ONLY` work, `@Transactional(readOnly = true, propagation = REQUIRES_NEW)`) unchanged into `ReviewDetailSnapshot.read(Long caseId)`. It must be a separate bean so the proxy applies `REQUIRES_NEW`.
  - `ReviewServiceImpl.getCaseDetail(String actorEmail, Long caseId)`: `@Transactional(propagation = REQUIRES_NEW)`, `@RequiresModerator`, `@AuditedOperation(AuditAction.CASE_SENSITIVE_DETAILS_VIEWED)`. Order: `snapshot.read(caseId)` **first** (so a 404 writes nothing, and the outer transaction has not taken a connection yet), then `actorLookup.byEmail`, then `auditRecorder.record(auditActor(actor), CASE_SENSITIVE_DETAILS_VIEWED, caseId, AuditMetadata.of(...).build())`, then return. Reuse the existing actor→`AuditActor` mapping in `ReviewServiceImpl`. `REQUIRES_NEW` on the outer keeps the existing "caller transaction stays writable at its own isolation" tests true and commits the audit before the controller serialises.
  - Controller: `detail(Authentication authentication, @PathVariable Long id)` passes `authentication.getName()`.
  - `ReviewDetailTest`: change call sites to `getCaseDetail(moderatorEmail, id)`. For the SUPER_ADMIN test, create the actor with `actor(email("detail-admin"), UserRoles.SUPER_ADMIN)` and pass that email. No assertion changes.

- [ ] **Step 4: Run** `./mvnw test -Dtest='Review*Test'` → all PASS (including the snapshot/isolation tests in `ReviewDetailTest`).

- [ ] **Step 5: Mutation check.** Temporarily move `auditRecorder.record` before `snapshot.read` → `anUnknownCaseWritesNoAudit` fails. Revert.

- [ ] **Step 6: Commit** `feat(governance): audit every moderator read of sensitive case details [CT-031]`

---

### Task 2: Keep emails and provider errors out of logs

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/ResendNotificationService.java:38,48,70,72`
- Create: `src/test/java/com/souldealers/crowdtracebackend/shared/ResendNotificationLoggingTest.java`

**Interfaces:** none new.

- [ ] **Step 1: Write the failing tests** with `@ExtendWith(OutputCaptureExtension.class)`. Build `ResendNotificationService` directly (template engine as in `ResendNotificationTemplateTest`; set `resendUrl`, `resendApiKey`, `fromEmail` with `ReflectionTestUtils`) against a local `HttpServer` (pattern: `ResendEmailServiceTest.serve`). Sentinels: email `sentinel.reporter@leak.test`, OTP `918273`, text `SENTINEL-MEDICAL-ASTHMA`.

```java
@Test void successLogsNoRecipientOrOtp(CapturedOutput out)
// server 200; sendOtpEmail(EMAIL, OTP, "Ama", OtpType.CREATE); sendWelcomeEmail(EMAIL, "Ama")
// assertThat(out.getAll()).doesNotContain(EMAIL, OTP).contains("OTP email sent").contains("Welcome email sent");

@Test void providerFailureLogsNoRecipientOrProviderBody(CapturedOutput out)
// server 500 with body containing EMAIL, OTP and MEDICAL
// sendOtpEmail(...) does not throw
// assertThat(out.getAll()).doesNotContain(EMAIL, OTP, MEDICAL).contains("Email delivery failed");
```

Call the `@Async` methods directly on the plain instance (no proxy), so they run synchronously.

- [ ] **Step 2: Run** `./mvnw test -Dtest=ResendNotificationLoggingTest` → both FAIL (email present in output).

- [ ] **Step 3: Implement.** Replace the four lines:
  - `log.info("OTP email sent (type={})", type)`
  - `log.info("Welcome email sent")`
  - `log.debug("Email sent")`
  - `log.error("Email delivery failed (exceptionType={})", e.getClass().getSimpleName())`

  Success lines currently log even when `sendEmail` swallowed a failure. Keep that behaviour; changing delivery semantics is out of scope.

- [ ] **Step 4: Run** the test → PASS. Run `grep -rnE 'log\.(trace|debug|info|warn|error)\(.*(email|Email|to\b|otp|token|password)' src/main/java`. Each hit must log only ids, types or counts. List any remaining hit in the commit body (or fix it in this task if it logs a value).

- [ ] **Step 5: Commit** `fix(notification): stop logging recipient emails and provider error text [CT-031]`

---

### Task 3: Real-route authorization matrix

**Files:**
- Create: `src/test/java/com/souldealers/crowdtracebackend/RouteAuthorizationMatrixTest.java`

**Interfaces:**
- Consumes: `RequestMappingHandlerMapping` bean; `SecurityConfig.publicEndpoints`; annotations `RequiresRegisteredUser`, `RequiresModerator`, `RequiresSuperAdmin` (`modules/identity`).

- [ ] **Step 1: Write the policy table** as a `Map<String, Access>` keyed `"METHOD /path/{pattern}"`, with `enum Access { PUBLIC, REGISTERED, MODERATOR, SUPER_ADMIN }`. Fill it from the product spec's role rules (`docs/product-spec.md` roles section), **not** by copying annotations. Where the spec and the code disagree, stop and report; don't pick one. Only include handlers whose bean class is under `com.souldealers.crowdtracebackend` (excludes springdoc and actuator).

- [ ] **Step 2: Write the tests** (`@SpringBootTest @AutoConfigureMockMvc`, embedded Postgres as in `PublicApiLeakTest`):
  - `everyRouteHasAPolicyEntry`: the enumerated keys equal the table keys exactly (`containsExactlyInAnyOrderElementsOf`), so both unlisted routes and stale entries fail.
  - `everyProtectedRouteDeclaresItsRole`: for each non-PUBLIC entry, `AnnotatedElementUtils.hasAnnotation` on the handler method or its bean class finds the matching `Requires*` annotation. This is the check validation can't mask. If a route enforces its role only at the service layer, stop and report it.
  - `guestsGet401OnEveryProtectedRoute`: for each non-PUBLIC entry, a request with path variables `= 1` and no token returns 401. The filter chain answers before body binding.
  - `lowerRolesGet403OnBodylessElevatedRoutes`: for GET/DELETE entries at MODERATOR, a REGISTERED token returns 403; at SUPER_ADMIN, REGISTERED and MODERATOR tokens return 403. Issue tokens the way `AuthorizationMatrixTest` does.
  - `everyPublicRouteIsCoveredBySecurityConfig`: each PUBLIC entry's path matches one of `SecurityConfig.publicEndpoints` (`AntPathMatcher`).

- [ ] **Step 3: Run** `./mvnw test -Dtest=RouteAuthorizationMatrixTest` → PASS. If any assertion fails on current code, that's a finding: report it before changing production code.

- [ ] **Step 4: Mutation check.** Temporarily remove `@RequiresModerator` from `ReviewController.detail` (and its class-level annotation, if any) → `everyProtectedRouteDeclaresItsRole` fails. Add a dummy `@GetMapping` to any controller → `everyRouteHasAPolicyEntry` fails. Revert both.

- [ ] **Step 5: Commit** `test(security): authorization matrix over every real route [CT-031]`

---

### Task 4: Reporter projection allowlist

Consent intake is already covered: `accepted=false`, `accepted=null`, and an outdated version are rejected with no rows written (`CaseSubmissionEndpointTest:52,62`, `CaseSubmissionServiceTest:187,223`, `CaseSubmissionRequestValidationTest`). The public projection is covered by `PublicApiLeakTest`, the admin one by `ReviewDetailTest` and `ReviewProjectionTest`. Only the reporter view lacks an exact-field check.

**Files:**
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/casefile/internal/ReporterCaseProjectionTest.java`

- [ ] **Step 1: Write the tests** using the fixtures in `CaseSubmissionEndpointTest`:
  - `ownerSeesExactlyTheReporterFields`: `GET /api/user/cases/{id}` as owner. Top-level `data` property names equal the `ReporterCaseResponse` record components, and `data.sensitiveDetails` names equal the `SensitiveDetailsResponse` components. Assert the JSON has no `reporterEmail`, `reporterId` or storage key at any depth (reuse CT-024's recursive walker if it's reachable, otherwise a key search).
  - `anotherUserGets404`: a second registered user gets 404 on the same id, with no sensitive sentinel in the body.
- [ ] **Step 2: Run** → PASS. Mutation: temporarily add `c.getReporterId()` to the reporter mapper → test fails. Revert.
- [ ] **Step 3: Commit** `test(casefile): pin the reporter case projection [CT-031]`

---

### Task 5: Policy evidence index for counsel

**Files:**
- Create: `docs/privacy/policy-references.md`
- Modify: `docs/TASKS_INDEX.md` (tick CT-031)

- [ ] **Step 1: Write the index.** One table row per rule: rule, where it's enforced (file:line), the test that proves it, and the open question for counsel. Rules:
  - consent version (`crowdtrace.consent.sensitive-data-version`, default `v1`), mapped to its notice text, which counsel has not supplied yet
  - public/reporter/admin projections
  - route access (Task 3)
  - private-report access audit (`CASE_FILE.PRIVATE_ACCESS_GRANTED`)
  - sensitive-detail view audit (`CASE.SENSITIVE_DETAILS_VIEWED`)
  - log rules (Task 2)
  - retention (CT-032, pending)
  - the known gap: report files are uploaded before consent is captured (product-spec flow, steps 4 → 5). Orphaned uploads are removed by the phase-6 stale-upload sweep.

  Link product-spec §7.2 and open question 6. State that the index is input for review, not a legal conclusion.
- [ ] **Step 2: Run** `./mvnw test` → all PASS.
- [ ] **Step 3: Commit** `docs(privacy): policy evidence index for legal review [CT-031]`
