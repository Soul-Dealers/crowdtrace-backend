# Verify OTP Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify a pending account with its email OTP through the public `/api/v1/auth/verify-otp` endpoint.

**Architecture:** The controller validates and forwards the request, while `AuthServiceImpl` owns the transactional transition from `PENDING_VERIFICATION` to `ACTIVE`. `OtpService` remains the lifecycle boundary: it validates and invalidates a `CREATE` OTP, and `NotificationService` sends the welcome email only after the successful state transition.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring Data JPA, Spring transactions, JUnit 5, AssertJ, Mockito, MockMvc, Maven.

---

### Task 1: Specify account-verification orchestration

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImplTest.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/AuthService.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java`

- [ ] **Step 1: Write the failing valid-verification service test**

  Add `VerifyOtpDto` and `GenericResponseMessage` imports to `AuthServiceImplTest`, then add:

  ```java
  @Test
  void verifyOtp_ValidCreateOtp_ActivatesAccountConsumesOtpAndSendsWelcomeEmail() {
      User pendingUser = User.builder()
              .email("verify@example.com")
              .displayName("Verify User")
              .accountStatus(UserStatus.PENDING_VERIFICATION)
              .build();
      VerifyOtpDto request = new VerifyOtpDto("123456", "VERIFY@example.com ");
      when(userRepository.findByEmail("verify@example.com")).thenReturn(Optional.of(pendingUser));
      when(otpService.isOtpValid("123456", "verify@example.com", OtpType.CREATE)).thenReturn(true);

      GenericResponseMessage response = authService.verifyOtp(request);

      assertThat(response.message()).isEqualTo("Email verified successfully");
      assertThat(pendingUser.getAccountStatus()).isEqualTo(UserStatus.ACTIVE);
      verify(userRepository).save(pendingUser);
      verify(otpService).invalidateOtp("123456", "verify@example.com", OtpType.CREATE);
      verify(notificationService).sendWelcomeEmail("verify@example.com", "Verify User");
  }
  ```

- [ ] **Step 2: Write failing invalid-code and inactive-state tests**

  Add tests that stub a pending account and `otpService.isOtpValid(..., OtpType.CREATE)` as `false`, asserting `ValidationException` with `"Could not verify this OTP"` and verifying no user save, OTP invalidation, or welcome email. Add another test using an `ACTIVE` account, asserting `IllegalStateException` and no OTP service interaction.

- [ ] **Step 3: Run the focused tests and verify RED**

  Run: `./mvnw test -q -Dtest=AuthServiceImplTest`

  Expected: FAIL. The current application does not compile because `AuthController` calls a nonexistent `verifyUser` method, and `verifyOtp` itself has no implementation. This is the expected red baseline before the production changes below.

- [ ] **Step 4: Implement the minimal transactional service flow**

  In `AuthServiceImpl`, add `@Transactional` to `verifyOtp`. Normalize `request.email()`, load the user with `findUserByEmail`, then reject any status other than `PENDING_VERIFICATION`:

  ```java
  if (user.getAccountStatus() != UserStatus.PENDING_VERIFICATION) {
      throw new IllegalStateException(EXISTING_EMAIL);
  }
  if (!otpService.isOtpValid(request.code(), email, OtpType.CREATE)) {
      throw new ValidationException("Could not verify this OTP");
  }
  user.setAccountStatus(UserStatus.ACTIVE);
  userRepository.save(user);
  otpService.invalidateOtp(request.code(), email, OtpType.CREATE);
  notificationService.sendWelcomeEmail(user.getEmail(), user.getDisplayName());
  return new GenericResponseMessage(VERIFICATION_SUCCESS_MSG);
  ```

  Add `VERIFICATION_SUCCESS_MSG = "Email verified successfully"` to `CustomMessages`. Retain `GenericResponseMessage verifyOtp(VerifyOtpDto request)` in `AuthService`.

- [ ] **Step 5: Make OTP lookup purpose-aware**

  Change the OTP port methods to:

  ```java
  boolean isOtpValid(String otpCode, String email, OtpType type);
  void invalidateOtp(String otpCode, String email, OtpType type);
  ```

  In `OtpRepository`, replace `findByCode` with:

  ```java
  Optional<Otp> findFirstByEmailAndCodeAndTypeOrderByCreatedAtDesc(
          String email, String code, OtpType type);
  ```

  Update `OtpServiceImpl` to use this query for both methods. A missing or expired code must return `false` from validation; invalidation rejects a missing matching OTP with the existing verification-failure conflict. This ensures only a registration (`CREATE`) code can activate an account.

- [ ] **Step 6: Run focused service tests and verify GREEN**

  Run: `./mvnw test -q -Dtest=AuthServiceImplTest`

  Expected: PASS. A valid OTP activates and consumes exactly once; invalid or non-pending verification does not mutate account state.

- [ ] **Step 7: Commit the service flow**

  ```bash
  git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImpl.java \
          src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/AuthService.java \
          src/main/java/com/souldealers/crowdtracebackend/modules/identity/OtpService.java \
          src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/OtpServiceImpl.java \
          src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/OtpRepository.java \
          src/main/java/com/souldealers/crowdtracebackend/shared/CustomMessages.java \
          src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/AuthServiceImplTest.java
  git commit -m "feat(identity): activate accounts from valid OTPs"
  ```

### Task 2: Publish a validated public verification route

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerifyOtpDto.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java`

- [ ] **Step 1: Write a failing public-route test**

  In `AuthenticationLifecycleTest`, add `OtpRepository` and a Mockito Spring bean for `NotificationService`. Sign up a user through the real `AuthService`, obtain that user's persisted `CREATE` OTP, then:

  ```java
  Otp otp = otpRepository.findAll().stream()
          .filter(candidate -> candidate.getEmail().equals("verify-route@example.com"))
          .findFirst()
          .orElseThrow();

  mockMvc.perform(post("/api/v1/auth/verify-otp")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\\\"email\\\":\\\"verify-route@example.com\\\",\\\"code\\\":\\\"" + otp.getCode() + "\\\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.message").value("Email verified successfully"));
  ```

  Assert the persisted account is now `ACTIVE`, the OTP `expiredAt` has passed, and `notificationService.sendWelcomeEmail("verify-route@example.com", "Verify Route User")` was called. Add a second request with a malformed email or short code and assert HTTP 400 with no welcome notification.

- [ ] **Step 2: Run the lifecycle test and verify RED**

  Run: `./mvnw test -q -Dtest=AuthenticationLifecycleTest`

  Expected: FAIL because no `/verify-otp` mapping consumes the request and the existing `/verify` mapping has an incompatible service call and response type.

- [ ] **Step 3: Add DTO constraints and delegate from the route**

  Add validation annotations to `VerifyOtpDto`:

  ```java
  public record VerifyOtpDto(
          @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
          @NotBlank @Email String email) {
  }
  ```

  Replace the stale `/verify` controller method with:

  ```java
  @PostMapping("/verify-otp")
  public ApiResponse<GenericResponseMessage> verifyOtp(
          @Valid @RequestBody VerifyOtpDto request) {
      GenericResponseMessage result = authService.verifyOtp(request);
      return ApiResponse.success(result, result.message());
  }
  ```

  Add the `jakarta.validation.Valid` import. The existing security configuration already publishes `/api/v1/auth/verify-otp`; do not alter unrelated reset routes in this scoped change.

- [ ] **Step 4: Run public-route tests and verify GREEN**

  Run: `./mvnw test -q -Dtest=AuthenticationLifecycleTest`

  Expected: PASS. Valid payloads reach the service unauthenticated, and malformed requests receive the standard validation response.

- [ ] **Step 5: Commit the route**

  ```bash
  git add src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerifyOtpDto.java \
          src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java \
          src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthenticationLifecycleTest.java
  git commit -m "feat(identity): expose OTP verification endpoint"
  ```

### Task 3: Verify the integrated behavior

**Files:**
- Verify only.

- [ ] **Step 1: Run OTP, service, and lifecycle checks**

  Run: `./mvnw test -q -Dtest=AuthServiceImplTest,AuthenticationLifecycleTest`

  Expected: PASS.

- [ ] **Step 2: Run the full Maven suite**

  Run: `./mvnw test -q`

  Expected: PASS. If unrelated, pre-existing worktree changes cause a failure, report the exact failure without expanding this feature’s scope.

- [ ] **Step 3: Inspect the scoped diff**

  Run: `git diff --check && git diff --check HEAD~2..HEAD`

  Expected: no whitespace errors in the verification-flow commits.
