# CT-008 Role-Based Method-Level Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce CrowdTrace's guest, registered-user, moderator, and Super Admin endpoint policies with method-level Spring Security authorization while preserving the existing JWT credential lifecycle.

**Architecture:** Keep request-level security responsible for authentication, public infrastructure endpoints, and the public API namespace. Enable Spring method security and expose three composed annotations—registered user, moderator-or-above, and Super Admin—that endpoint methods apply explicitly. Role inheritance is expressed in the authorization expressions because the persisted `User` has one role and `SecurityUser` currently exposes that role as a single raw authority.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security method security, Spring MVC, JWT, JUnit 5, AssertJ, Mockito, MockMvc, Springdoc OpenAPI, Maven.

**Spec:** `docs/tasks/phase_1_identity.md` (CT-008), `docs/product-spec.md` (roles and permissions), and GitHub issue [#8](https://github.com/Soul-Dealers/crowdtrace-backend/issues/8).

## Global Constraints

- Keep the selected short-lived signed JWT access-token model from CT-007; `jwt.access-token-expiry` controls lifetime and the development default is 15 minutes.
- The persisted roles remain exactly `REGISTERED_USER`, `MODERATOR`, and `SUPER_ADMIN`.
- A verified badge is presentation metadata and must never produce a Spring authority or permission.
- Public responses must not expose passwords, password hashes, private email data, credentials, or verification evidence.
- Do not return JPA entities directly from controllers.
- Do not implement case, comment, follow, discovery, or moderation domain workflows in this plan; authorize their future endpoint methods and test the policy boundaries.
- Preserve unrelated worktree changes and do not create commits as part of this implementation.

## Review Focus

- A request without credentials must receive `401`, while an authenticated user with the wrong role must receive `403`; cover both in the authorization matrix.
- A `MODERATOR` must not reach Super Admin methods, while a `SUPER_ADMIN` must inherit moderator-level access; cover both directions.
- A valid registered-user JWT must authorize contribution methods, but an expired or account-deactivated JWT must remain rejected; reuse the CT-007 lifecycle behavior and add a regression case at a protected method.
- An approved or otherwise verified presentation state must not change a `REGISTERED_USER` authority set; prove authorization derives only from `UserRoles`.
- Public discovery must remain reachable without a token even after method security is enabled; cover the public request path separately from authenticated method checks.

---

### Task 1: Define reusable method-level authorization annotations

**Files:**
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/RequiresRegisteredUser.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/RequiresModerator.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/RequiresSuperAdmin.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/MethodAuthorizationAnnotationTest.java`

**Interfaces:**
- Consumes: raw authorities produced by `SecurityUser#getAuthorities()`.
- Produces: method annotations usable by current and future module controllers.

- [ ] **Step 1: Write the failing annotation metadata test**

  Add a test that loads each annotation class and asserts it is runtime-retained, targets methods and types, and carries the expected `@PreAuthorize` expression:

  ```java
  @Test
  void exposesTheThreeCrowdTraceAuthorizationPolicies() {
      assertThat(preAuthorizeValue(RequiresRegisteredUser.class))
              .isEqualTo("hasAnyAuthority('REGISTERED_USER', 'MODERATOR', 'SUPER_ADMIN')");
      assertThat(preAuthorizeValue(RequiresModerator.class))
              .isEqualTo("hasAnyAuthority('MODERATOR', 'SUPER_ADMIN')");
      assertThat(preAuthorizeValue(RequiresSuperAdmin.class))
              .isEqualTo("hasAuthority('SUPER_ADMIN')");
  }

  private String preAuthorizeValue(Class<? extends Annotation> annotationType) {
      return annotationType.getAnnotation(PreAuthorize.class).value();
  }
  ```

  Also assert `@Retention(RUNTIME)` and `@Target({METHOD, TYPE})` for each annotation so a refactor cannot silently remove runtime enforcement.

- [ ] **Step 2: Run the focused test and verify RED**

  Run: `./mvnw -B -Dtest=MethodAuthorizationAnnotationTest test`

  Expected: FAIL because the composed annotations do not exist yet.

- [ ] **Step 3: Add the composed annotations**

  Each annotation must use the same structure and only differ in its expression:

  ```java
  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasAnyAuthority('MODERATOR', 'SUPER_ADMIN')")
  public @interface RequiresModerator {
  }
  ```

  Implement the three exact policies:

  ```java
  @PreAuthorize("hasAnyAuthority('REGISTERED_USER', 'MODERATOR', 'SUPER_ADMIN')")
  @PreAuthorize("hasAnyAuthority('MODERATOR', 'SUPER_ADMIN')")
  @PreAuthorize("hasAuthority('SUPER_ADMIN')")
  ```

  Do not add a verified-user annotation or a second role enum.

- [ ] **Step 4: Enable Spring method security**

  Add `@EnableMethodSecurity` to `SecurityConfig`. Keep the existing JWT filter and stateless session configuration. Do not use URL matchers for moderator or Super Admin role decisions; those decisions belong to the method annotations.

- [ ] **Step 5: Run the focused test and verify GREEN**

  Run: `./mvnw -B -Dtest=MethodAuthorizationAnnotationTest test`

  Expected: PASS, with all expressions present at runtime.

### Task 2: Apply method-level policy to the current identity endpoint

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityConfig.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthorizationMatrixTest.java`

**Interfaces:**
- Consumes: `@RequiresSuperAdmin` from Task 1 and existing `JwtService` token generation.
- Produces: Super Admin-only access for `GET /users` and `GET /api/v1/auth/users`.

- [ ] **Step 1: Add the failing live-endpoint authorization tests**

  Add tests that create active users for all three roles, issue JWTs using `jwtService.generateToken(new SecurityUser(user))`, and exercise the existing user-list endpoint:

  ```java
  @ParameterizedTest
  @MethodSource("nonSuperAdminRoles")
  void rejectsUserListingForNonSuperAdmins(UserRoles role) throws Exception {
      String token = tokenFor(role);

      mockMvc.perform(get("/users").header("Authorization", "Bearer " + token))
              .andExpect(status().isForbidden());
  }

  private static Stream<UserRoles> nonSuperAdminRoles() {
      return Stream.of(UserRoles.REGISTERED_USER, UserRoles.MODERATOR);
  }

  @Test
  void allowsUserListingForSuperAdmin() throws Exception {
      mockMvc.perform(get("/users")
                      .header("Authorization", "Bearer " + tokenFor(UserRoles.SUPER_ADMIN)))
              .andExpect(status().isOk());
  }
  ```

  Include the `/api/v1/auth/users` alias in the same test and retain the no-token `401` assertion.

- [ ] **Step 2: Run the focused test and verify RED**

  Run: `./mvnw -B -Dtest=AuthorizationMatrixTest test`

  Expected: FAIL because the current endpoint allows every authenticated role.

- [ ] **Step 3: Protect the controller method**

  Add `@RequiresSuperAdmin` directly above `AuthController#getUsers`. Move the controller-level `@SecurityRequirement` to this method so public signup, login, OTP, and password-reset operations are not documented as requiring authentication.

  Update the operation description to state that the endpoint requires a Super Admin JWT. The controller remains a projection boundary; do not expose the `User` entity.

- [ ] **Step 4: Keep request authentication broad enough for method checks**

  Retain `.anyRequest().authenticated()` for non-public requests so Spring authenticates the bearer token before method security evaluates the role. Public health, authentication, documentation, and future `/api/public/**` discovery paths remain explicitly permitted.

  Do not add role-specific request matchers. This keeps role policy at the method boundary and ensures a future controller cannot accidentally rely on a path name as its authorization rule.

- [ ] **Step 5: Run the focused test and verify GREEN**

  Run: `./mvnw -B -Dtest=AuthorizationMatrixTest test`

  Expected: registered users and moderators receive `403`, Super Admin receives `200`, and unauthenticated requests receive `401`.

### Task 3: Prove the complete role matrix through method-level integration tests

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/AuthorizationMatrixTest.java`
- Verify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/SecurityUser.java`

**Interfaces:**
- Consumes: the three method annotations and the existing JWT filter.
- Produces: parameterized evidence for all CT-008 acceptance categories.

- [ ] **Step 1: Add a test-only probe controller with representative policies**

  Register a nested `@TestConfiguration` controller so the test exercises Spring method-security interception without implementing future domain workflows:

  ```java
  @RestController
  static class AuthorizationProbeController {
      @GetMapping("/api/public/test-discovery")
      String discovery() {
          return "public";
      }

      @PostMapping("/api/test/contribute")
      @RequiresRegisteredUser
      String contribute() {
          return "contribute";
      }

      @GetMapping("/api/test/moderate")
      @RequiresModerator
      String moderate() {
          return "moderate";
      }

      @GetMapping("/api/test/super-admin")
      @RequiresSuperAdmin
      String superAdmin() {
          return "super-admin";
      }
  }
  ```

  Permit only `/api/public/**` at the request-security layer so the public probe reaches the controller without credentials; the other probes must first authenticate and then be evaluated by `@PreAuthorize`.

- [ ] **Step 2: Add the parameterized role matrix**

  Cover these expected outcomes:

  ```text
  public discovery: guest 200, registered 200, moderator 200, super admin 200
  contribution:     guest 401, registered 200, moderator 200, super admin 200
  moderation:       guest 401, registered 403, moderator 200, super admin 200
  super admin:      guest 401, registered 403, moderator 403, super admin 200
  ```

  Use active persisted users and real bearer JWTs rather than `@WithMockUser`, because the test must preserve CT-007's database-backed account-status revocation behavior.

- [ ] **Step 3: Add authority-source regression tests**

  Assert that `new SecurityUser(user).getAuthorities()` contains exactly the persisted `UserRoles` authority and does not add a verification or badge authority. Keep the test focused on the current model; the approved-badge lifecycle belongs to CT-010.

- [ ] **Step 4: Add lifecycle rejection coverage**

  Reuse the existing inactive-account setup to issue a token, call `/api/test/contribute`, and assert `401`. Add an expired-token case if the current JWT test helper does not already cover a method-protected endpoint.

- [ ] **Step 5: Run the matrix and verify GREEN**

  Run: `./mvnw -B -Dtest=AuthorizationMatrixTest,InactiveAccountAuthenticationTest test`

  Expected: all guest, registered-user, moderator, Super Admin, verified-metadata, inactive-account, and expired-token cases pass with the exact `401`/`403` distinction.

### Task 4: Align the OpenAPI contract and phase documentation

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`
- Modify: `docs/tasks/phase_1_identity.md`
- Modify: `docs/TASKS_INDEX.md`

**Interfaces:**
- Consumes: endpoint annotations and the selected JWT bearer mechanism.
- Produces: documentation that distinguishes public operations from authenticated and role-restricted operations.

- [ ] **Step 1: Add a failing OpenAPI security assertion**

  Update the contract test to require a bearer scheme and role-specific documentation for the current user-list operation. The test must also assert that public planned discovery operations have an empty security requirement while planned admin operations require bearer authentication.

- [ ] **Step 2: Replace stale Basic-auth documentation**

  In `OpenApiConfig`, define `bearerAuth` as an HTTP bearer scheme using JWT. Update the generated `/users` operation description and remove the stale `basicAuth` assertions. Do not change the planned business response schemas.

- [ ] **Step 3: Document planned method policy categories**

  Keep the existing planned case paths, but add concise role metadata to the OpenAPI operations:

  ```json
  "x-crowdtrace-roles": ["MODERATOR", "SUPER_ADMIN"]
  ```

  Use no role metadata for public discovery, registered-user metadata for future contribution operations when they are added, moderator-or-above metadata for review/moderation, and Super Admin metadata for moderator management.

- [ ] **Step 4: Update the CT-008 implementation notes**

  In `docs/tasks/phase_1_identity.md`, record that CT-008 uses composed method-level `@PreAuthorize` annotations, that verified status is not an authority, and that the current `/users` endpoint is Super Admin-only. In `docs/TASKS_INDEX.md`, leave CT-008 pending until implementation and its matrix tests are verified; do not mark it complete from the plan alone.

- [ ] **Step 5: Run the contract tests and verify GREEN**

  Run: `./mvnw -B -Dtest=OpenApiContractTest test`

  Expected: the contract documents bearer authentication, public discovery, and the planned administrative role boundary without changing runtime business endpoints.

### Task 5: Run complete verification and hand off

**Files:**
- Verify only.

- [ ] **Step 1: Run focused identity and authorization tests**

  Run: `./mvnw -B -Dtest=MethodAuthorizationAnnotationTest,AuthorizationMatrixTest,InactiveAccountAuthenticationTest,OpenApiContractTest test`

  Expected: PASS.

- [ ] **Step 2: Run the full Maven suite**

  Run: `./mvnw -B test`

  Expected: PASS with no failures or errors. Existing notification-provider warnings may appear in the test logs but must not change the result.

- [ ] **Step 3: Inspect the changed-file set**

  Confirm only the planned authorization annotations, security/controller changes, tests, OpenAPI contract, and phase documentation changed. Preserve unrelated untracked files already present in the worktree.

## Completion Criteria

- `@EnableMethodSecurity` is active and the three composed annotations are used for role boundaries.
- Public discovery works without credentials.
- Registered users can reach contribution methods; moderators can reach moderation methods; Super Admins can reach all administrative methods.
- Wrong roles receive `403`; missing, expired, or revoked credentials receive `401`.
- Verified presentation metadata never grants a stronger authority.
- The current user-list endpoint is Super Admin-only.
- OpenAPI and phase documentation describe the same method-level policy.
- The full Maven suite passes.
