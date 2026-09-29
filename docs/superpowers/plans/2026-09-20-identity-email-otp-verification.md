# Identity Email OTP Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver public, documented email-OTP account verification for CrowdTrace signups, with codes that never contain `0` and no password-reset flow.

**Architecture:** Keep HTTP concerns in `AuthController`, account-state orchestration in `AuthServiceImpl`, and code lifecycle rules in `OtpServiceImpl`. Persist OTPs in a new Flyway migration, bind all notification use through `NotificationService`, and use Springdoc controller annotations for the real `/api/v1/auth` contract rather than placeholder paths.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security, Spring Data JPA, Flyway, Springdoc 3.0.2, Resend HTTP API, JUnit 5, AssertJ, Mockito, MockMvc, Maven.

---

## Current baseline

`./mvnw test -q` currently fails at compile time because the uncommitted copied notification implementation references an undefined `appProperties` field in its password-reset method. That code is out of scope and must be removed before test-driven feature work can run. The existing `Otp` entity also uses a UUID while `OtpRepository` declares a `Long` identifier and the production Flyway schema has no OTP table; both must be reconciled as part of persistence setup.

### Task 1: Remove copied reset behavior and establish a compiling notification boundary

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/NotificationService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/notification/ResendNotificationService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java`
- Modify: `src/main/resources/application-test.yaml`

- [ ] **Step 1: Remove reset-only methods from the notification abstraction and provider**

Delete `sendPasswordResetEmail(...)` from `NotificationService`. Delete `sendPasswordResetEmail(...)` and `buildPasswordResetEmailHtml(...)` from `ResendNotificationService`; this removes the undefined `appProperties` reference and leaves only OTP and welcome-email responsibilities.

Change the provider configuration fields to match the existing application configuration:

```java
@Value("${resend.url}")
private String resendUrl;

@Value("${resend.api-key}")
private String resendApiKey;

@Value("${resend.from-email}")
private String fromEmail;
```

Add `resend.url: https://api.resend.com/emails` to `application-test.yaml` so the test context resolves every required configuration property without sending a real request.

- [ ] **Step 2: Depend on the notification port, not its Resend implementation**

In `AuthServiceImpl`, replace the concrete injection with the shared interface:

```java
private final NotificationService notificationService;
```

Replace direct calls such as:

```java
resendNotificationService.sendOtpEmail(email, otp.getCode(), "Create Account");
```

with:

```java
notificationService.sendOtpEmail(user.getEmail(), otp.getCode(), user.getDisplayName());
```

This makes the service independently testable and avoids coupling identity logic to a specific email vendor.

- [ ] **Step 3: Verify the existing test suite compiles and passes before adding OTP behavior**

Run: `./mvnw test -q`

Expected: exit code `0`; this confirms the pre-existing reset-copy compile failure is gone while all committed behavior remains intact.

- [ ] **Step 4: Commit the boundary repair**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/shared/NotificationService.java \
  src/main/java/com/souldealers/crowdtracebackend/shared/notification/ResendNotificationService.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
  src/main/resources/application-test.yaml
git commit -m "refactor(identity): remove reset notification flow"
```

### Task 2: Persist and validate purpose-aware, non-zero OTPs

**Files:**
- Create: `src/main/resources/db/migration/V2__create_otp_table.sql`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/Otp.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/OtpType.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/IdentityMigrationTest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/OtpServiceTest.java`

- [ ] **Step 1: Write failing OTP contract tests**

Create `OtpServiceTest` as a `@SpringBootTest` with the `test` profile. Autowire `OtpService` and `OtpRepository`; clean OTP rows in `@AfterEach`. First define the desired API in tests:

```java
@Test
void generatesSixDigitCreateOtpWithoutZero() {
    List<Otp> generated = IntStream.range(0, 30)
            .mapToObj(ignored -> otpService.generateOtp("otp@example.com", OtpType.CREATE))
            .toList();

    assertThat(generated)
            .extracting(Otp::getCode)
            .allMatch(code -> code.matches("[1-9]{6}"));
    assertThat(generated).extracting(Otp::getType).containsOnly(OtpType.CREATE);
}

@Test
void rejectsAnOtpBelongingToAnotherEmail() {
    Otp otp = otpService.generateOtp("purpose@example.com", OtpType.CREATE);

    assertThat(otpService.isOtpValid(otp.getCode(), otp.getEmail(), OtpType.CREATE)).isTrue();
    assertThat(otpService.isOtpValid(otp.getCode(), "other@example.com", OtpType.CREATE)).isFalse();
}
```

Keep `RESET` out of the production enum in the final implementation. The final contract and every public workflow must expose only `CREATE`.

Extend `IdentityMigrationTest` to expect `otp` and its columns:

```java
assertThat(tableExists("otp")).isTrue();
assertThat(columnsFor("otp")).containsExactlyInAnyOrder(
        "id", "email", "type", "code", "expired_at", "created_at");
```

- [ ] **Step 2: Run the focused tests and confirm they fail for missing OTP schema/API behavior**

Run: `./mvnw test -q -Dtest=OtpServiceTest,IdentityMigrationTest`

Expected: FAIL because the repository cannot yet query by email/code/type, the OTP table is absent from Flyway, and generated codes still allow `0`.

- [ ] **Step 3: Add the migration and align the entity/repository identifier type**

Create `V2__create_otp_table.sql` using the project’s existing `BIGINT` identity convention:

```sql
CREATE TABLE otp
(
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    email      VARCHAR(255)                            NOT NULL,
    type       VARCHAR(32)                             NOT NULL,
    code       VARCHAR(6)                              NOT NULL,
    expired_at TIMESTAMP WITHOUT TIME ZONE             NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE             NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_otp PRIMARY KEY (id)
);

CREATE INDEX idx_otp_email_type_expiry ON otp (email, type, expired_at);
```

Change `Otp.id` to `Long` with `GenerationType.IDENTITY`, add `@Table(name = "otp")`, make `type`, `expiredAt`, and `createdAt` non-null as appropriate, and change the repository to match:

```java
public interface OtpRepository extends JpaRepository<Otp, Long> {
    Optional<Otp> findFirstByEmailAndCodeAndTypeOrderByCreatedAtDesc(
            String email, String code, OtpType type);

    List<Otp> findAllByEmailAndTypeAndExpiredAtAfter(
            String email, OtpType type, LocalDateTime now);
}
```

Retain only `CREATE` in `OtpType`; do not retain a dormant reset purpose.

- [ ] **Step 4: Implement the minimal OTP lifecycle service**

Change the public port to make validation purpose-aware and replacement explicit:

```java
public interface OtpService {
    Otp generateOtp(String email, OtpType type);
    boolean isOtpValid(String otpCode, String email, OtpType type);
    void invalidateOtp(String otpCode, String email, OtpType type);
    void invalidateActiveOtps(String email, OtpType type);
}
```

In `OtpServiceImpl`, create codes with no zero digit:

```java
private String generateCode() {
    SecureRandom random = new SecureRandom();
    StringBuilder code = new StringBuilder(OTP_LENGTH);
    for (int index = 0; index < OTP_LENGTH; index++) {
        code.append(random.nextInt(9) + 1);
    }
    return code.toString();
}
```

Use `findFirstByEmailAndCodeAndTypeOrderByCreatedAtDesc` for validation and invalidation. Return `false` for a missing, expired, or mismatched OTP instead of throwing a generic conflict. `invalidateActiveOtps` must set every currently unexpired OTP for that email and type to `LocalDateTime.now()` before a replacement is created.

- [ ] **Step 5: Run the focused persistence and OTP tests until green**

Run: `./mvnw test -q -Dtest=OtpServiceTest,IdentityMigrationTest`

Expected: exit code `0`; migration validation, non-zero six-digit codes, purpose-aware lookup, and invalidation behavior all pass.

- [ ] **Step 6: Commit the OTP lifecycle**

```bash
git add src/main/resources/db/migration/V2__create_otp_table.sql \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/Otp.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/OtpType.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java \
  src/test/java/com/souldealers/crowdtracebackend/IdentityMigrationTest.java \
  src/test/java/com/souldealers/crowdtracebackend/modules/identity/OtpServiceTest.java
git commit -m "feat(identity): persist non-zero email OTPs"
```

### Task 3: Orchestrate signup, verification, and resend through the identity service

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerifyOtpRequest.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpRequest.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/AuthService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java`

- [ ] **Step 1: Write failing service and public-route tests**

Add `@MockitoBean NotificationService notificationService` to `AuthenticationLifecycleTest` so no test invokes Resend. Add a test that signs up a new email and verifies persistence plus notification:

```java
@Test
void registrationCreatesAPendingAccountAndEmailsANonZeroOtp() {
    authService.signUp(SignUpRequest.builder()
            .email("verify@example.com")
            .password("password123")
            .displayName("Verify User")
            .build());

    Otp otp = otpRepository.findAll().getFirst();
    assertThat(otp.getEmail()).isEqualTo("verify@example.com");
    assertThat(otp.getType()).isEqualTo(OtpType.CREATE);
    assertThat(otp.getCode()).matches("[1-9]{6}");
    verify(notificationService).sendOtpEmail(
            "verify@example.com", otp.getCode(), "Verify User");
}
```

Then add MockMvc tests that post `{"email":"...","otpCode":"..."}` to `/api/v1/auth/verify-otp` and assert an active persisted user plus an expired OTP, and post `{"email":"..."}` to `/api/v1/auth/resend-otp` and assert that the prior OTP is expired, a new code exists, and an email is sent. Include invalid-code and already-active-account cases asserting the project’s `400` and `409` Problem Detail responses.

- [ ] **Step 2: Run the lifecycle test and verify red**

Run: `./mvnw test -q -Dtest=AuthenticationLifecycleTest`

Expected: FAIL because the request records, service methods, and controller routes do not yet exist; the failure must be attributable to the intended verification behavior rather than configuration.

- [ ] **Step 3: Add validated request DTOs and service API**

Create these request records:

```java
public record VerifyOtpRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "[1-9]{6}") String otpCode) {
}

public record ResendOtpRequest(@NotBlank @Email String email) {
}
```

Add the following to `AuthService`:

```java
GenericMessageResponse verifyOtp(VerifyOtpRequest request);
GenericMessageResponse resendOtp(ResendOtpRequest request);
```

Add `VERIFICATION_SUCCESS_MSG = "Email verified successfully"` to `CustomMessages`.

- [ ] **Step 4: Implement atomic state transitions**

Make `verifyOtp` transactional. Normalize the request email, load the user, reject a user whose `accountStatus` is not `PENDING_VERIFICATION` with `IllegalStateException`, validate its `CREATE` OTP, and reject `false` validation with `ValidationException("Could not verify this OTP")`. On success, set `UserStatus.ACTIVE`, save the user, invalidate the exact OTP, send the welcome email, and return `new GenericMessageResponse(VERIFICATION_SUCCESS_MSG)`.

Make `resendOtp` transactional. It must find a pending user, invalidate active `CREATE` OTPs, generate exactly one replacement `CREATE` OTP, notify the user with the replacement code, and return `new GenericMessageResponse(TOKEN_SENT_MSG)`.

Update `signUp` so both branches issue a code: after saving a new pending user, generate and notify; when the user already exists and is pending, invalidate active codes then generate and notify; when it is not pending, throw `IllegalStateException(EXISTING_EMAIL)`. Always use the normalized email for persistence lookup and the persisted user’s email/display name in notifications.

- [ ] **Step 5: Run the lifecycle test until green**

Run: `./mvnw test -q -Dtest=AuthenticationLifecycleTest`

Expected: exit code `0`; a signup produces one non-zero create OTP, valid verification activates exactly the pending account and consumes its OTP, resend replaces an OTP, and invalid lifecycle requests remain safe failures.

- [ ] **Step 6: Commit the identity workflow**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerifyOtpRequest.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/ResendOtpRequest.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/AuthService.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
  src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java \
  src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java
git commit -m "feat(identity): verify pending accounts with email OTP"
```

### Task 4: Publish the real Springdoc contract and public security policy

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`

- [ ] **Step 1: Write failing OpenAPI and route-security assertions**

Replace stale placeholder assertions for `/api/auth/register` and `/api/auth/login` with assertions that the generated document contains `/api/v1/auth/signup`, `/api/v1/auth/verify-otp`, `/api/v1/auth/resend-otp`, and `/api/v1/auth/login`. Assert the three verification routes have an empty operation-security array and that their summaries describe signup, verification, and resend. Assert `/api/v1/auth/verify-otp` documents `200`, `400`, `404`, and `409` responses. Add a MockMvc assertion that an unauthenticated valid resend request reaches the controller instead of receiving `401`.

- [ ] **Step 2: Run the contract test and verify red**

Run: `./mvnw test -q -Dtest=OpenApiContractTest`

Expected: FAIL because the controller has no verification or resend mappings and OpenAPI still advertises the stale planned authentication paths.

- [ ] **Step 3: Add controller mappings, validation, and Springdoc annotations**

Remove class-level `@SecurityRequirement` from `AuthController`; put it on the protected `getUsers` operation only. Add `@Valid` to every request body and provide public-operation metadata using `@Operation`, `@ApiResponses`, `@Content`, and `@Schema`.

Implement controller methods with the application response envelope:

```java
@PostMapping("/verify-otp")
public ApiResponse<GenericMessageResponse> verifyOtp(
        @Valid @RequestBody VerifyOtpRequest request) {
    GenericMessageResponse result = authService.verifyOtp(request);
    return ApiResponse.success(result, result.message());
}

@PostMapping("/resend-otp")
public ApiResponse<GenericMessageResponse> resendOtp(
        @Valid @RequestBody ResendOtpRequest request) {
    GenericMessageResponse result = authService.resendOtp(request);
    return ApiResponse.success(result, result.message());
}
```

Give signup and login equivalent operation/response annotations. Each public operation must explicitly set an empty security requirement in the generated document so the document-level basic-auth default cannot imply authentication is required.

- [ ] **Step 4: Remove stale reset authorization and OpenAPI placeholders**

Delete `/api/v1/auth/request-password-reset` and `/api/v1/auth/reset-password` from `SecurityConfig.publicEndpoints`. In `OpenApiConfig`, remove the planned `/api/auth/register` and `/api/auth/login` path items (and the obsolete `Authentication` tag if no planned authentication path remains). Remove the global `.addSecurityItem(new SecurityRequirement().addList("basicAuth"))`; keep the reusable `basicAuth` scheme and let only the documented protected user-list operation opt in. Retain unrelated planned case/admin placeholder paths unchanged.

- [ ] **Step 5: Run focused docs/security tests until green**

Run: `./mvnw test -q -Dtest=OpenApiContractTest,AuthenticationLifecycleTest`

Expected: exit code `0`; Swagger/OpenAPI truthfully describes the implemented public identity routes, while the user list remains protected and reset routes are absent from security configuration.

- [ ] **Step 6: Run the complete verification suite**

Run: `./mvnw test -q`

Expected: exit code `0` with no compilation errors or failing tests.

- [ ] **Step 7: Commit API documentation and policy alignment**

```bash
git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java \
  src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java \
  src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java
git commit -m "docs(identity): publish OTP verification API contract"
```

## Plan review

- Coverage: signup OTP generation, no-zero constraint, persistence migration, verification, resend/replacement, password-reset exclusion, public security, Springdoc documentation, and regression tests all map to explicit tasks.
- Existing worktree safety: this plan incorporates the current uncommitted OTP/notification scaffolding rather than deleting unrelated files; only reset-specific code is intentionally removed because it conflicts with the approved scope.
- Known correction: the wrong `resend.notification.*` property keys and undefined `appProperties` reference are corrected before behavior tests begin.
