# CT-006 Identity Persistence Implementation Plan

> **For agentic workers:** Implement this plan task-by-task with test-first red/green cycles. Do not create commits in the shared workspace.

**Goal:** Replace the starter user schema with the CT-006 identity and verification-request persistence model while retaining numeric user IDs and protecting private fields.

**Architecture:** Use one initial Flyway migration for `users` and `verification_requests`. Map the tables to focused package-private-facing JPA entities with explicit enums, then expose only the public display name through the existing user projection. Repositories own email lookup and pending-verification queue access.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, H2 test database, JUnit 5, AssertJ, Jackson.

---

### Task 1: Replace the starter migration with CT-006 tables

**Files:**
- Delete: `src/main/resources/db/migration/V1__createUsers.sql`
- Create: `src/main/resources/db/migration/V1__create_identity_tables.sql`
- Test: `src/test/java/com/souldealers/crowdtracebackend/IdentityMigrationTest.java`

- [x] **Step 1: Write the failing migration test**

Create a Spring Boot test using the test H2 datasource with Flyway enabled and Hibernate schema generation disabled. Assert that `users` and `verification_requests` exist, the required columns exist, and the `users.email` and `verification_requests(status, created_at)` indexes exist.

- [x] **Step 2: Run the migration test and verify it fails**

Run: `./mvnw -q -Dtest=IdentityMigrationTest test`

Expected: FAIL because the current migration creates the starter schema and does not create `verification_requests` or the CT-006 column set.

- [x] **Step 3: Write the initial migration**

Create `V1__create_identity_tables.sql` with numeric identity primary keys, the eight `users` columns from the approved design, the verification-request columns, the user foreign key, unique email constraint, role/email/user/status indexes, and timestamp columns.

- [x] **Step 4: Run the migration test and verify it passes**

Run: `./mvnw -q -Dtest=IdentityMigrationTest test`

Expected: PASS with the schema and indexes detected in H2.

### Task 2: Map identity and verification entities with explicit enums

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/UserRoles.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/UserStatus.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerificationType.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/VerificationStatus.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/User.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/model/VerificationRequest.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/UserRepositoryTest.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/VerificationRequestRepositoryTest.java`

- [x] **Step 1: Update repository tests to the desired entity API**

Use `User.builder().email(...).passwordHash(...).displayName(...).role(UserRoles.REGISTERED_USER).accountStatus(UserStatus.ACTIVE)` and create verification requests with `VerificationType.IDENTITY` and `VerificationStatus.PENDING`. Add assertions that enum values round-trip and duplicate email persistence throws a data-integrity exception.

- [x] **Step 2: Run the repository tests and verify they fail**

Run: `./mvnw -q -Dtest=UserRepositoryTest,VerificationRequestRepositoryTest test`

Expected: FAIL because the existing entities and enums expose the old fields and values.

- [x] **Step 3: Implement the minimal entity mappings**

Map `User.id` and `VerificationRequest.id` to `Long` identity IDs. Map `passwordHash` to `password_hash` with Jackson ignored serialization, map lifecycle fields to `EnumType.STRING`, add timestamp auditing callbacks, and map verification reviewer/user relationships with `@ManyToOne(fetch = FetchType.LAZY)`.

- [x] **Step 4: Run repository tests and verify they pass**

Run: `./mvnw -q -Dtest=UserRepositoryTest,VerificationRequestRepositoryTest test`

Expected: PASS with enum persistence and unique-email enforcement.

### Task 3: Define repository queue queries

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/UserRepository.java`
- Create: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/VerificationRequestRepository.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/internal/repository/VerificationRequestRepositoryTest.java`

- [x] **Step 1: Add failing queue-order test**

Persist two pending requests with different creation times and one approved request. Assert that `findPendingRequests(Pageable)` returns only pending requests in oldest-first order. Also assert that `findByEmail(String)` returns the saved user.

- [x] **Step 2: Run the focused tests and verify the expected failure**

Run: `./mvnw -q -Dtest=UserRepositoryTest,VerificationRequestRepositoryTest test`

Expected: FAIL because the repository methods do not exist.

- [x] **Step 3: Implement repository methods**

Add `Optional<User> findByEmail(String email)` and a JPQL pending query ordered by `createdAt ASC` with `Pageable` to `VerificationRequestRepository`.

- [x] **Step 4: Run the focused tests and verify they pass**

Run: `./mvnw -q -Dtest=UserRepositoryTest,VerificationRequestRepositoryTest test`

Expected: PASS.

### Task 4: Protect public projections from private identity data

**Files:**
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/UserResponse.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/internal/service/UserServiceImpl.java`
- Test: `src/test/java/com/souldealers/crowdtracebackend/modules/identity/UserResponseTest.java`

- [x] **Step 1: Write the failing serialization test**

Serialize a `User` containing email and password hash and assert that JSON contains `displayName` but does not contain `email`, `passwordHash`, `password_hash`, role, or verification evidence. Assert the public `UserResponse` contains only `displayName`.

- [x] **Step 2: Run the test and verify it fails**

Run: `./mvnw -q -Dtest=UserResponseTest test`

Expected: FAIL because the current projection includes email and username and the entity exposes the old password field.

- [x] **Step 3: Implement the safe projection**

Reduce `UserResponse` to `displayName`, update `UserServiceImpl` to map only that field, and annotate the entity password hash with `@JsonIgnore` as defense-in-depth.

- [x] **Step 4: Run the test and verify it passes**

Run: `./mvnw -q -Dtest=UserResponseTest test`

Expected: PASS.

### Task 5: Update affected tests and verify the complete change

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`
- Modify: `docs/tasks/phase_1_identity.md`
- Modify: `docs/changelog.md`

- [x] **Step 1: Update the existing contract expectation**

Change the current `UserResponse` OpenAPI assertions from `username`, `email`, and `displayName` to the approved public projection containing `displayName` only; retain the endpoint/security contract.

- [x] **Step 2: Run all tests**

Run: `./mvnw test`

Expected: PASS with zero test failures.

- [x] **Step 3: Verify the final diff and migration inventory**

Run: `find src/main/resources/db/migration -maxdepth 1 -type f -print; ./mvnw -q -DskipTests compile`

Expected: only `V1__create_identity_tables.sql` is present and compilation exits successfully.

- [x] **Step 4: Update the codebase index/changelog notes**

Record CT-006 identity persistence in the existing phase/changelog documentation, listing the replacement migration, entities, repository queries, and privacy projection.
