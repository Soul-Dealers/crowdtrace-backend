# Auth Path Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the validation, enumeration, session-invalidation and credential-storage gaps found in the CT-007 auth review, without changing the stateless-JWT architecture.

**Architecture:** The existing model — short-lived signed JWTs, per-request account-status lookup, SHA-256 token denylist for explicit logout — is kept as-is. This plan fixes the edges around it: request validation that was never wired up, four endpoints that leak account existence inconsistently, a parallel Basic-auth path that bypasses revocation, OTP codes stored recoverably, and password resets that leave old sessions alive. Four migrations are added (`V4`–`V7`).

**Tech Stack:** Java 21 (`pom.xml` `<java.version>21</java.version>`) / Spring Boot 4.1.0, Spring Security, Spring Data JPA, Flyway, jjwt 0.11.5, JUnit 5 + AssertJ + MockMvc, PostgreSQL 16, Testcontainers 2.0.5 (Boot-managed test dependency).

**Spec:** The CT-007 auth code review recorded in the originating conversation, as revised by `docs/superpowers/plans/2026-09-24-auth-hardening-review.md` (the Codex peer review). Findings are referenced by their review numbers (e.g. "review item 1"). Note: `docs/pr-ct-007-password-authentication.md` was referenced by an earlier draft of this plan and is no longer present in the working tree.

## Revision Note (peer review incorporated)

This plan was revised after peer review. Changes from the first draft, all of them verified against the source before being accepted:

| Change | Why |
|---|---|
| OTP storage moved from plain SHA-256 to **HMAC-SHA-256 with a server-side secret** | A 6-digit OTP has 10^6 possible values; an unkeyed digest is exhaustively reversible in seconds, so plain hashing does not defend against the database-leak threat it was added for. |
| `AuthServiceImplTest` added to the OTP task | Verified: it stubs `generateOtp` returning `Otp` (lines 75-76, 99-100) and asserts `isOtpValid`/`invalidateOtp` are never called (lines 128-129, 151-152). Omitting it breaks compilation. |
| `consumeOtp` made `@Transactional` | Its repository finder carries `@Lock(PESSIMISTIC_WRITE)`, which needs an active transaction. Ownership belongs at the service boundary, not in each caller. |
| Canonical-path task moved **before** the OpenAPI task | Verified: `OpenApiContractTest` lines 41-49 and 111 pin `/users`. Reordering means the OpenAPI contract is updated once, against final paths. |
| Canonical-path test rewritten | The original asserted 404 on `POST /login`. Wrong: `anyRequest().authenticated()` returns 401 before handler mapping runs. It now asserts against `RequestMappingHandlerMapping` directly, which is deterministic. |
| `credentials_changed_at` replaced with **`credentials_version`** | The timestamp design deliberately failed open for a sub-second window because JWT `iat` has one-second precision. A monotonic integer is exact and has no clock, timezone or ordering edge. |
| Token revocation moved to `INSERT ... ON CONFLICT DO NOTHING` | Catching `DataIntegrityViolationException` is brittle inside an outer transaction — the insert may not flush until commit, and the transaction can already be marked rollback-only. |
| `correlationId` added to the security entry point, **plus filter reordering** | Additional finding: `CorrelationIdFilter` is an unordered `@Component`, so it registers at `LOWEST_PRECEDENCE` and runs *after* Spring Security's `FilterChainProxy` (order -100). MDC is empty inside an entry point until the filter is reordered. |
| New task for `users.deleted_at` schema drift | Additional finding: `User.deletedAt` (line 63) maps to a `deleted_at` column that **no migration creates**. `IdentityMigrationTest` confirms it — the migrated schema has 8 columns and `deleted_at` is not among them. Dev and prod both run `ddl-auto: validate`, so a database built from migrations alone fails Hibernate validation at startup. It only passes today because the test profile uses `create-drop`. |
| `IdentityMigrationTest` and `OpenApiContractTest` updates made explicit | Both were conditional ("if it enumerates…"). Verified: `IdentityMigrationTest` uses `containsExactlyInAnyOrder` on the `users` columns, so every new column is a required edit. |
| Task 1 dump-deletion step removed | Verified: `crowdtrace-backup-*.sql` is no longer in the working tree. The ignore rule is still worth having. |
| Token-revocation integration test moved from H2 to **PostgreSQL 16 via Testcontainers** | H2 2.4.240 rejects PostgreSQL's `INSERT ... ON CONFLICT DO NOTHING` even in PostgreSQL compatibility mode. The test must execute the production SQL dialect and concurrency semantics. |
| OTP secret parsing and minimum strength made explicit | `otp.hmac-secret` is Base64-decoded and rejected at startup unless it contains at least 32 bytes. A raw `@Value String` would silently accept an empty or weak key. |
| OTP domain-separation test made deterministic | Consuming a code for an email with no OTP row does not prove the email participates in the HMAC. The revised test applies the same fixed code to two emails and two purposes and compares the actual digests. |
| Credential-version enforcement made fail-closed | An unexpected `UserDetails` implementation and a signed legacy token without `cv` are both rejected rather than bypassing session invalidation. |
| JWT failure logging made data-safe | The filter logs a stable exception category, not `exception.getMessage()`, which may contain account identifiers or parser details. |

Peer-review points **not** adopted, with reasons, are listed under Deferred Items.

## Global Constraints

- **Dev only.** No production deployment exists. Do not add, rename or remove keys in `src/main/resources/application-prod.yaml`. New configuration keys go in `application-dev.yaml` and `application-test.yaml`, and are listed under Deferred Items as prod prerequisites.
- **Rate limiting is out of scope** for this plan (review item 5). Do not add Bucket4j, an `attempts` column, or any throttling filter. See Deferred Items — this is the next required security ticket.
- **Test harness:** every new **HTTP-facing** Spring integration test uses this harness verbatim: `@SpringBootTest(properties = "cors.allowed-origins=http://localhost")`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@MockitoBean private NotificationService notificationService;`. Pure unit tests do not use it. Context-only schema tests omit MockMvc because success is the context booting. Database-dialect tests use the same Spring context but may override the datasource with a PostgreSQL Testcontainer and omit MockMvc when no HTTP request is made.
- Assertions use AssertJ (`org.assertj.core.api.Assertions.assertThat`) and MockMvc matchers. Match the style in `PasswordResetFlowTest`.
- All user-facing strings go in `com.souldealers.crowdtracebackend.shared.CustomMessages`. Never inline a message literal in a service.
- Email normalization is always `normalizeEmail(...)` — `trim().toLowerCase(Locale.ROOT)`. No endpoint may skip it.
- Migrations are strictly sequential: `V4` (deleted_at), `V5` (OTP keying), `V6` (otp index), `V7` (credentials_version). Do not renumber.
- Run the **full** suite (`./mvnw test`) at the end of every task, not just the new test. Baseline before starting: **93 tests, 0 failures, 0 errors.**
- Commit at the end of every task. Conventional commit format. End each message with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

## Review Focus

Input classes the fixes imply but that no task's happy path exercises. Each has a test assigned to the task that owns the code.

1. **Mixed-case email on `/resend-otp`** — today it 404s because normalization is skipped; after the fix it must behave identically to lowercase. → Task 3, `resendOtpNormalizesEmailBeforeLookup`.
2. **The same OTP digits under another email or purpose** — domain separation must change the stored HMAC, and a CREATE OTP must be rejected by RESET. → Task 9, `theSameCodeIsSeparatedByEmailAndPurpose` and `otpMintedForCreateIsRejectedForReset`.
3. **Two callers revoking the same token concurrently on PostgreSQL** — the native upsert must be idempotent under the production dialect rather than merely accepted by H2. → Task 11, `concurrentRevocationOfTheSameTokenIsIdempotent`.
4. **Preflight on an authenticated path** — CORS works for `permitAll` paths today purely by accident of path matching; the authenticated ones are the failing case. → Task 7, `preflightOnAuthenticatedEndpointSucceeds`.
5. **A full reset-then-login round trip, including a legacy token with no `cv` claim** — the invalidation check must kill the old token, reject pre-version tokens, and accept the one the user earns by logging in again. → Task 12, `legacyTokenWithoutCredentialsVersionIsRejected` and `resetThenLoginEndToEnd`.

---

## Task 1: Repo hygiene — stop the database dump from being committed

**Files:**
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Standalone.

- [ ] **Step 1: Append ignore rules**

Add to the end of `.gitignore`:

```gitignore
### macOS ###
.DS_Store
**/.DS_Store

### Local database dumps ###
*.sql.gz
crowdtrace-backup-*.sql
```

Note: this deliberately does **not** ignore `*.sql`, because `src/main/resources/db/migration/*.sql` must stay tracked.

- [ ] **Step 2: Verify the dump and .DS_Store files are now ignored**

Run: `git status --porcelain`
Expected: `.DS_Store`, `src/.DS_Store` and `src/main/.DS_Store` no longer appear as untracked. Verified: `crowdtrace-backup-*.sql` is no longer in the working tree, so the rule is preventative only.

- [ ] **Step 3: Confirm migrations are still tracked**

Run: `git check-ignore -v src/main/resources/db/migration/V1__create_identity_tables.sql`
Expected: exit code 1, no output (the file is NOT ignored).

- [ ] **Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore: ignore local database dumps and macOS metadata

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Wire up request validation on signup, login and resend-otp

Review item 1. Every constraint on `SignUpRequest` is currently dead — `@Email`, `@NotBlank`, `@Length(min = 8)` are never evaluated because the controller omits `@Valid`.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java:44,50,66`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpRequest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/SignUpValidationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new. `ResendOtpRequest` gains constraints but keeps its `(String email, String type)` shape, which Task 3 depends on.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/SignUpValidationTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignUpValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void rejectsPasswordShorterThanEightCharacters() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"short-password@example.com\","
                                + "\"password\":\"a\",\"displayName\":\"Short Password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").exists());

        assertThat(userRepository.findByEmail("short-password@example.com")).isEmpty();
    }

    @Test
    void rejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\","
                                + "\"password\":\"averylongpassword\",\"displayName\":\"Bad Email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void rejectsBlankLoginPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void rejectsResendOtpWithoutEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"create\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SignUpValidationTest`
Expected: FAIL. `rejectsPasswordShorterThanEightCharacters` returns 200 and persists the user; the others return 200 or 500 instead of 400.

- [ ] **Step 3: Add `@Valid` to the three handlers**

In `AuthController.java`, change these three signatures (the `jakarta.validation.Valid` import is already present):

```java
    @PostMapping("/signup")
    public ApiResponse<GenericResponseMessage> signUpUser(@Valid @RequestBody SignUpRequest request){
```

```java
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request){
```

```java
    @PostMapping("/resend-otp")
    public GenericResponseMessage resendOtp (@Valid @RequestBody ResendOtpRequest request){
```

- [ ] **Step 4: Add constraints to `ResendOtpRequest`**

Replace the whole file:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request object for resending OTP")
public record ResendOtpRequest(
        @NotBlank
        @Email
        String email,

        @Schema(description = "Type of OTP request. Can be either 'create' or 'reset'",
                allowableValues = {"create", "reset"}
        )
        String type
) {
}
```

`type` stays optional — Task 3 defaults it to `CREATE` when absent.

- [ ] **Step 5: Run the new test**

Run: `./mvnw test -Dtest=SignUpValidationTest`
Expected: PASS, all four tests.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS. If a pre-existing test posts a signup or login body that now fails validation, fix the *test payload*, not the constraint.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpRequest.java \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/SignUpValidationTest.java
git commit -m "fix(identity): enforce request validation on signup, login and resend-otp

Bean Validation constraints on SignUpRequest were never evaluated because
the handlers omitted @Valid. A one-character password registered successfully.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

---

## Task 3: Fix the three defects in `resendOtp`

Review item 6. `resendOtp` 404s on unknown email (enumeration oracle), skips `normalizeEmail`, and hardcodes `OtpType.CREATE` while silently ignoring the documented `type` field. It currently has **zero** test coverage.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java:128-134`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpTest.java`

**Interfaces:**
- Consumes: `ResendOtpRequest(String email, String type)` from Task 2, now `@Valid`-enforced.
- Produces: `private OtpType resolveOtpType(String type)` in `AuthServiceImpl` — returns `OtpType.CREATE` for null/blank, parses `create`/`reset` case-insensitively, throws `ValidationException` otherwise.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.TOKEN_SENT_MSG;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResendOtpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void unknownEmailReturnsTheSameGenericMessageAsAKnownOne() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-here@example.com\",\"type\":\"create\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        verify(notificationService, never())
                .sendOtpEmail(anyString(), anyString(), anyString(), eq(OtpType.CREATE));
    }

    @Test
    void resendOtpNormalizesEmailBeforeLookup() throws Exception {
        User user = saveUser("resend-normalize@example.com", "Resend Normalize");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  RESEND-NORMALIZE@EXAMPLE.COM  \",\"type\":\"create\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(TOKEN_SENT_MSG));

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.CREATE));
    }

    @Test
    void honoursResetTypeInsteadOfAlwaysSendingCreate() throws Exception {
        User user = saveUser("resend-reset@example.com", "Resend Reset");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-reset@example.com\",\"type\":\"reset\"}"))
                .andExpect(status().isOk());

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.RESET));
    }

    @Test
    void defaultsToCreateWhenTypeIsOmitted() throws Exception {
        User user = saveUser("resend-default@example.com", "Resend Default");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-default@example.com\"}"))
                .andExpect(status().isOk());

        verify(notificationService).sendOtpEmail(
                eq(user.getEmail()), anyString(), eq(user.getDisplayName()), eq(OtpType.CREATE));
    }

    @Test
    void rejectsUnsupportedType() throws Exception {
        saveUser("resend-bad-type@example.com", "Resend Bad Type");

        mockMvc.perform(post("/api/v1/auth/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"resend-bad-type@example.com\",\"type\":\"banana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private User saveUser(String email, String displayName) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName(displayName)
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(UserStatus.PENDING_VERIFICATION)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ResendOtpTest`
Expected: FAIL. `unknownEmailReturnsTheSameGenericMessageAsAKnownOne` gets 404, `resendOtpNormalizesEmailBeforeLookup` gets 404, `honoursResetTypeInsteadOfAlwaysSendingCreate` sends `CREATE`.

- [ ] **Step 3: Rewrite `resendOtp` and add `resolveOtpType`**

In `AuthServiceImpl.java`, replace the `resendOtp` method:

```java
    @Override
    public GenericResponseMessage resendOtp(ResendOtpRequest request) {
        String email = normalizeEmail(request.email());
        OtpType type = resolveOtpType(request.type());

        userRepository.findByEmail(email)
                .ifPresent(user -> generateAndSendOtp(user, type));

        return new GenericResponseMessage(TOKEN_SENT_MSG);
    }
```

Add this private helper next to `normalizeEmail`:

```java
    private OtpType resolveOtpType(String type) {
        if (type == null || type.isBlank()) {
            return OtpType.CREATE;
        }

        try {
            return OtpType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(UNSUPPORTED_OTP_TYPE_MSG);
        }
    }
```

- [ ] **Step 4: Add the message constant**

In `CustomMessages.java`, add:

```java
    public static final String UNSUPPORTED_OTP_TYPE_MSG = "Unsupported OTP type";
```

`NotFoundException` may now be unused in `AuthServiceImpl` — leave the import if `findUserByEmail`/`findUserByEmailForUpdate` still use it (they do at this point in the plan; Task 4 changes that).

- [ ] **Step 5: Run the test**

Run: `./mvnw test -Dtest=ResendOtpTest`
Expected: PASS, all five tests.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
        src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpTest.java
git commit -m "fix(identity): make resend-otp non-enumerable, normalized and type-aware

resendOtp 404'd on unknown emails, skipped email normalization, and ignored
the documented type field so a reset code could never be resent.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

---

## Task 4: Make account-existence leaks consistent across the OTP flow

Review item 7. `resetPasswordRequest` is carefully generic while `resetPassword` 404s and `verifyOtp` 409s with "This email is already registered". The careful endpoint buys nothing while its siblings leak.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java:104-125,147-163`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java:128` (the `status().isConflict()` assertion)
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AccountEnumerationTest.java`

**Interfaces:**
- Consumes: `normalizeEmail`, `findUserByEmailForUpdate` from `AuthServiceImpl`.
- Produces: nothing new. `verifyOtp` and `resetPassword` now throw `ValidationException(OTP_VERIFICATION_FAILED_MSG)` → HTTP 400 for *every* failure mode: unknown email, wrong code, expired code, wrong account state.

**Design note:** an unknown email and a wrong code become indistinguishable — both 400 with the same body. This is deliberate. An attacker learns nothing; a real user who typo'd their email sees "Could not verify your OTP", which is accurate.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AccountEnumerationTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountEnumerationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void verifyOtpLooksIdenticalForUnknownAndKnownEmails() throws Exception {
        saveUser("enum-known@example.com", UserStatus.PENDING_VERIFICATION);

        MvcResult known = postVerify("enum-known@example.com");
        MvcResult unknown = postVerify("enum-unknown@example.com");

        assertThat(known.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(known)).isEqualTo(bodyWithoutInstance(unknown));
    }

    @Test
    void verifyOtpOnAnAlreadyActiveAccountLooksLikeAnyOtherFailure() throws Exception {
        saveUser("enum-active@example.com", UserStatus.ACTIVE);

        MvcResult active = postVerify("enum-active@example.com");
        MvcResult unknown = postVerify("enum-absent@example.com");

        assertThat(active.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(active)).isEqualTo(bodyWithoutInstance(unknown));
    }

    @Test
    void resetPasswordLooksIdenticalForUnknownAndKnownEmails() throws Exception {
        saveUser("enum-reset@example.com", UserStatus.ACTIVE);

        MvcResult known = postReset("enum-reset@example.com");
        MvcResult unknown = postReset("enum-reset-absent@example.com");

        assertThat(known.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyWithoutInstance(known)).isEqualTo(bodyWithoutInstance(unknown));
    }

    private MvcResult postVerify(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
    }

    private MvcResult postReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"000000\","
                                + "\"password\":\"a-new-long-password\","
                                + "\"confirmPassword\":\"a-new-long-password\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
    }

    /** correlationId and instance differ per request; everything else must match. */
    private String bodyWithoutInstance(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("\"correlationId\":\"[^\"]*\"", "")
                .replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    private User saveUser(String email, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName("Enumeration Probe")
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AccountEnumerationTest`
Expected: FAIL. Unknown emails return 404, the already-ACTIVE account returns 409.

- [ ] **Step 3: Make `verifyOtp` uniformly generic**

In `AuthServiceImpl.java`, replace the body of `verifyOtp` down to the `consumeOtp` check:

```java
    @Override
    @Transactional
    public GenericResponseMessage verifyOtp (VerifyOtpDto request){
        if (request == null) {
            throw new ValidationException(EMAIL_NOT_NULL_MSG);
        }

        String email = normalizeEmail(request.email());

        User user = userRepository.findByEmailForUpdate(email)
                .filter(candidate -> candidate.getAccountStatus() == UserStatus.PENDING_VERIFICATION)
                .orElseThrow(() -> new ValidationException(OTP_VERIFICATION_FAILED_MSG));

        if (!otpService.consumeOtp(request.code(), email, OtpType.CREATE)) {
            throw new ValidationException(OTP_VERIFICATION_FAILED_MSG);
        }

        user.setAccountStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        sendWelcomeEmailAfterCommit(user.getEmail(), user.getDisplayName());

        return new GenericResponseMessage(VERIFICATION_SUCCESS_MSG);
    }
```

This also replaces the hardcoded `"Could not verify this OTP"` literal with the existing `OTP_VERIFICATION_FAILED_MSG` constant.

- [ ] **Step 4: Make `resetPassword` uniformly generic**

Replace the user lookup inside `resetPassword`:

```java
        String email = normalizeEmail(request.email());

        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ValidationException(OTP_VERIFICATION_FAILED_MSG));
```

- [ ] **Step 5: Update the existing conflict assertion**

`AuthenticationLifecycleTest.java:128` asserts `status().isConflict()` for the already-verified case. Change that expectation to `status().isBadRequest()`. Read the surrounding test first — if it also asserts on the `EXISTING_EMAIL` message, remove that assertion too.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -Dtest=AccountEnumerationTest+AuthenticationLifecycleTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS. `EXISTING_EMAIL` in `CustomMessages` may now be unused — leave the constant; `signUp` semantics may want it later.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/com/souldealers/crowdtracebackend/modules/identity \
           src/test/java/com/souldealers/crowdtracebackend/modules/identity
git commit -m "fix(identity): return identical responses for unknown and known accounts

verify-otp and reset-password leaked account existence via 404/409 while
request-password-reset was careful. All OTP failures now return the same 400.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

---

## Task 5: Collapse the root-path endpoint aliases

Review item "root aliases". `AuthController` is mapped `@RequestMapping({"", "/api/v1/auth"})`, so `/login`, `/signup`, `/reset-password`, `/verify-otp`, `/resend-otp` and `/users` all exist at the root as well. But `SecurityConfig.publicEndpoints` lists only the `/api/v1/auth/*` spellings, so `POST /login` is routable, reachable, and returns 401 forever. The alias works for the one authenticated endpoint and is silently broken for all six public ones.

**This task runs before the OpenAPI task on purpose.** springdoc generates paths from controller mappings, so the OpenAPI document currently advertises `/users`. Collapsing the aliases first means the contract test is edited once, against final paths, instead of twice.

**Decision:** Option B — one canonical URL per endpoint. Both this plan's author and the peer review independently recommended it, and it is the fixed scope of this plan. The alternative (adding all six root spellings to `publicEndpoints`) doubles a hand-maintained security allowlist to preserve a surface that is already broken for six of seven endpoints.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java:18`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthorizationMatrixTest.java:42-43` and every `USERS_PATH` usage
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java:41-49,111`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/CanonicalPathTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `/api/v1/auth/**` is the single canonical prefix for every `AuthController` endpoint. Later tasks assume no root aliases exist.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/CanonicalPathTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CanonicalPathTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private NotificationService notificationService;

    /**
     * Asserted against the handler mapping rather than over HTTP on purpose.
     * An unauthenticated request to a removed path still returns 401, because
     * anyRequest().authenticated() matches before handler mapping ever runs —
     * so an HTTP-level 404 assertion would fail for a reason unrelated to the
     * change. The mapping table is the direct evidence.
     */
    @Test
    void everyAuthEndpointIsMappedExactlyOnceUnderTheCanonicalPrefix() {
        Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(patterns).contains(
                "/api/v1/auth/login",
                "/api/v1/auth/signup",
                "/api/v1/auth/verify-otp",
                "/api/v1/auth/resend-otp",
                "/api/v1/auth/request-password-reset",
                "/api/v1/auth/reset-password",
                "/api/v1/auth/users");

        assertThat(patterns).doesNotContain(
                "/login", "/signup", "/verify-otp", "/resend-otp",
                "/request-password-reset", "/reset-password", "/users");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=CanonicalPathTest`
Expected: FAIL — the `doesNotContain` assertion reports `/login`, `/signup`, `/users` and the rest still mapped.

- [ ] **Step 3: Collapse the mapping**

In `AuthController.java:18`:

```java
@RequestMapping("/api/v1/auth")
```

`UserController`'s `{"/api/auth", "/api/v1/auth"}` is left alone — `/api/auth` is a distinct prefix, not a root alias, and nothing in this plan depends on it. It is listed under Deferred Items.

- [ ] **Step 4: Update `AuthorizationMatrixTest`**

Delete the `USERS_PATH` constant (`= "/users"`) and every `mockMvc.perform(...)` call that uses it, keeping only the `USERS_ALIAS_PATH` assertions. Then rename `USERS_ALIAS_PATH` to `USERS_PATH`:

```java
    private static final String USERS_PATH = "/api/v1/auth/users";
```

Affected blocks: `rejectsUserListingForNonSuperAdmins` (two perform calls → one) and `allowsUserListingForSuperAdmin` (two → one). Scan the rest of the file for further `USERS_PATH` uses and collapse them the same way.

- [ ] **Step 5: Update `OpenApiContractTest`**

Change every `$.paths['/users']` JSON path to `$.paths['/api/v1/auth/users']` — lines 41, 42, 43, 45, 46, 47 and 49.

At line 111, change the runtime probe:

```java
        mockMvc.perform(get("/api/v1/auth/users"))
                .andExpect(status().isUnauthorized());
```

Note this assertion would have kept passing unchanged (a removed path still 401s pre-handler), but it would be testing a path that no longer exists. Fix it anyway.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -Dtest=CanonicalPathTest+AuthorizationMatrixTest+OpenApiContractTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A src/main src/test
git commit -m "refactor(identity): collapse root-path endpoint aliases

Root aliases worked for /users but were unreachable for every public auth
endpoint, because the allowlist only carried the /api/v1/auth spellings.
One canonical prefix removes the mismatch and halves the allowlist surface.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Remove HTTP Basic auth, install a real 401 entry point, move OpenAPI to bearer

Review item 4. A user who logs out — token hash written to `revoked_tokens` — can still reach every protected endpoint with `Authorization: Basic <email:password>`. Basic auth goes through the same `DaoAuthenticationProvider` and involves no token, so there is nothing to revoke.

**⚠ Removing `httpBasic` also removes the API's 401 entry point.** `httpBasic(Customizer.withDefaults())` is the only thing installing a `BasicAuthenticationEntryPoint`. With nothing configured, `ExceptionHandlingConfigurer` falls back to `Http403ForbiddenEntryPoint`, so **every** filter-chain rejection across the whole API flips from 401 to 403. `GlobalExceptionHandler` cannot catch these — they are thrown in the filter chain, not in a controller. Verified blast radius: **10 `isUnauthorized()` assertions across 6 test files** (`OpenApiContractTest`, `GlobalExceptionHandlerTest`, `CurrentUserProfileTest`, `AuthenticationLifecycleTest`, `AuthorizationMatrixTest`, `InactiveAccountAuthenticationTest`).

**⚠ The entry point cannot see the correlation ID until `CorrelationIdFilter` is reordered.** It is an unordered `@Component OncePerRequestFilter`, so Spring Boot registers it at `LOWEST_PRECEDENCE` — *after* `FilterChainProxy` (order `-100`). Controller-thrown errors get a correlation ID because the filter wraps the dispatcher; filter-chain errors do not, because they never reach it. Step 4 fixes the ordering.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java:52`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/ProblemDetailAuthenticationEntryPoint.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/config/CorrelationIdFilter.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java` (`addSecurityItem` + `addSecuritySchemes`)
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java:36`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java:39,40,45,89`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/BasicAuthDisabledTest.java`

**Interfaces:**
- Consumes: `JwtService`, `UserRepository`, `CustomMessages.UNAUTHORIZED_MSG`, `CorrelationIdFilter.CORRELATION_ID_MDC_KEY`.
- Produces: `ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint`, registered on the chain. OpenAPI security scheme renamed `basicAuth` → `bearerAuth` (type `http`, scheme `bearer`, bearerFormat `JWT`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/BasicAuthDisabledTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.UNAUTHORIZED_MSG;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BasicAuthDisabledTest {

    private static final String PASSWORD = "a-very-long-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void validCredentialsOverHttpBasicAreRejected() throws Exception {
        String email = "basic-auth@example.com";
        userRepository.save(User.builder()
                .email(email)
                .displayName("Basic Auth Probe")
                .role(UserRoles.SUPER_ADMIN)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .accountStatus(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        String credentials = Base64.getEncoder()
                .encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Basic " + credentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestsGetAProblemDetailWithACorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value(UNAUTHORIZED_MSG))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void theCorrelationIdInTheBodyMatchesTheRequestHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Correlation-ID", "probe-correlation-id"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlationId").value("probe-correlation-id"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BasicAuthDisabledTest`
Expected: FAIL. `validCredentialsOverHttpBasicAreRejected` returns 200 (proving Basic auth bypasses the bearer path), and the ProblemDetail tests fail because the current entry point writes an empty body.

- [ ] **Step 3: Remove `httpBasic` from the filter chain**

In `SecurityConfig.java`:

```java
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicEndpoints)
                        .permitAll()
                        .anyRequest().authenticated());
                http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
                http.authenticationProvider(authenticationProvider);
                http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```

Keep the `org.springframework.security.config.Customizer` import — Task 7 uses it for `http.cors(...)`.

Keep the `AuthenticationProvider` bean: `AuthServiceImpl.login` still calls `authenticationManager.authenticate(...)` to verify passwords at the `/login` endpoint. Removing `httpBasic` only stops credentials being accepted on *every* request.

- [ ] **Step 4: Reorder `CorrelationIdFilter` ahead of Spring Security**

In `CorrelationIdFilter.java`, add the annotation and import:

```java
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
```

Without this the filter registers at `LOWEST_PRECEDENCE`, which is after `FilterChainProxy` (`SecurityProperties.DEFAULT_FILTER_ORDER = -100`), and the MDC is empty for anything the security chain rejects. Moving it first also means the `X-Correlation-ID` response header is set on 401s and 403s, which it is not today.

- [ ] **Step 5: Create the entry point**

Create `src/main/java/com/souldealers/crowdtracebackend/modules/identity/ProblemDetailAuthenticationEntryPoint.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souldealers.crowdtracebackend.shared.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

import static com.souldealers.crowdtracebackend.shared.CustomMessages.UNAUTHORIZED_MSG;

/**
 * Rejections raised inside the security filter chain never reach
 * GlobalExceptionHandler, which only sees exceptions thrown from controllers.
 * Without an explicit entry point Spring Security falls back to
 * Http403ForbiddenEntryPoint, turning every unauthenticated request into a 403
 * with an empty body. This preserves both the 401 and the ProblemDetail shape,
 * correlation ID included, so clients see one error contract across the API.
 */
@Component
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MSG);
        problem.setType(URI.create("urn:crowdtrace:problem:unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "UNAUTHORIZED");
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
```

Register it in `SecurityConfig` — add the field next to `jwtFilter`:

```java
    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
```

and add to the chain:

```java
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint));
```

This deliberately does **not** set an `accessDeniedHandler`: authenticated-but-unauthorized requests must keep returning 403, which `AuthorizationMatrixTest` pins.

- [ ] **Step 6: Confirm the 401 contract still holds everywhere**

Run: `./mvnw test -Dtest=CurrentUserProfileTest+InactiveAccountAuthenticationTest+AuthorizationMatrixTest+GlobalExceptionHandlerTest`
Expected: PASS. If any flip to 403, the entry point is not wired — fix that, do not edit the assertions.

- [ ] **Step 7: Switch the OpenAPI document to bearer**

In `OpenApiConfig.java`, replace the `addSecurityItem`/`addSecuritySchemes` pair inside `openAPI()`:

```java
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .schemas(reusableSchemas()))
```

- [ ] **Step 8: Update the controller annotation**

In `AuthController.java:36`, on `getUsers`:

```java
    @SecurityRequirement(name = "bearerAuth")
```

- [ ] **Step 9: Update the contract test**

In `OpenApiContractTest.java`:

- line 39: `$.components.securitySchemes.basicAuth.type` → `$.components.securitySchemes.bearerAuth.type` (value stays `"http"`)
- line 40: `$.components.securitySchemes.basicAuth.scheme` → `$.components.securitySchemes.bearerAuth.scheme`, value `"basic"` → `"bearer"`
- line 45: `$.paths['/api/v1/auth/users'].get.security[0].basicAuth` → `...security[0].bearerAuth` (the path was already updated in Task 5)
- line 89: `$.security[0].basicAuth` → `$.security[0].bearerAuth`, and update the comment on line 88 to say "document-level bearerAuth requirement"

Add one assertion after line 40:

```java
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
```

- [ ] **Step 10: Run the tests**

Run: `./mvnw test -Dtest=BasicAuthDisabledTest+OpenApiContractTest+AuthorizationMatrixTest`
Expected: PASS.

- [ ] **Step 11: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A src/main src/test
git commit -m "fix(identity): remove HTTP Basic auth so logout actually revokes access

Basic auth accepted credentials on every request, bypassing the JWT denylist
entirely: a logged-out user kept full access. Adds an explicit 401 entry point
so filter-chain rejections keep the ProblemDetail contract, and reorders
CorrelationIdFilter ahead of the security chain so those errors carry a
correlation ID. OpenAPI now documents bearer.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Register CORS with the security filter chain

Review item 8. `CorsConfig` is a `WebMvcConfigurer`, which registers CORS at the MVC layer. The security filter chain runs *before* the dispatcher servlet and never calls `http.cors(...)`, so a preflight `OPTIONS` on any authenticated path hits `anyRequest().authenticated()` and returns 401 with no CORS headers. The browser then blocks the real request. Public paths work only by accident of path matching.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/config/CorsConfig.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/CorsPreflightTest.java`

**Interfaces:**
- Consumes: the `cors.allowed-origins` property (already set to `http://localhost` by every test's `@SpringBootTest(properties = ...)`).
- Produces: a `CorsConfigurationSource` bean named `corsConfigurationSource`, which `http.cors(Customizer.withDefaults())` discovers by name.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/CorsPreflightTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsPreflightTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void preflightOnAuthenticatedEndpointSucceeds() throws Exception {
        mockMvc.perform(options("/api/v1/auth/me")
                        .header("Origin", "http://localhost")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightOnPublicEndpointSucceeds() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost"));
    }

    @Test
    void preflightFromDisallowedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/me")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=CorsPreflightTest`
Expected: FAIL. `preflightOnAuthenticatedEndpointSucceeds` returns 401 with no `Access-Control-Allow-Origin` header.

- [ ] **Step 3: Expose a `CorsConfigurationSource` from `CorsConfig`**

Replace `src/main/java/com/souldealers/crowdtracebackend/shared/config/CorsConfig.java` entirely:

```java
package com.souldealers.crowdtracebackend.shared.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConfigurationProperties(prefix = "cors")
public class CorsConfig implements WebMvcConfigurer {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private static final long MAX_AGE_SECONDS = 3600L;

    private List<String> allowedOrigins = new ArrayList<>();

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods(ALLOWED_METHODS.toArray(new String[0]))
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(MAX_AGE_SECONDS);
    }

    /**
     * The security filter chain runs before the dispatcher servlet, so
     * {@link #addCorsMappings} alone leaves preflight requests to authenticated
     * paths being rejected with 401 before MVC ever sees them. This bean is what
     * {@code http.cors(...)} picks up.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

`allowedOriginPatterns` (not `allowedOrigins`) is required because `application-prod.yaml` declares `https://*.crowdtrace.com`; the non-pattern setter rejects wildcards when `allowCredentials` is true.

- [ ] **Step 4: Enable CORS on the security chain**

In `SecurityConfig.java`, add the `cors` call immediately after the `csrf` line:

```java
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(Customizer.withDefaults());
```

- [ ] **Step 5: Run the test**

Run: `./mvnw test -Dtest=CorsPreflightTest`
Expected: PASS, all three tests.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/config/CorsConfig.java \
        src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java \
        src/test/java/com/souldealers/crowdtracebackend/modules/identity/CorsPreflightTest.java
git commit -m "fix(cors): register CORS with the security filter chain

WebMvcConfigurer CORS never reaches Spring Security, so preflight requests to
authenticated paths were rejected with 401 before MVC saw them.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

---

## Task 8: Close the `users.deleted_at` schema drift

**Additional finding, not in the original review.** `User.java:63` declares `private LocalDateTime deletedAt;` with no `@Column` override, so Hibernate maps it to a `deleted_at` column. **No migration creates that column** — `V1__create_identity_tables.sql` creates eight columns and `deleted_at` is not among them, which `IdentityMigrationTest:31-33` confirms with `containsExactlyInAnyOrder`.

Both `application-dev.yaml:13` and `application-prod.yaml:13` set `ddl-auto: validate`. A database built from migrations alone therefore **fails Hibernate schema validation at startup**. It passes today only because `application-test.yaml:9` uses `create-drop`, where Hibernate generates the schema from the entities and the column exists by definition.

This lands before the OTP and credential migrations so `V4` fixes the pre-existing gap and later migrations build on a correct schema.

**Files:**
- Create: `src/main/resources/db/migration/V4__add_users_deleted_at.sql`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/IdentityMigrationTest.java:31-33`

**Interfaces:**
- Consumes: nothing.
- Produces: `users.deleted_at` exists in the migrated schema. Task 12 adds one more column to the same `containsExactlyInAnyOrder` list.

- [ ] **Step 1: Write the failing test**

In `IdentityMigrationTest.java`, add `"deleted_at"` to the `users` column expectation:

```java
        assertThat(columnsFor("users")).containsExactlyInAnyOrder(
                "id", "email", "password_hash", "display_name", "role", "account_status",
                "created_at", "updated_at", "deleted_at");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=IdentityMigrationTest`
Expected: FAIL — the migrated schema has no `deleted_at`, so the actual list is one element short.

This test runs with `spring.flyway.enabled=true` and `ddl-auto=none`, which is exactly why it catches drift the rest of the suite hides.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V4__add_users_deleted_at.sql`:

```sql
-- User.deletedAt has always been mapped by Hibernate but was never created by
-- a migration. Dev and prod both run ddl-auto: validate, so a database built
-- from migrations alone fails schema validation at startup. The test profile
-- hid this because it uses create-drop.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP WITHOUT TIME ZONE;
```

Nullable with no default: a null `deleted_at` means "not deleted", which is what the entity already assumes.

- [ ] **Step 4: Run the test**

Run: `./mvnw test -Dtest=IdentityMigrationTest`
Expected: PASS.

- [ ] **Step 5: Prove validation now passes against a migrated schema**

Create `src/test/java/com/souldealers/crowdtracebackend/SchemaValidationTest.java`:

This is the explicit context-only exception to the HTTP integration-test harness in Global Constraints: it deliberately omits `@AutoConfigureMockMvc` and `NotificationService` because its only assertion is that the application context can build the Flyway-migrated schema with Hibernate validation enabled.

```java
package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots with the Flyway-migrated schema and ddl-auto: validate, the way dev and
 * prod run. Any future entity field without a matching migration fails here
 * instead of at deploy time.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
class SchemaValidationTest {

    @Test
    void entityMappingsMatchTheMigratedSchema() {
        // Success is the context starting. Hibernate validation runs at startup
        // and fails the context if any mapped column is missing.
    }
}
```

Run: `./mvnw test -Dtest=SchemaValidationTest`
Expected: PASS. If it fails, another entity has the same drift — fix it in this task and note it in the commit message.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "fix(identity): create the users.deleted_at column the entity maps

User.deletedAt was mapped by Hibernate but created by no migration, so a
database built from migrations alone failed ddl-auto: validate at startup.
Adds a schema validation test that boots the migrated schema the way dev does.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Key OTP codes with HMAC-SHA-256

Review item 9, as corrected by peer review. `Otp.code` is stored in plaintext while `TokenRevocationServiceImpl` hashes its token — same module, opposite posture.

**Plain SHA-256 is not sufficient here and the first draft of this plan got it wrong.** A six-digit decimal OTP has only 10^6 possible values. An attacker holding the table can precompute every digest in under a second and recover every live code, so an unkeyed hash defends against casual inspection and nothing else. This task uses **HMAC-SHA-256 with a server-side secret held outside the database**, with the normalized email and OTP purpose bound into the authenticated input for domain separation. A leaked table without the secret yields nothing.

The secret is **dedicated** — deliberately not the JWT signing key, so that rotating one does not silently invalidate the other.

This is the largest task: it changes the `OtpService` contract, adds validated key configuration and a migration, and updates every test that observes an OTP.

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/config/OtpProperties.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java` (`generateAndSendOtp`)
- Modify: `src/main/resources/application-dev.yaml`, `src/main/resources/application-test.yaml`
- Create: `src/main/resources/db/migration/V5__key_otp_codes.sql`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImplTest.java:75-76,99-100,128-129,151-152`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImplTest.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/PasswordResetFlowTest.java:87`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java:106,177,186`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpKeyingTest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/config/OtpPropertiesTest.java`

**Interfaces:**
- Consumes: `OtpType`, `OtpRepository`, the new Base64-encoded `otp.hmac-secret` property. Startup fails unless it decodes to at least 32 bytes.
- Produces: **`String OtpService.generateOtp(String email, OtpType type)`** — signature changed from returning `Otp` to returning the **plaintext** code. The persisted `Otp.code` holds `HMAC-SHA256(secret, email + "|" + type + "|" + code)` as 64 hex characters. **`boolean OtpService.consumeOtp(String otpCode, String email, OtpType type)`** keeps its signature, is now `@Transactional`, and keys its argument before comparing. `isOtpValid` and `invalidateOtp` are **removed** — they have no production callers.

**Migration decision:** the dev database's existing `otp` rows hold plaintext that will never match a keyed digest. `V5` **truncates** the table. Correct in dev (no production deployment exists, per Global Constraints); any in-flight code is simply re-requested. It also drops `idx_otp_code`, useless once codes are keyed and never queried by value.

**Transaction ownership:** `consumeOtp` becomes `@Transactional` so its `@Lock(PESSIMISTIC_WRITE)` finder is enforced at the service boundary rather than depending on every caller having opened a transaction. `verifyOtp` and `resetPassword` are already `@Transactional`, so the lock joins the outer transaction and the semantics are unchanged; direct callers (including tests) now get correct locking for free.

- [ ] **Step 1: Add the secret to the dev and test profiles**

In `src/main/resources/application-dev.yaml`, next to the existing `jwt:` block:

```yaml
otp:
  hmac-secret: ${OTP_HMAC_SECRET:ZGV2LW90cC1obWFjLXNlY3JldC1kby1ub3QtdXNlLWluLXByb2Q=}
```

In `src/main/resources/application-test.yaml`:

```yaml
otp:
  hmac-secret: dGVzdC1vdHAtaG1hYy1zZWNyZXQtZm9yLWF1dG9tYXRlZC10ZXN0cw==
```

Per Global Constraints, `application-prod.yaml` is **not** touched. Setting `OTP_HMAC_SECRET` before the first deploy is recorded under Deferred Items.

Both values are Base64 and decode to more than 32 bytes. The implementation must decode them before constructing the HMAC key; it must not use the Base64 text itself as key material.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpKeyingTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpKeyingTest {

    @Autowired
    private OtpServiceImpl otpService;

    @Autowired
    private OtpRepository otpRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void generateOtpReturnsPlaintextButPersistsADigest() {
        String email = "keyed@example.com";

        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(plainCode).matches("[0-9]{6}");

        Otp stored = latest(email, OtpType.CREATE);
        assertThat(stored.getCode())
                .isNotEqualTo(plainCode)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    /**
     * The whole point of keying rather than plain hashing: an attacker holding
     * the table can precompute all one million unkeyed SHA-256 digests. The
     * stored value must NOT be one of them.
     */
    @Test
    void storedDigestIsNotAnUnkeyedHashOfTheCode() {
        String email = "keyed-not-plain@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(latest(email, OtpType.CREATE).getCode())
                .isNotEqualTo(sha256Hex(plainCode))
                .isNotEqualTo(sha256Hex(email + "|CREATE|" + plainCode));
    }

    @Test
    void theSameCodeIsSeparatedByEmailAndPurpose() {
        String fixedCode = "123456";
        String createForA = otpService.keyedDigest(
                "domain-a@example.com", OtpType.CREATE, fixedCode);

        assertThat(otpService.keyedDigest(
                "domain-b@example.com", OtpType.CREATE, fixedCode))
                .isNotEqualTo(createForA);
        assertThat(otpService.keyedDigest(
                "domain-a@example.com", OtpType.RESET, fixedCode))
                .isNotEqualTo(createForA);
    }

    @Test
    void consumeOtpAcceptsThePlaintextCodeExactlyOnce() {
        String email = "keyed-consume@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(otpService.consumeOtp(plainCode, email, OtpType.CREATE)).isTrue();
        assertThat(otpService.consumeOtp(plainCode, email, OtpType.CREATE))
                .as("a consumed code must not work twice")
                .isFalse();
    }

    @Test
    void consumeOtpRejectsTheStoredDigestSubmittedAsACode() {
        String email = "keyed-replay@example.com";
        otpService.generateOtp(email, OtpType.CREATE);

        String storedDigest = latest(email, OtpType.CREATE).getCode();

        assertThat(otpService.consumeOtp(storedDigest, email, OtpType.CREATE)).isFalse();
    }

    @Test
    void otpMintedForCreateIsRejectedForReset() {
        String email = "keyed-wrong-type@example.com";
        String plainCode = otpService.generateOtp(email, OtpType.CREATE);

        assertThat(otpService.consumeOtp(plainCode, email, OtpType.RESET)).isFalse();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Deliberately avoids findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc:
     * that finder carries @Lock(PESSIMISTIC_WRITE) and needs an active
     * transaction, which a plain @SpringBootTest method does not have.
     * PasswordResetFlowTest uses this same findAll() approach.
     */
    private Otp latest(String email, OtpType type) {
        return otpRepository.findAll().stream()
                .filter(otp -> email.equals(otp.getEmail()))
                .filter(otp -> otp.getType() == type)
                .max(Comparator.comparing(Otp::getCreatedAt))
                .orElseThrow();
    }
}
```

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/config/OtpPropertiesTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpPropertiesTest {

    @Test
    void rejectsMissingKeyAtStartupValidation() {
        OtpProperties properties = new OtpProperties();

        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp.hmac-secret");
    }

    @Test
    void rejectsMalformedBase64() {
        OtpProperties properties = new OtpProperties();

        assertThatThrownBy(() -> properties.setHmacSecret("not base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void rejectsKeysShorterThanThirtyTwoBytes() {
        OtpProperties properties = new OtpProperties();
        String shortKey = Base64.getEncoder().encodeToString(
                "too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> properties.setHmacSecret(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void returnsADefensiveCopyOfAValidKey() {
        OtpProperties properties = new OtpProperties();
        String encoded = Base64.getEncoder().encodeToString(new byte[32]);
        properties.setHmacSecret(encoded);

        byte[] first = properties.hmacKey();
        first[0] = 1;

        assertThat(properties.hmacKey()[0]).isZero();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=OtpKeyingTest+OtpPropertiesTest`
Expected: FAIL to compile — `OtpProperties` and `keyedDigest` do not exist yet, and `generateOtp` still returns `Otp`, not `String`.

- [ ] **Step 4: Change the `OtpService` contract**

Replace `src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.OtpType;

public interface OtpService {

    /**
     * Mints a new OTP for the given email and purpose.
     *
     * @return the <strong>plaintext</strong> code to send to the user. Only a
     *         keyed HMAC of it is persisted, so this is the single moment the
     *         plaintext exists — it cannot be recovered from the database.
     */
    String generateOtp(String email, OtpType type);

    boolean consumeOtp(String otpCode, String email, OtpType type);
}
```

- [ ] **Step 5: Validate the key configuration and implement HMAC keying**

Create `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/config/OtpProperties.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@ConfigurationProperties(prefix = "otp")
public class OtpProperties {

    private static final int MIN_KEY_BYTES = 32;

    private byte[] hmacKey;

    public void setHmacSecret(String encodedSecret) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "otp.hmac-secret must be valid Base64", exception);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "otp.hmac-secret must decode to at least 32 bytes");
        }

        this.hmacKey = decoded.clone();
    }

    @PostConstruct
    void requireConfigured() {
        if (hmacKey == null) {
            throw new IllegalStateException("otp.hmac-secret must be configured");
        }
    }

    public byte[] hmacKey() {
        if (hmacKey == null) {
            throw new IllegalStateException("otp.hmac-secret must be configured");
        }
        return hmacKey.clone();
    }
}
```

The binder calls `setHmacSecret`; malformed Base64 or fewer than 32 decoded bytes aborts startup. `@PostConstruct` catches a completely absent property. Returning a clone prevents callers from mutating the configured key in memory.

Replace `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.OtpService;
import com.souldealers.crowdtracebackend.modules.identity.internal.config.OtpProperties;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.shared.OtpType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private static final int OTP_LENGTH = 6;
    private static final int EXPIRY_MINUTES = 5;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository repository;
    private final OtpProperties otpProperties;

    @Override
    @Transactional
    public String generateOtp(String email, OtpType type) {
        String plainCode = generator();

        repository.save(Otp.builder()
                .code(keyedDigest(email, type, plainCode))
                .type(type)
                .expiredAt(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .email(email)
                .build());

        return plainCode;
    }

    /**
     * Consumes an OTP by comparing the keyed digest of the supplied code against
     * the newest stored digest for that email and purpose, then expiring it.
     *
     * <p>Transactional because the repository finder holds a PESSIMISTIC_WRITE
     * lock: owning the transaction here means the locking contract is enforced
     * at the service boundary rather than depending on each caller.
     *
     * @return {@code true} if the OTP was successfully consumed; {@code false} otherwise
     */
    @Override
    @Transactional
    public boolean consumeOtp(String otpCode, String email, OtpType type) {
        if (otpCode == null) {
            return false;
        }

        String candidate = keyedDigest(email, type, otpCode);

        return repository.findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc(email, type)
                .filter(otp -> constantTimeEquals(otp.getCode(), candidate))
                .filter(otp -> !isOtpExpired(otp))
                .map(otp -> {
                    otp.setExpiredAt(LocalDateTime.now());
                    repository.save(otp);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Binds the email and purpose into the authenticated input so a digest is
     * only ever valid for the identity and flow it was minted for. A six-digit
     * code has just 10^6 possible values, so an unkeyed hash would be trivially
     * reversible from a database leak; the secret is what makes this worthwhile.
     */
    String keyedDigest(String email, OtpType type, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(otpProperties.hmacKey(), HMAC_ALGORITHM));

            String message = email + "|" + type.name() + "|" + code;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("OTP keying is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String stored, String candidate) {
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isOtpExpired(Otp otp) {
        LocalDateTime expiredAt = otp.getExpiredAt();
        return expiredAt == null || !LocalDateTime.now().isBefore(expiredAt);
    }

    private String generator() {
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(RANDOM.nextInt(10));
        }
        return otp.toString();
    }
}
```

Note the switch from `@AllArgsConstructor` to `@RequiredArgsConstructor`: both the repository and validated `OtpProperties` are final constructor dependencies. The service never receives or interprets the encoded configuration string directly.

`keyedDigest` is package-private solely so `OtpKeyingTest` in the same internal package can apply a fixed code and verify the real HMAC function's domain separation. It is not exposed through `OtpService` or any controller.

- [ ] **Step 6: Update the one production caller**

In `AuthServiceImpl.java`:

```java
    private void generateAndSendOtp(User user, OtpType type){
        String code = otpService.generateOtp(user.getEmail(), type);
        notificationService.sendOtpEmail(user.getEmail(), code, user.getDisplayName(), type);
    }
```

(Task 11 revisits this method to move delivery after commit.)

- [ ] **Step 7: Write the migration**

Create `src/main/resources/db/migration/V5__key_otp_codes.sql`:

```sql
-- OTP codes are now stored as HMAC-SHA-256 digests keyed with a server-side
-- secret, with the email and purpose bound into the authenticated input.
-- Existing rows hold plaintext that can never match a digest, so they are
-- discarded; any in-flight code simply has to be re-requested. Safe here
-- because no production deployment exists.
TRUNCATE TABLE otp;

-- Codes are never looked up by value (only by email + type), and indexing a
-- credential digest serves no purpose.
DROP INDEX IF EXISTS idx_otp_code;
```

- [ ] **Step 8: Update `AuthServiceImplTest` (required — it will not compile otherwise)**

This is a pure Mockito unit test (`@ExtendWith(MockitoExtension.class)`), so the Spring harness rule does not apply.

At lines 75-76 and 99-100, the stub returns an `Otp`. Replace both occurrences:

```java
        when(otpService.generateOtp(eq(email), eq(OtpType.CREATE))).thenReturn("123456");
```

Delete both now-unused `Otp mockOtp = Otp.builder()...` declarations and remove `import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;`; no remaining test in this class uses `Otp`.

At lines 128-129 and 151-152, delete these assertions — the methods no longer exist:

```java
        verify(otpService, never()).isOtpValid(anyString(), anyString(), any(OtpType.class));
        verify(otpService, never()).invalidateOtp(anyString(), anyString(), any(OtpType.class));
```

Keep the `never`, `any`, and `anyString` imports: the remaining negative-path tests still use all three.

- [ ] **Step 9: Trim `OtpServiceImplTest`**

Delete every test for `isOtpValid` and `invalidateOtp` (`isOtpValid_MissingOtp_ReturnsFalse`, `isOtpValid_ExpiredOtp_ReturnsFalse`, `isOtpValid_CurrentOtp_ReturnsTrue`, `isOtpValid_OnlyNewestOtpForPurposeCanBeUsed`, `invalidateOtp_NonCurrentOtp_ThrowsConflictWithoutSaving`) and the now-unused `ConflictException` import.

Any surviving test constructs `OtpServiceImpl` directly. Build a valid `OtpProperties` and pass it explicitly in `setUp`:

```java
        OtpProperties properties = new OtpProperties();
        properties.setHmacSecret(Base64.getEncoder().encodeToString(new byte[32]));
        otpService = new OtpServiceImpl(repository, properties);
```

Add imports for `OtpProperties` and `java.util.Base64`. Do not use reflection to bypass the same key validation production relies on.

Hand-built `Otp` fixtures must store the **keyed digest**, not the plaintext. Rather than reproduce the HMAC in the test, mint the expected digest through the service itself or move the case to `OtpKeyingTest`. If the class ends up empty, delete it — `OtpKeyingTest` covers the behavior.

- [ ] **Step 10: Rewrite the tests that read codes from the database**

`PasswordResetFlowTest:87` and `AuthenticationLifecycleTest:106,177,186` obtain codes via `otp.getCode()`, which is now a digest. In each class add this helper and use it instead:

```java
    private String captureLatestOtpCode(String email, OtpType type) {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeastOnce()).sendOtpEmail(
                eq(email), codeCaptor.capture(), anyString(), eq(type));
        return codeCaptor.getValue();
    }
```

Required imports:

```java
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
```

`getValue()` returns the most recent capture, which is what the "only the newest OTP works" cases at `AuthenticationLifecycleTest:177,186` need. For the older-code case at line 177, use `codeCaptor.getAllValues().get(0)`.

- [ ] **Step 11: Run the OTP tests**

Run: `./mvnw test -Dtest=OtpKeyingTest+OtpPropertiesTest+OtpServiceImplTest+AuthServiceImplTest+PasswordResetFlowTest+AuthenticationLifecycleTest`
Expected: PASS.

- [ ] **Step 12: Run the full suite**

Run: `./mvnw test`
Expected: PASS. Flyway runs `V5` against the test database automatically. Verified in advance: `IdentityMigrationTest` asserts only on `idx_users_email`, `idx_users_role` and `idx_verification_requests_queue`, so dropping `idx_otp_code` needs no change there.

- [ ] **Step 13: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(identity): key OTP codes with HMAC-SHA-256

OTP codes sat in plaintext while revoked tokens were hashed in the same module.
Plain hashing would not have helped: a 6-digit code has 10^6 values and is
exhaustively reversible, so this uses a keyed HMAC with a dedicated server-side
secret, binding email and purpose into the authenticated input.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Purge expired OTPs and revoked tokens on a schedule

Review item 10. `revoked_tokens` grows forever — rows are dead weight the moment `expires_at` passes, since `isRevoked` already filters on it. `otp` has the same problem, and `OtpRepository.findAllExpiredOTP()` was written for this and never called.

Per peer review, deletion uses explicit `@Modifying` bulk queries rather than Spring Data derived deletes, which load every matching entity into the persistence context and remove them one at a time. An index on `otp.expired_at` mirrors the one `revoked_tokens` already has.

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/config/SchedulingConfig.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/ExpiredCredentialCleanupJob.java`
- Create: `src/main/resources/db/migration/V6__index_otp_expired_at.sql`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/RevokedTokenRepository.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/ExpiredCredentialCleanupJobTest.java`

**Interfaces:**
- Consumes: `RevokedTokenRepository`, `OtpRepository`, `TokenRevocationService.revoke(String, LocalDateTime)`.
- Produces: `ExpiredCredentialCleanupJob.purgeExpired()` — returns `void`, idempotent, safe to call directly from a test. `RevokedTokenRepository.deleteExpired(LocalDateTime) : int` and `OtpRepository.deleteExpired(LocalDateTime) : int`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/ExpiredCredentialCleanupJobTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.TokenRevocationService;
import com.souldealers.crowdtracebackend.modules.identity.internal.model.Otp;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import com.souldealers.crowdtracebackend.shared.OtpType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpiredCredentialCleanupJobTest {

    @Autowired
    private ExpiredCredentialCleanupJob cleanupJob;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @Autowired
    private OtpRepository otpRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void removesExpiredRowsAndKeepsLiveOnes() {
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        LocalDateTime future = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);

        tokenRevocationService.revoke("expired-token-value", past);
        tokenRevocationService.revoke("live-token-value", future);

        otpRepository.save(Otp.builder()
                .email("cleanup-expired@example.com")
                .type(OtpType.CREATE)
                .code("a".repeat(64))
                .expiredAt(LocalDateTime.now().minusDays(1))
                .build());
        otpRepository.save(Otp.builder()
                .email("cleanup-live@example.com")
                .type(OtpType.CREATE)
                .code("b".repeat(64))
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build());

        long revokedBefore = revokedTokenRepository.count();
        long otpBefore = otpRepository.count();

        cleanupJob.purgeExpired();

        assertThat(revokedTokenRepository.count()).isLessThan(revokedBefore);
        assertThat(otpRepository.count()).isLessThan(otpBefore);

        assertThat(tokenRevocationService.isRevoked("live-token-value"))
                .as("a token that has not expired must stay revoked")
                .isTrue();
        assertThat(emailsRemaining())
                .as("an unexpired OTP must survive the purge")
                .contains("cleanup-live@example.com")
                .doesNotContain("cleanup-expired@example.com");
    }

    @Test
    void purgingTwiceIsSafe() {
        cleanupJob.purgeExpired();
        cleanupJob.purgeExpired();
    }

    /**
     * findFirstByEmailAndTypeOrderByCreatedAtDescIdDesc carries
     * @Lock(PESSIMISTIC_WRITE) and requires an active transaction, which a
     * plain @SpringBootTest method does not have. Read through findAll().
     */
    private List<String> emailsRemaining() {
        return otpRepository.findAll().stream().map(Otp::getEmail).toList();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ExpiredCredentialCleanupJobTest`
Expected: FAIL to compile — `ExpiredCredentialCleanupJob` does not exist.

- [ ] **Step 3: Add bulk delete queries to both repositories**

In `RevokedTokenRepository.java`:

```java
    @Modifying
    @Query("delete from RevokedToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
```

with imports `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`.

In `OtpRepository.java`:

```java
    @Modifying
    @Query("delete from Otp o where o.expiredAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
```

with `import java.time.LocalDateTime;` and `org.springframework.data.repository.query.Param`. Also delete the now-unused `findAllExpiredOTP()` query and its `import java.util.List;` — the bulk delete replaces it.

A derived `deleteByExpiredAtBefore` would load every matching row into the persistence context and delete them individually. These issue one statement.

- [ ] **Step 4: Index the column the purge scans**

Create `src/main/resources/db/migration/V6__index_otp_expired_at.sql`:

```sql
-- The nightly purge scans otp by expiry, and consumeOtp compares against it.
-- revoked_tokens already has the equivalent index (idx_revoked_tokens_expires_at).
CREATE INDEX idx_otp_expired_at ON otp (expired_at);
```

- [ ] **Step 5: Create the job**

Create `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/ExpiredCredentialCleanupJob.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity.internal.service;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.OtpRepository;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Expired revoked tokens and consumed OTPs carry no security value — the
 * revocation check and the OTP check both already filter on expiry — so they
 * are pure table growth. This purges them nightly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredCredentialCleanupJob {

    private final RevokedTokenRepository revokedTokenRepository;
    private final OtpRepository otpRepository;

    @Scheduled(cron = "${identity.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int revokedTokens = revokedTokenRepository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC));
        int otps = otpRepository.deleteExpired(LocalDateTime.now());

        log.info("Purged expired credentials (revokedTokens={}, otps={})", revokedTokens, otps);
    }
}
```

The two different clocks are intentional and mirror existing behavior: `revoked_tokens.expires_at` is written in UTC by `TokenRevocationServiceImpl`, while `Otp.expiredAt` is written with the system default zone. Normalizing both is recorded under Deferred Items rather than changed silently here.

- [ ] **Step 6: Enable scheduling**

Create `src/main/java/com/souldealers/crowdtracebackend/shared/config/SchedulingConfig.java`:

```java
package com.souldealers.crowdtracebackend.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 7: Run the test**

Run: `./mvnw test -Dtest=ExpiredCredentialCleanupJobTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: PASS. `CrowdtraceModulesTest` enforces Spring Modulith boundaries — the job lives under `modules/identity/internal/service`, which is correct.

- [ ] **Step 9: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(identity): purge expired OTPs and revoked tokens nightly

Both tables grew without bound although every read already filters on expiry.
Uses bulk @Modifying deletes rather than derived deletes, and indexes
otp.expired_at to match the index revoked_tokens already has.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Idempotent revocation, dead code, and login/signup hardening

Bundles the remaining correctness items: a dead repository method with the wrong ID type, a filter that swallows failures silently, a token-revocation race, a redundant lookup on the login path, and a non-transactional signup.

Per peer review, revocation is implemented as an **idempotent database write** (`INSERT ... ON CONFLICT DO NOTHING`) rather than by catching `DataIntegrityViolationException`. Catching is brittle once the method participates in an outer transaction: the insert may not flush until commit, and by then the transaction can already be marked rollback-only, so the catch never runs and the whole request fails.

The upsert is PostgreSQL-specific. H2 2.4.240 rejects this `ON CONFLICT` form even with `MODE=PostgreSQL`, so the repository and concurrency contract are tested against PostgreSQL 16 through Testcontainers. This test is the database-dialect exception to the standard H2 integration harness and requires a working Docker-compatible container runtime.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/UserRepository.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/JwtFilter.java:59-61`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/RevokedTokenRepository.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/TokenRevocationServiceImpl.java:22-32`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java` (`signUp`, `login`, `generateAndSendOtp`)
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/TokenRevocationRaceTest.java`

**Interfaces:**
- Consumes: `RevokedTokenRepository`, `AuthService.logout(String)`, Docker/Testcontainers for the PostgreSQL integration test.
- Produces: `RevokedTokenRepository.insertIgnoringConflict(String tokenHash, LocalDateTime expiresAt) : int` — native upsert returning rows inserted (1 on insert, 0 when the hash already exists). `TokenRevocationService.revoke` keeps its signature and becomes safe under concurrency. `AuthServiceImpl.runAfterCommit(Runnable)` replaces the single-purpose `sendWelcomeEmailAfterCommit`.

- [ ] **Step 1: Add PostgreSQL Testcontainers test dependencies**

Add these test-scoped dependencies to `pom.xml`. Spring Boot 4.1.0 manages Testcontainers 2.0.5, whose database modules use the `testcontainers-*` artifact names:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

Do not add explicit versions; the Spring Boot dependency BOM supplies them.

- [ ] **Step 2: Write the failing PostgreSQL contract test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/TokenRevocationRaceTest.java`. It pins the native repository contract directly and then exercises the service under concurrent callers. This is a database-only integration test, so it deliberately omits MockMvc while retaining the test profile and notification mock.

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.repository.RevokedTokenRepository;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@ActiveProfiles("test")
@Testcontainers
class TokenRevocationRaceTest {

    private static final int CONCURRENCY = 8;

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void usePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @Transactional
    void nativeUpsertReturnsOneThenZeroForTheSameHash() {
        String tokenHash = "a".repeat(64);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        assertThat(revokedTokenRepository.insertIgnoringConflict(tokenHash, expiresAt))
                .isOne();
        assertThat(revokedTokenRepository.insertIgnoringConflict(tokenHash, expiresAt))
                .isZero();
    }

    @Test
    void concurrentRevocationOfTheSameTokenIsIdempotent() throws Exception {
        String token = "race-token-value";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        long before = revokedTokenRepository.count();

        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        List<Callable<Throwable>> callers = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            callers.add(() -> {
                try {
                    barrier.await();
                    tokenRevocationService.revoke(token, expiresAt);
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });
        }

        List<Future<Throwable>> results;
        try {
            results = pool.invokeAll(callers, 15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        for (Future<Throwable> result : results) {
            assertThat(result.isCancelled())
                    .as("every revocation caller must finish before the timeout")
                    .isFalse();
            assertThat(result.get())
                    .as("no caller may fail when revoking a token another caller already revoked")
                    .isNull();
        }

        assertThat(revokedTokenRepository.count())
                .as("exactly one row must exist for the token")
                .isEqualTo(before + 1);
        assertThat(tokenRevocationService.isRevoked(token)).isTrue();
    }

    @Test
    void revokingSequentiallyTwiceIsAlsoSafe() {
        String token = "sequential-token-value";
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15);

        long before = revokedTokenRepository.count();

        tokenRevocationService.revoke(token, expiresAt);
        tokenRevocationService.revoke(token, expiresAt);

        assertThat(revokedTokenRepository.count()).isEqualTo(before + 1);
        assertThat(tokenRevocationService.isRevoked(token)).isTrue();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=TokenRevocationRaceTest`
Expected: FAIL deterministically at test compilation because `RevokedTokenRepository.insertIgnoringConflict` does not exist yet. Do not use nondeterministic reproduction of the old race as the red gate; the final concurrent test is the behavioral proof.

- [ ] **Step 4: Add the idempotent insert**

In `RevokedTokenRepository.java`:

```java
    /**
     * Idempotent by construction: two callers revoking the same token race
     * harmlessly, and neither sees a constraint violation. Catching
     * DataIntegrityViolationException instead would be unreliable inside an
     * outer transaction, where the insert may not flush until commit and the
     * transaction can already be marked rollback-only.
     */
    @Modifying
    @Query(value = "insert into revoked_tokens (token_hash, expires_at) "
            + "values (:tokenHash, :expiresAt) on conflict (token_hash) do nothing",
            nativeQuery = true)
    int insertIgnoringConflict(@Param("tokenHash") String tokenHash,
                               @Param("expiresAt") LocalDateTime expiresAt);
```

with imports `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`.

`on conflict (token_hash)` targets the `uc_revoked_tokens_token_hash` unique constraint created in `V3`.

- [ ] **Step 5: Rewrite `revoke`**

In `TokenRevocationServiceImpl.java`:

```java
    @Override
    @Transactional
    public void revoke(String token, LocalDateTime expiresAt) {
        revokedTokenRepository.insertIgnoringConflict(hash(token), expiresAt);
    }
```

Add `import org.springframework.transaction.annotation.Transactional;`. The read-then-write pair is gone entirely, so there is no window left to race. `isRevoked` is unchanged.

- [ ] **Step 6: Delete the dead repository method**

In `UserRepository.java`, remove:

```java
    Optional<User> findById(UUID id);
```

and the now-unused `import java.util.UUID;`. The entity's `@Id` is `Long`; this overload was wrong and unused.

- [ ] **Step 7: Log authentication failures in `JwtFilter` without exception details**

Replace the catch block at `JwtFilter.java:59-61`:

```java
        } catch (Exception exception){
            log.debug("Rejected bearer token for {} {} ({})",
                    request.getMethod(), request.getRequestURI(),
                    exception.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
        }
```

The class already carries `@Slf4j`; `log` was never used. `debug` rather than `warn` because an expired token on a busy API is routine. Log only the stable exception category—not `exception.getMessage()`—because messages from account lookup or token parsing may contain an email address or sensitive parser detail.

- [ ] **Step 8: Drop the redundant lookup on the login path**

In `AuthServiceImpl.login`:

```java
    @Override
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        SecurityUser principal = (SecurityUser) authentication.getPrincipal();
        User user = principal.user();

        return LoginResponse.builder()
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .token(jwtService.generateToken(principal))
                .build();
    }
```

Add `import org.springframework.security.core.Authentication;`. Then delete the private `findUserByEmail` helper: after replacing the login lookup, its only caller is gone. Keep `findUserByEmailForUpdate`, which the verification and reset paths still use.

- [ ] **Step 9: Make signup atomic and move delivery after commit**

Add `@Transactional` to `signUp`:

```java
    @Override
    @Transactional
    public GenericResponseMessage signUp(SignUpRequest request) {
```

Generalize the existing after-commit helper and route OTP delivery through it, so a mail-provider outage cannot roll back a persisted user:

```java
    private void generateAndSendOtp(User user, OtpType type){
        String code = otpService.generateOtp(user.getEmail(), type);
        String email = user.getEmail();
        String displayName = user.getDisplayName();

        runAfterCommit(() -> notificationService.sendOtpEmail(email, code, displayName, type));
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void sendWelcomeEmailAfterCommit(String email, String displayName) {
        runAfterCommit(() -> notificationService.sendWelcomeEmail(email, displayName));
    }
```

- [ ] **Step 10: Run the PostgreSQL test**

Run: `./mvnw test -Dtest=TokenRevocationRaceTest`
Expected: PASS, all three tests against the PostgreSQL 16 container. This command requires Docker (or another Testcontainers-compatible runtime) to be running.

- [ ] **Step 11: Run the full suite**

Run: `./mvnw test`
Expected: PASS, including `TokenRevocationRaceTest` against PostgreSQL. Docker (or another Testcontainers-compatible runtime) is now a full-suite prerequisite. The existing service-level signup tests are not test-transactional: proxied integration calls commit before returning, while the Mockito unit calls have no active synchronization and therefore execute the callback immediately. Their notification assertions remain valid.

- [ ] **Step 12: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "refactor(identity): make revocation idempotent and remove dead code

Replaces check-then-insert revocation with INSERT ... ON CONFLICT DO NOTHING,
which is safe under concurrency and inside an outer transaction, and verifies
the native SQL against PostgreSQL 16 rather than H2 compatibility mode. Also deletes
UserRepository.findById(UUID) (wrong ID type, unused), logs swallowed JWT
failures, drops a duplicate user lookup on login, and moves signup email
delivery after commit.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Invalidate existing sessions on password reset

Review item 3. `resetPassword` writes a new hash and returns; every JWT issued before the reset keeps working for up to 15 minutes. The threat model for "reset my password" is *someone is in my account* — and this leaves them there.

**This task is last deliberately, and the design changed after peer review.**

The naive implementation — comparing the token's `iat` against `User.updatedAt` — is wrong twice over. First, `updatedAt` advances on every `@PreUpdate`, including `UserServiceImpl.updateProfile`, so editing a display name would log you out of every device. Second, a timestamp of any kind inherits JWT's one-second `iat` precision, which forces a choice between rejecting the token a user earns by logging in immediately after a reset (fail closed) and leaving a sub-second window where a pre-reset token still works (fail open). The first draft of this plan chose fail-open; peer review correctly flagged that as an avoidable gap.

**A monotonic integer removes the dilemma.** `credentials_version` is signed into the token and compared for exact equality against the database on every request. No clocks, no timezones, no ordering edge, and it reuses the user lookup `JwtFilter` already performs.

**Files:**
- Create: `src/main/resources/db/migration/V7__add_users_credentials_version.sql`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/User.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/JwtService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/JwtServiceImpl.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/JwtFilter.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java` (`resetPassword`)
- Modify: `src/test/java/com/souldealers/crowdtracebackend/IdentityMigrationTest.java:31-33`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/PasswordResetFlowTest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/SessionInvalidationTest.java`

**Interfaces:**
- Consumes: `SecurityUser.user()`, `UserRepository.findByEmailForUpdate`, `captureLatestOtpCode` (added to `PasswordResetFlowTest` in Task 9 Step 10).
- Produces:
  - `User.getCredentialsVersion() : int` / `setCredentialsVersion(int)` — non-null, defaults to `0`.
  - `JwtService.extractCredentialsVersion(String jwtToken) : Integer` — reads the `cv` claim, `null` when absent.
  - Tokens now carry a `cv` claim. A token minted before this change has no `cv`; Step 6 defines that as **rejected**, since every such token is at most 15 minutes from expiry anyway and failing closed is correct here.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/souldealers/crowdtracebackend/modules/identity/SessionInvalidationTest.java`:

```java
package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.modules.identity.internal.model.User;
import com.souldealers.crowdtracebackend.modules.identity.internal.repository.UserRepository;
import com.souldealers.crowdtracebackend.shared.JwtService;
import com.souldealers.crowdtracebackend.shared.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cors.allowed-origins=http://localhost")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionInvalidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void newAccountsStartAtVersionZeroAndTheirTokensWork() throws Exception {
        User user = saveActiveUser("version-zero@example.com");
        assertThat(user.getCredentialsVersion()).isZero();

        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void legacyTokenWithoutCredentialsVersionIsRejected() throws Exception {
        User user = saveActiveUser("legacy-no-version@example.com");
        UserDetails legacyPrincipal = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(UserRoles.REGISTERED_USER.name())
                .build();

        // JwtServiceImpl only adds cv for SecurityUser. This produces a valid,
        // signed pre-migration token whose sole defect is the missing claim.
        String legacyToken = jwtService.generateToken(legacyPrincipal);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bumpingTheVersionInvalidatesTokensCarryingTheOldOne() throws Exception {
        User user = saveActiveUser("version-bump@example.com");
        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        user.setCredentialsVersion(user.getCredentialsVersion() + 1);
        userRepository.save(user);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenMintedAfterTheBumpIsAccepted() throws Exception {
        User user = saveActiveUser("version-after@example.com");

        user.setCredentialsVersion(7);
        User saved = userRepository.save(user);

        String token = jwtService.generateToken(new SecurityUser(saved));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void editingTheProfileDoesNotLogTheUserOut() throws Exception {
        User user = saveActiveUser("version-profile@example.com");
        String token = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(patch("/api/v1/auth/profile-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed Person\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private User saveActiveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .displayName("Invalidation Probe")
                .role(UserRoles.REGISTERED_USER)
                .passwordHash(passwordEncoder.encode("a-very-long-password"))
                .accountStatus(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SessionInvalidationTest`
Expected: FAIL to compile — `getCredentialsVersion` does not exist.

- [ ] **Step 3: Write the migration and update the schema test**

Create `src/main/resources/db/migration/V7__add_users_credentials_version.sql`:

```sql
-- Incremented whenever credentials change. The value is signed into each JWT
-- and compared for exact equality on every request, so a password reset makes
-- every previously issued token invalid immediately.
-- Deliberately an integer rather than a timestamp: JWT iat has one-second
-- precision, which would force a choice between rejecting the token a user
-- earns by logging in right after a reset and leaving a sub-second window in
-- which a pre-reset token still works.
ALTER TABLE users ADD COLUMN credentials_version INTEGER NOT NULL DEFAULT 0;
```

In `IdentityMigrationTest.java`, extend the `users` column expectation (which already gained `deleted_at` in Task 8):

```java
        assertThat(columnsFor("users")).containsExactlyInAnyOrder(
                "id", "email", "password_hash", "display_name", "role", "account_status",
                "created_at", "updated_at", "deleted_at", "credentials_version");
```

- [ ] **Step 4: Add the field to `User`**

In `User.java`, add below `deletedAt`:

```java
    @Builder.Default
    @Column(name = "credentials_version", nullable = false)
    private int credentialsVersion = 0;
```

`@Builder.Default` is required: without it Lombok's builder would produce `0` only by accident of primitive defaulting and would warn. Add `import lombok.Builder;` if not already present (it is).

- [ ] **Step 5: Sign the version into the token**

Add to the `JwtService` interface:

```java
    Integer extractCredentialsVersion(String jwtToken);
```

In `JwtServiceImpl.java`, add the claim when minting. Replace `generateToken(Map, UserDetails)`'s claim setup:

```java
    public static final String CREDENTIALS_VERSION_CLAIM = "cv";

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        Set<String> roles = userDetails.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        extraClaims.put("role", String.join(",", roles));

        if (userDetails instanceof SecurityUser securityUser) {
            extraClaims.put(CREDENTIALS_VERSION_CLAIM, securityUser.user().getCredentialsVersion());
        }

        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + (tokenExpirationTime * 1000L))) // Convert seconds to milliseconds
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    @Override
    public Integer extractCredentialsVersion(String jwtToken) {
        return extractSingleClaim(jwtToken,
                claims -> claims.get(CREDENTIALS_VERSION_CLAIM, Integer.class));
    }
```

Add `import com.souldealers.crowdtracebackend.modules.identity.SecurityUser;`.

- [ ] **Step 6: Enforce the check in `JwtFilter`**

Extend the validation block inside `doFilterInternal`:

```java
            if(userEmail !=null && SecurityContextHolder.getContext().getAuthentication() == null){
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (userDetails.isEnabled()
                        && jwtService.isTokenValid(token, userDetails)
                        && !tokenRevocationService.isRevoked(token)
                        && credentialsVersionMatches(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                }
            }
```

Add the private method:

```java
    /**
     * A password reset increments the stored version, so a token carrying an
     * older one is dead. Exact integer equality — no clock, no timezone and no
     * same-second ambiguity, unlike a timestamp compared against JWT iat.
     *
     * <p>A token with no {@code cv} claim predates this check and is rejected.
     * Failing closed costs at most one re-login within the 15-minute token
     * lifetime, which is the right trade for a credential check.
     */
    private boolean credentialsVersionMatches(String token, UserDetails userDetails) {
        if (!(userDetails instanceof SecurityUser securityUser)) {
            return false;
        }

        Integer tokenVersion = jwtService.extractCredentialsVersion(token);

        return tokenVersion != null
                && tokenVersion == securityUser.user().getCredentialsVersion();
    }
```

The unexpected-principal branch deliberately returns `false`: every authenticated request in this application is supposed to load a `SecurityUser`, so silently accepting another implementation would disable the credential-version invariant for any future authentication path. `tokenVersion` is an `Integer` and `getCredentialsVersion()` an `int`, so `==` unboxes the left side and compares numerically. This is correct — but it is exactly the shape that silently compares references when both sides are boxed, so do not change either type without revisiting it.

- [ ] **Step 7: Increment on password reset**

In `AuthServiceImpl.resetPassword`, after setting the new hash:

```java
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCredentialsVersion(user.getCredentialsVersion() + 1);
        userRepository.save(user);
```

The surrounding method is already `@Transactional` and the user was loaded with `findByEmailForUpdate` (a `PESSIMISTIC_WRITE` lock), so two concurrent resets serialize and cannot lose an increment.

- [ ] **Step 8: Run the test**

Run: `./mvnw test -Dtest=SessionInvalidationTest`
Expected: PASS, all five tests — including rejection of a validly signed legacy token with no `cv`, and `editingTheProfileDoesNotLogTheUserOut`, which is the regression this design exists to prevent.

- [ ] **Step 9: Add the end-to-end reset round trip**

Add to `PasswordResetFlowTest`. This is the test the peer review asked for: it performs a real reset through the HTTP API and a real login afterwards, rather than setting state by hand.

```java
    @Test
    void resetThenLoginEndToEnd() throws Exception {
        String email = "reset-round-trip@example.com";
        User user = saveUser(email, "Reset Round Trip", "old-password");
        String oldToken = jwtService.generateToken(new SecurityUser(user));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        String code = captureLatestOtpCode(email, OtpType.RESET);

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\","
                                + "\"password\":\"a-brand-new-password\","
                                + "\"confirmPassword\":\"a-brand-new-password\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.data.token");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }
```

`captureLatestOtpCode` is the helper added to this class in Task 9 Step 10. Add these imports and the `JwtService` field to `PasswordResetFlowTest`:

```java
import com.souldealers.crowdtracebackend.shared.JwtService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

    @Autowired
    private JwtService jwtService;
```

`saveUser` in this class builds an `ACTIVE` user, which `/me` and `/login` both require. `AuthController.login` wraps `LoginResponse` in `ApiResponse`, whose record components are `success`, `message`, `data`, and `errors`, so the token path is exactly `$.data.token`.

- [ ] **Step 10: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(identity): invalidate existing sessions on password reset

Tokens issued before a reset stayed valid for the full token lifetime, so a
reset did not evict an attacker. Uses a monotonic credentials_version signed
into the token and compared for exact equality, rather than updated_at (which
also moves on profile edits) or a timestamp (which inherits JWT iat's
one-second precision and would leave a same-second gap).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Deferred Items

Tracked here so they are not silently lost. None are in scope for this plan.

### Required before any production release

| Item | Why deferred | Note |
|---|---|---|
| **Rate limiting / OTP attempt cap** (review item 5) | ~~Explicitly excluded by the user~~ **Delivered 2026-09-25** | Implemented per `docs/superpowers/specs/2026-09-25-otp-rate-limiting-design.md`. Two layers over `rate_limit_bucket`: a coarse IP guard and an identity-keyed control, with the OTP attempt cap keyed on identity rather than on the OTP row (a per-row counter is bypassable via `/resend-otp`). |
| **Prod `secret-key` is undefined** (review item 2) | No production deployment exists; Global Constraints forbid touching `application-prod.yaml` | `application-prod.yaml` defines no `secret-key`, and `@Value("${secret-key}")` has no default, so the app will not boot on that profile until `SECRET_KEY` is set. Note it is `SECRET_KEY`, not `JWT_SECRET` as `application-dev.yaml` implies. |
| **Prod `otp.hmac-secret` is undefined** | Introduced by Task 9 under the same constraint | Task 9 adds `otp.hmac-secret` to the dev and test profiles only. Prod needs `OTP_HMAC_SECRET` set before first boot. It must be valid Base64 encoding at least 32 random bytes and must be distinct from the JWT signing key; validated configuration intentionally prevents startup otherwise. |
| **Prod `rate-limit.hmac-secret` is undefined** | Introduced 2026-09-25 under the same constraint as `otp.hmac-secret` | Prod needs `RATE_LIMIT_HMAC_SECRET` set before first boot. Valid Base64 of at least 32 random bytes, and **distinct from `OTP_HMAC_SECRET`** so rotating one does not affect the other. Validated configuration prevents startup otherwise. |
| **Committed dev signing key** | It is dev config, and a test depends on it | `application-dev.yaml:75` defaults to `MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=` (base64 of `0123456789…`), and `AuthorizationMatrixTest:40` hardcodes the same literal. Rotating it means updating that test. Harmless while dev-only; must not survive the first deploy. |
| **Signing-key rotation** | No key-management story exists yet | Raised by the peer review. Both the JWT key and the new OTP HMAC key need a rotation path; `credentials_version` (Task 12) gives a clean way to force re-authentication when one rotates. |

### Lower priority

| Item | Why deferred | Note |
|---|---|---|
| `UserController`'s `/api/auth` prefix | Out of scope for Task 5 | Task 5 collapses `AuthController`'s root aliases. `UserController` is still mapped `{"/api/auth", "/api/v1/auth"}`; both work, so this is duplication rather than a defect. Worth collapsing to the canonical prefix in a follow-up. |
| **Forwarded-header strategy is `none`** | Proxy topology is undocumented | `server.forward-headers-strategy: none`, so the IP layer keys on the socket address. Safe, but if a load balancer is in front, all traffic attributes to it and the IP layer stops distinguishing clients — the identity layer then carries the whole load. Switch to `native` with explicit `internal-proxies` once the LB CIDRs are known. |
| **jjwt 0.11.5 deprecations** | Cosmetic, no behavior change | `parserBuilder()`, `setSigningKey()` and `SignatureAlgorithm` are all deprecated in 0.12.x. |
| **Two DB queries per authenticated request** | Fine at current scale | `JwtFilter` loads the user and then checks the denylist on every call. Task 12 adds no third query — the version check reuses the already-loaded user. |
| **Mixed clocks on expiry columns** | Pre-existing; surfaced in Task 10 | `revoked_tokens.expires_at` is written in UTC; `Otp.expiredAt` uses the system default zone. Normalize both to UTC. Deliberately not changed inside a task that would otherwise be about cleanup. |
| **The unused `role` claim in the JWT** | Correct behavior, latent trap | `JwtFilter` reloads authorities from the database and never reads the claim. Leaving a stale authority claim in the token invites someone to start trusting it. Unlike `role`, the new `cv` claim added in Task 12 *is* read on every request. |
| **Refresh tokens** | Deferred to CT-009 by the original PR | Access tokens live 15 minutes with no refresh path. |

### Peer-review points not adopted

| Point | Decision |
|---|---|
| "Use Argon2 or PBKDF2 for OTP storage" | Not adopted; **HMAC-SHA-256 chosen instead** (the review offered both). A slow hash is designed to resist offline attack on a *high-entropy-per-attempt* secret; here the secret is 6 digits, so no practical work factor saves it once the digest is unkeyed. A keyed MAC removes the attack entirely rather than slowing it, and costs microseconds instead of milliseconds on a path that runs during every signup and reset. |
| "Retain a test proving repeated consumption cannot succeed twice" | **Adopted** — `consumeOtpAcceptsThePlaintextCodeExactlyOnce` in Task 9. Genuine concurrent double-consumption is covered by the `PESSIMISTIC_WRITE` lock, now enforced at the service boundary by `@Transactional`. |
| "The `docs/pr-ct-007-password-authentication.md` reference is absent" | **Adopted** — the Spec line now points at the review conversation and this peer review, and notes the file's absence. |
