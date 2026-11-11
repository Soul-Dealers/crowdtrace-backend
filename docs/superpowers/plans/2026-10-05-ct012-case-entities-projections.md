# CT-012 — Case Entities, Repositories, and Visibility Projections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Issue:** https://github.com/Soul-Dealers/crowdtrace-backend/issues/12
**Phase:** 2 — Case Registry and Submission
**Depends on:** CT-011 (`V12__create_case_tables.sql` — see `docs/superpowers/plans/2026-10-04-ct011-case-schema.md`, especially its "Handoff notes → CT-012")
**Unblocks:** CT-013 (submission), CT-014 (flags), CT-015 (files), phase 3 review queue, phase 4 public registry

**Goal:** Map the CT-011 schema to entities and repositories, and expose cases only through three separately mapped views — public, reporter-owned, and admin — enforced in one read service.

**Architecture:** Entities and repositories are `internal` to `modules.casefile`. Response records and a `CaseQueryService` interface are the module's public API (`modules.casefile`). An internal `CaseMapper` is the **only** code that turns entities into responses. Visibility (approved-only), ownership (reporter-scoped) and role (`@RequiresModerator`) are enforced in `CaseQueryServiceImpl`; there are no controllers in this task.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA, Spring Security method security, PostgreSQL 16 + Testcontainers, JUnit 5, AssertJ, ArchUnit (already on the test classpath via `spring-modulith-starter-test`).

**Spec:** `docs/product-spec.md` §5.1 (field visibility table), §5.2, §3.4; issue #12; CT-011 plan.

---

## Decisions

Settled with Edem during planning, plus a design review with Codex (gpt-6-sol).

| # | Decision | Rationale |
|---|---|---|
| D1 | **No controllers / endpoints.** `CaseQueryService` is the boundary | Issue scope is entities, repositories and mappers. Submission endpoints are CT-013; public search is phase 4. Rules live in the service so every future controller inherits them. |
| D2 | **The public mapper cannot see sensitive data** — `CaseMapper.toPublic(CaseRecord)` takes only the case row, and `CaseRecord` has no reference to `CaseSensitiveDetails` | The boundary is structural, not a remembered `@JsonIgnore`. A public response can't leak a field its mapper was never given. |
| D3 | **Public view = PRD §5.1 "Public" rows only**, no reporter identity, no `reviewStatus`, no flags | Matches the privacy table. `caseStatus` is public (drives the Found-Deceased banner). `reporterId` is "private identity" and omitted. |
| D4 | **Reporter view = public fields + `reviewStatus`, `submittedAt`, and their own sensitive details**; never `priorityMinor` / `duplicateFlag` | Edem's call: they entered it and may edit it (PRD §4.2). The flags are admin-only in §5.1 — a reporter seeing "duplicate" would read it as an accusation. |
| D5 | **Admin view = everything** + `reporterId`, reporter account email, flags, live file metadata, consent history | PRD §3.3: the queue surfaces the report, duplicate and minor flags. File metadata **never** includes `storage_key` (signed URLs are CT-015 / phase 6). |
| D6 | **Lists return summaries, details return full views** — `ReporterCaseSummaryResponse`, `AdminCaseSummaryResponse` | A page of 50 full admin views would load 50 sensitive rows and 50 email lookups. Summaries load only the `cases` row: fewer queries and less sensitive data in flight. The public list reuses `PublicCaseResponse` (no extra loads). |
| D7 | **Ownership miss → `NotFoundException`, not 403** | A 403 confirms the case id exists. Same for a non-approved case on the public read. |
| D8 | **`TAKEN_DOWN` / `REJECTED` stay visible to their reporter**, with `reviewStatus`; invisible publicly | Codex's point: the reporter must be able to see what happened to their case. |
| D9 | **Stable pagination: server-fixed order with `id` as the last tiebreaker; client `Sort` is ignored; size clamped to 1..50** | Equal timestamps otherwise reorder between pages. Ignoring client sort also closes a leak: sorting the public list by `duplicate_flag` would reveal an admin-only field through ordering. |
| D10 | **Reporter account email via a new identity API, `UserService.getAccountEmail(Long)`, itself `@RequiresModerator`** | `PublicUserResponse` is deliberately pseudonymous; email must not join it. One call per *detail* view only (D6), so no batch API is needed. A missing account → `null` email, not an error (soft-deleted reporter). |
| D11 | **Photos deferred to CT-015** | Edem's call. No storage port can produce a URL yet; a list of ids would be a placeholder clients can't use. |
| D12 | **Absent sensitive details are valid** → `sensitiveDetails: null` | Phase-6 retention deletes the row (CT-011 D7). Mappers must not throw. |
| D13 | **ArchUnit rule: no `@RestController` method may return or accept a JPA `@Entity` type** | The issue states "controllers must never serialize entities directly". A rule now means CT-013's first controller is checked automatically. *(Codex rated it low value with no controllers yet; kept because it's ~20 lines and guards the next task.)* |

### Out of scope

Endpoints, submission, status changes, publication/approval, duplicate logic, photo URLs, signed file access, public search filters (phase 4), audit (CT-020).

---

## Global Constraints

- **Java 21**, Spring Boot 4.1. No new dependencies.
- **Commits carry no Claude attribution** (`~/.claude/CLAUDE.md`). Style: `feat(casefile): … [CT-012]`.
- **Before tests:** `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true`.
- **Repository and service tests boot Postgres + Flyway + `ddl-auto: validate`.** Plain `@DataJpaTest` here is H2 `create-drop` and never runs `V12` — its partial indexes and CHECKs would be untested.
- **Name the case entity `CaseRecord`** — `CASE` is an HQL keyword.
- **`casefile` never imports `modules.identity.internal`.** `reporterId`, `uploadedBy`, `userId` are plain `Long`s. `CrowdtraceModulesTest` stays green.
- **`open-in-view: false`.** All mapping happens inside `@Transactional(readOnly = true)` service methods.
- **Enum values match the V12 CHECKs exactly.**
- Suite green at every commit.

## Review Focus

1. **A new field added to `CaseRecord` silently appearing in public JSON.** The public contract test is an **exact allowlist** of field names, not a denylist. Pinned by `CaseProjectionContractTest.publicJsonHasExactlyThePublicFields` (Task 4).
2. **Two cases with identical timestamps straddling a page boundary** — must not duplicate or vanish across pages. Pinned by `CaseRecordRepositoryTest.pagesAreStableWhenTimestampsTie` (Task 2).
3. **A client passing `sort=duplicateFlag` or `size=10000`** — ignored / clamped. Pinned by `CaseQueryServiceTest.ignoresClientSortAndClampsPageSize` (Task 5).
4. **A reporter whose sensitive row was purged** — detail views still render. Pinned by `CaseProjectionContractTest.mapsACaseWhoseSensitiveDetailsWerePurged` (Task 4).
5. **Enum value present in Java but rejected by the DB CHECK** (the V9 bug class). Pinned by `CaseRecordRepositoryTest.everyEnumValueIsAcceptedByTheDatabase` (Task 2).

---

## File Structure

All under `src/main/java/com/souldealers/crowdtracebackend/modules/casefile/` unless stated.

| Path | Responsibility |
|---|---|
| `ReviewStatus`, `CaseStatus`, `Gender`, `GhanaRegion`, `CaseFilePurpose`, `FileVisibility`, `ConsentType`, `ConsentSource` (`.java`) | Public vocabulary |
| `PublicCaseResponse.java` | Public view (record) |
| `ReporterCaseResponse.java`, `ReporterCaseSummaryResponse.java` | Reporter detail / list row |
| `AdminCaseResponse.java`, `AdminCaseSummaryResponse.java` | Admin detail / queue row |
| `SensitiveDetailsResponse.java`, `CaseFileMetadataResponse.java`, `CaseConsentResponse.java` | Nested parts of the detail views |
| `CaseQueryService.java` | Public read API |
| `internal/model/CaseRecord.java`, `CaseSensitiveDetails.java`, `CaseFile.java`, `CaseConsent.java` | Entities |
| `internal/repository/CaseRecordRepository.java`, `CaseSensitiveDetailsRepository.java`, `CaseFileRepository.java`, `CaseConsentRepository.java` | Repositories |
| `internal/CaseMapper.java` | The only entity → response code |
| `internal/CasePageRequests.java` | Fixed sort + clamp |
| `internal/CaseQueryServiceImpl.java` | Visibility, ownership, role |
| `modules/identity/UserService.java`, `internal/service/UserServiceImpl.java` | + `getAccountEmail` |
| `src/test/.../modules/casefile/**` | Tests per task |
| `src/test/.../CaseArchitectureTest.java` | ArchUnit rule (D13) |

---

### Task 1: Enums and entities, validated against V12

**Files:**
- Create: the eight enums; `internal/model/CaseRecord.java`, `CaseSensitiveDetails.java`, `CaseFile.java`, `CaseConsent.java`
- Test: existing `SchemaValidationTest` (Postgres + `validate`); new `src/test/.../modules/casefile/internal/model/CaseFileTest.java`

**Interfaces — Produces:**
- `CaseRecord`: `Long id`, `Long reporterId`, `String fullName`, `int age`, `Gender gender`, `LocalDate lastSeenDate`, `GhanaRegion region`, `String lastSeenLocation`, `physicalDescription`, `clothing`, `circumstances`, `publicContactNumber`, `ReviewStatus reviewStatus`, `CaseStatus caseStatus`, `String closingStatement`, `boolean priorityMinor`, `boolean duplicateFlag`, `Long version` (`@Version`), `LocalDateTime submittedAt, approvedAt, resolvedAt, closedAt, createdAt, updatedAt`.
- `CaseSensitiveDetails`: `Long caseId` (`@Id`), `CaseRecord caseRecord` (`@MapsId @OneToOne(fetch = LAZY, optional = false) @JoinColumn(name = "case_id")`), `reporterRelationship`, `medicalConditions`, `knownAssociates`, `vehicleInfo`, `socialMediaHandles`, `createdAt`, `updatedAt`.
- `CaseFile`: `Long id`, `Long caseId`, `Long uploadedBy`, `CaseFilePurpose purpose`, `FileVisibility visibility`, `String storageKey`, `contentType`, `long sizeBytes`, `String checksumSha256`, `LocalDateTime uploadedAt, attachedAt, deletedAt`; `void attachTo(Long caseId, LocalDateTime at)`.
- `CaseConsent`: `Long id`, `Long caseId`, `Long userId`, `ConsentType consentType`, `String consentVersion`, `ConsentSource source`, `LocalDateTime acceptedAt` — getters + builder only, every column `updatable = false`.

- [ ] **Step 1: Write the enums** (values copied from V12 CHECKs):

```java
package com.souldealers.crowdtracebackend.modules.casefile;

/** Moderation state. Independent of {@link CaseStatus}, which exists only once a case is published. */
public enum ReviewStatus { SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, TAKEN_DOWN }
```

`CaseStatus { MISSING, FOUND_SAFE, FOUND_DECEASED, CLOSED }`, `Gender { MALE, FEMALE, UNKNOWN }`, `GhanaRegion { AHAFO, ASHANTI, BONO, BONO_EAST, CENTRAL, EASTERN, GREATER_ACCRA, NORTH_EAST, NORTHERN, OTI, SAVANNAH, UPPER_EAST, UPPER_WEST, VOLTA, WESTERN, WESTERN_NORTH }`, `CaseFilePurpose { REPORT, PHOTO }`, `FileVisibility { PRIVATE, PUBLIC }`, `ConsentType { SENSITIVE_DATA_COLLECTION }`, `ConsentSource { WEB, MOBILE, API }`.

- [ ] **Step 2: Prove `SchemaValidationTest` bites.** Write `CaseRecord` with one misspelled column (`@Column(name = "reporterid")`). Run `./mvnw test -Dtest=SchemaValidationTest` → expect FAIL `missing column [reporterid]`. Fix it.

- [ ] **Step 3: Write the entities**, Lombok style as `identity.internal.model.VerificationRequest` (`@Getter @Setter @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor(access = PRIVATE) @Builder`, `@Enumerated(EnumType.STRING)`). Key parts:

```java
@Entity
@Table(name = "cases")
public class CaseRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** users.id. A plain id, not a User: identity's model is internal to its module. */
    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    /** Null until the case is approved (V12 cases_*_outcome_check). */
    @Enumerated(EnumType.STRING) @Column(name = "case_status", length = 32)
    private CaseStatus caseStatus;

    @Version
    private Long version;

    // No reference to CaseSensitiveDetails, on purpose: loading a case must never load
    // sensitive data. Admin and reporter views fetch it explicitly (CT-012 D2).

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (submittedAt == null) submittedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
```

```java
// CaseFile
public void attachTo(Long caseId, LocalDateTime at) {
    if (this.caseId != null) {
        throw new IllegalStateException("File is already attached to a case");
    }
    this.caseId = caseId;
    this.attachedAt = at;   // V12 case_files_attachment_check requires both together
}
```

`CaseFile.uploadedAt` and `CaseConsent.acceptedAt` default to `now()` in `@PrePersist` when null.

- [ ] **Step 4: Unit test `attachTo`** in `CaseFileTest`: attaching sets both fields; attaching twice throws `IllegalStateException`.

- [ ] **Step 5: Run** `./mvnw test -Dtest='SchemaValidationTest,CrowdtraceModulesTest,CaseFileTest'` → PASS. Then the full suite.

- [ ] **Step 6: Commit** — `feat(casefile): map the case schema to entities [CT-012]`

---

### Task 2: Repositories with Postgres-backed tests

**Files:**
- Create: four repositories in `internal/repository/`
- Create: `src/test/.../modules/casefile/internal/repository/CasePostgresTestSupport.java` (abstract), `CaseRecordRepositoryTest.java`, `CaseFileRepositoryTest.java`, `CaseConsentRepositoryTest.java`

**Interfaces — Produces:**

```java
public interface CaseRecordRepository extends JpaRepository<CaseRecord, Long> {
    // Pageables passed in are always unsorted (CasePageRequests); ordering is in the name, id last.
    Page<CaseRecord> findByReporterIdOrderByCreatedAtDescIdDesc(Long reporterId, Pageable pageable);
    Optional<CaseRecord> findByIdAndReporterId(Long id, Long reporterId);
    Optional<CaseRecord> findByIdAndReviewStatus(Long id, ReviewStatus reviewStatus);
    Page<CaseRecord> findByReviewStatusOrderByApprovedAtDescIdDesc(ReviewStatus reviewStatus, Pageable pageable);
    Page<CaseRecord> findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAscIdAsc(
            Collection<ReviewStatus> statuses, Pageable pageable);
}

public interface CaseSensitiveDetailsRepository extends JpaRepository<CaseSensitiveDetails, Long> {}

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {
    List<CaseFile> findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndPurposeAndDeletedAtIsNull(Long caseId, CaseFilePurpose purpose);
    List<CaseFile> findByUploadedByAndCaseIdIsNull(Long uploadedBy);
}

public interface CaseConsentRepository extends JpaRepository<CaseConsent, Long> {
    List<CaseConsent> findByCaseIdOrderByAcceptedAtAscIdAsc(Long caseId);
    boolean existsByCaseIdAndConsentType(Long caseId, ConsentType consentType);
}
```

- [ ] **Step 1: Write `CasePostgresTestSupport`** — copy the container + `@DynamicPropertySource` block from `IdentityMigrationTest`; annotate `@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "cors.allowed-origins=http://localhost"})`, `@ActiveProfiles("test")`, `@Testcontainers`, `@Transactional` (each test rolls back), `@MockitoBean NotificationService`. Helpers:

```java
@Autowired protected JdbcTemplate jdbc;

protected long user(String email) {
    return jdbc.queryForObject("""
            INSERT INTO users (email, password_hash, display_name, role, account_status)
            VALUES (?, 'x', 'Reporter', 'REGISTERED_USER', 'ACTIVE') RETURNING id""", Long.class, email);
}

protected CaseRecord.CaseRecordBuilder aCase(long reporterId) {
    return CaseRecord.builder().reporterId(reporterId).fullName("Kofi Mensah").age(30)
            .gender(Gender.MALE).lastSeenDate(LocalDate.of(2026, 9, 30)).region(GhanaRegion.GREATER_ACCRA)
            .lastSeenLocation("Madina").physicalDescription("Slim").clothing("Blue shirt")
            .circumstances("Did not return").publicContactNumber("+233200000000")
            .reviewStatus(ReviewStatus.SUBMITTED);
}

protected static LocalDateTime at(int hour) { return LocalDateTime.of(2026, 10, 1, hour, 0); }
```

- [ ] **Step 2: Write the failing tests.**

`CaseRecordRepositoryTest`:
- `listsOnlyTheReportersOwnCasesNewestFirst` — two reporters; reporter A's page is exactly A's two cases, newest first.
- `scopesLookupToTheOwner` — `findByIdAndReporterId(caseOfA, B)` empty; with A present.
- `findsOnlyApprovedCasesForPublicLookup` — `findByIdAndReviewStatus(submittedCase, APPROVED)` empty.
- `reviewQueueShowsMinorsFirstThenOldest` — adult@9, minor@10, minor@11, approved@8 → `[minor@10, minor@11, adult@9]`.
- `pagesAreStableWhenTimestampsTie`:

```java
@Test
void pagesAreStableWhenTimestampsTie() {
    long r = user("tie@x.com");
    List<Long> ids = IntStream.range(0, 5)
            .mapToObj(i -> cases.save(aCase(r).createdAt(at(9)).submittedAt(at(9)).build()).getId())
            .toList();
    cases.flush();

    List<Long> seen = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
        cases.findByReporterIdOrderByCreatedAtDescIdDesc(r, PageRequest.of(page, 2))
                .forEach(c -> seen.add(c.getId()));
    }

    assertThat(seen).containsExactlyElementsOf(ids.reversed());   // no duplicates, none missing
}
```

- `everyEnumValueIsAcceptedByTheDatabase` — `@ParameterizedTest @EnumSource(GhanaRegion.class)`, same for `Gender`, `ReviewStatus` (set `caseStatus = MISSING` when `APPROVED`/`TAKEN_DOWN`, else null), and `CaseStatus` (with `reviewStatus = APPROVED`); `saveAndFlush` each.
- `optimisticLockRejectsAStaleUpdate` — needs real commits, so annotate the method `@Transactional(propagation = NOT_SUPPORTED)` and clean up in a `@AfterEach` (`DELETE FROM cases`/users rows it created). Load the case twice via `findById` in two `TransactionTemplate` calls, save the first copy with a changed `closingStatement`, then saving the stale copy throws `ObjectOptimisticLockingFailureException`.
- `sensitiveDetailsShareTheCaseId` — save details for a case; `findById(case.getId())` returns them; another case returns empty.

`CaseFileRepositoryTest`: `listsOnlyLiveFilesOfTheCaseInUploadOrder` (soft-deleted and other-case files excluded), `detectsWhetherAReportIsAttached`, `findsUnattachedUploadsByUploader`, `recordsWhoUploaded`.

`CaseConsentRepositoryTest`: `keepsConsentHistoryInAcceptanceOrder` (two versions, explicit `acceptedAt`), `rejectsTheSameVersionTwice` (`DataIntegrityViolationException` on `saveAndFlush`), `reportsWhetherConsentWasGiven`.

- [ ] **Step 3: Run** `./mvnw test -Dtest='Case*RepositoryTest'` → FAIL (repositories missing).
- [ ] **Step 4: Write the four repositories** exactly as above.
- [ ] **Step 5: Run** → PASS; full suite; confirm the Postgres tests ran (surefire count), not skipped.
- [ ] **Step 6: Commit** — `feat(casefile): add case repositories with stable ordering [CT-012]`

---

### Task 3: Identity seam — moderator-only account email lookup

**Files:**
- Modify: `modules/identity/UserService.java`, `modules/identity/internal/service/UserServiceImpl.java`
- Create: `src/test/.../modules/identity/AccountEmailLookupTest.java`

**Interfaces — Produces:** `Optional<String> UserService.getAccountEmail(Long userId)` annotated `@RequiresModerator`.

- [ ] **Step 1: Failing test** (`@SpringBootTest`, `@ActiveProfiles("test")`, `@MockitoBean NotificationService`, like `PublicUserProjectionTest`):

```java
@Test
@WithMockUser(authorities = "MODERATOR")
void returnsTheAccountEmailToAModerator() {
    User user = saveUser("reporter@example.com");
    assertThat(userService.getAccountEmail(user.getId())).contains("reporter@example.com");
}

@Test
@WithMockUser(authorities = "MODERATOR")
void returnsEmptyForAnUnknownUser() {
    assertThat(userService.getAccountEmail(999_999L)).isEmpty();
}

@Test
@WithMockUser(authorities = "REGISTERED_USER")
void refusesARegisteredUser() {
    assertThatThrownBy(() -> userService.getAccountEmail(1L))
            .isInstanceOf(AuthorizationDeniedException.class);
}
```

- [ ] **Step 2: Run** → FAIL (method missing).
- [ ] **Step 3: Implement.**

```java
// UserService
/**
 * The private account email of a user, for admin case views only (CT-012). Never put this
 * on a shared projection — {@link PublicUserResponse} is deliberately pseudonymous. Empty
 * when no such user exists, so a removed reporter does not break an admin view.
 */
@RequiresModerator
Optional<String> getAccountEmail(Long userId);
```

```java
// UserServiceImpl
@Override
public Optional<String> getAccountEmail(Long userId) {
    return userRepository.findById(userId).map(User::getEmail);
}
```

Note: if Spring Security doesn't pick up the annotation from the interface, also place `@RequiresModerator` on the impl method; the denial test proves which.

- [ ] **Step 4: Run** → PASS; `MethodAuthorizationAnnotationTest` and `CrowdtraceModulesTest` still green.
- [ ] **Step 5: Commit** — `feat(identity): add a moderator-only account email lookup for case review [CT-012]`

---

### Task 4: Response records, `CaseMapper`, and projection contract tests

**Files:**
- Create: the eight response records in `modules/casefile/`; `internal/CaseMapper.java`
- Create: `src/test/.../modules/casefile/internal/CaseProjectionContractTest.java` (plain JUnit — no Spring)

**Interfaces — Produces:**

```java
public record PublicCaseResponse(
        Long id, String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        CaseStatus caseStatus, String closingStatement, String publicContactNumber,
        LocalDateTime approvedAt, LocalDateTime resolvedAt) {}

public record SensitiveDetailsResponse(String reporterRelationship, String medicalConditions,
        String knownAssociates, String vehicleInfo, String socialMediaHandles) {}

public record ReporterCaseResponse(
        Long id, String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        String publicContactNumber, ReviewStatus reviewStatus, CaseStatus caseStatus,
        String closingStatement, LocalDateTime submittedAt, LocalDateTime approvedAt,
        SensitiveDetailsResponse sensitiveDetails /* null after retention purge */) {}

public record ReporterCaseSummaryResponse(Long id, String fullName, ReviewStatus reviewStatus,
        CaseStatus caseStatus, LocalDateTime submittedAt) {}

public record CaseFileMetadataResponse(Long id, CaseFilePurpose purpose, FileVisibility visibility,
        String contentType, long sizeBytes, LocalDateTime uploadedAt) {}   // never storageKey

public record CaseConsentResponse(ConsentType consentType, String consentVersion,
        ConsentSource source, LocalDateTime acceptedAt) {}

public record AdminCaseResponse(
        Long id, Long reporterId, String reporterAccountEmail /* null if account gone */,
        String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        String publicContactNumber, ReviewStatus reviewStatus, CaseStatus caseStatus,
        String closingStatement, boolean priorityMinor, boolean duplicateFlag,
        LocalDateTime submittedAt, LocalDateTime approvedAt, LocalDateTime resolvedAt, LocalDateTime closedAt,
        SensitiveDetailsResponse sensitiveDetails, List<CaseFileMetadataResponse> files,
        List<CaseConsentResponse> consents) {}

public record AdminCaseSummaryResponse(Long id, String fullName, int age, GhanaRegion region,
        ReviewStatus reviewStatus, boolean priorityMinor, boolean duplicateFlag, LocalDateTime submittedAt) {}
```

```java
// internal/CaseMapper.java — package-private constructor, static methods; the only entity → response code.
static PublicCaseResponse toPublic(CaseRecord c)
static ReporterCaseResponse toReporter(CaseRecord c, @Nullable CaseSensitiveDetails d)
static ReporterCaseSummaryResponse toReporterSummary(CaseRecord c)
static AdminCaseResponse toAdmin(CaseRecord c, @Nullable CaseSensitiveDetails d, @Nullable String reporterEmail,
                                 List<CaseFile> liveFiles, List<CaseConsent> consents)
static AdminCaseSummaryResponse toAdminSummary(CaseRecord c)
```

Each record gets a one-line javadoc stating its audience and pointing at PRD §5.1.

- [ ] **Step 1: Write the failing contract tests.** Build fixtures with distinctive sentinel strings so a leak is detectable anywhere in the JSON. Serialize with the same `ObjectMapper` `UserResponseTest` uses (it already handles `LocalDateTime`).

```java
private static final String MEDICAL = "SENTINEL-MEDICAL", ASSOCIATES = "SENTINEL-ASSOCIATES",
        VEHICLE = "SENTINEL-VEHICLE", SOCIALS = "SENTINEL-SOCIALS", RELATION = "SENTINEL-RELATION",
        EMAIL = "sentinel-reporter@example.com", STORAGE_KEY = "SENTINEL-STORAGE-KEY";

@Test
void publicJsonHasExactlyThePublicFields() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(CaseMapper.toPublic(fullCase())));

    assertThat(fieldNames(json)).containsExactlyInAnyOrder(
            "id", "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
            "physicalDescription", "clothing", "circumstances", "caseStatus", "closingStatement",
            "publicContactNumber", "approvedAt", "resolvedAt");
}

@Test
void publicJsonCarriesNoAdminOnlyValue() throws Exception {
    String json = mapper.writeValueAsString(CaseMapper.toPublic(fullCase()));
    assertThat(json).doesNotContain(MEDICAL, ASSOCIATES, VEHICLE, SOCIALS, RELATION, EMAIL, STORAGE_KEY)
            .doesNotContain("reporterId", "priorityMinor", "duplicateFlag", "reviewStatus", "submittedAt");
}

@Test
void reporterJsonHasTheirDetailsButNoReviewFlags() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(
            CaseMapper.toReporter(fullCase(), fullDetails())));
    assertThat(fieldNames(json)).containsExactlyInAnyOrder(
            "id", "fullName", "age", "gender", "lastSeenDate", "region", "lastSeenLocation",
            "physicalDescription", "clothing", "circumstances", "publicContactNumber", "reviewStatus",
            "caseStatus", "closingStatement", "submittedAt", "approvedAt", "sensitiveDetails");
    assertThat(json.get("sensitiveDetails").get("medicalConditions").asText()).isEqualTo(MEDICAL);
}

@Test
void reporterSummaryHasNoSensitiveOrFlagFields() throws Exception {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(CaseMapper.toReporterSummary(fullCase())));
    assertThat(fieldNames(json)).containsExactlyInAnyOrder("id", "fullName", "reviewStatus", "caseStatus", "submittedAt");
}

@Test
void adminJsonHasEverythingExceptStorageKeys() throws Exception {
    String raw = mapper.writeValueAsString(CaseMapper.toAdmin(
            fullCase(), fullDetails(), EMAIL, List.of(reportFile()), List.of(consent())));
    assertThat(raw).contains(MEDICAL, EMAIL, "\"priorityMinor\":true", "\"duplicateFlag\":true")
            .doesNotContain(STORAGE_KEY, "storageKey", "checksum");
}

@Test
void adminSummaryCarriesFlagsButNoSensitiveData() throws Exception {
    String raw = mapper.writeValueAsString(CaseMapper.toAdminSummary(fullCase()));
    assertThat(raw).contains("priorityMinor", "duplicateFlag").doesNotContain(MEDICAL, EMAIL);
}

@Test
void mapsACaseWhoseSensitiveDetailsWerePurged() {
    assertThat(CaseMapper.toReporter(fullCase(), null).sensitiveDetails()).isNull();
    assertThat(CaseMapper.toAdmin(fullCase(), null, null, List.of(), List.of()).sensitiveDetails()).isNull();
}

private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
}
```

`fullCase()` builds an `APPROVED` / `MISSING` `CaseRecord` with **every** field set (`priorityMinor = true`, `duplicateFlag = true`, `reporterId = 42L`, `id` set via the builder). `reportFile()` sets `storageKey = STORAGE_KEY`.

Note: `null` record fields still serialize as keys (Jackson default), which is why the allowlist holds for `closingStatement` etc. If the app's mapper is configured with `NON_NULL`, use the app mapper and adjust — check `UserResponseTest` first.

- [ ] **Step 2: Run** `./mvnw test -Dtest=CaseProjectionContractTest` → FAIL (records/mapper missing).
- [ ] **Step 3: Write the records and `CaseMapper`.** Field-by-field copies; `toAdmin` maps files via `CaseFileMetadataResponse` (drops `storageKey`, `checksumSha256`, `uploadedBy`) and consents via `CaseConsentResponse`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `feat(casefile): add public, reporter and admin case projections [CT-012]`

---

### Task 5: `CaseQueryService` — visibility, ownership, role, stable paging (+ architecture rule)

**Files:**
- Create: `modules/casefile/CaseQueryService.java`, `internal/CaseQueryServiceImpl.java`, `internal/CasePageRequests.java`
- Create: `src/test/.../modules/casefile/CaseQueryServiceTest.java` (extends `CasePostgresTestSupport`), `internal/CasePageRequestsTest.java`, `src/test/.../CaseArchitectureTest.java`
- Modify: `docs/changelog.md`

**Interfaces:**
- Consumes: Tasks 1–4; `UserService.getAccountEmail` (Task 3); `NotFoundException` (`shared`).
- Produces (CT-013 controllers, phase 3/4 call these):

```java
public interface CaseQueryService {
    /** Approved cases only; anything else is NotFound. */
    PublicCaseResponse getPublicCase(Long caseId);
    PagedResponse<PublicCaseResponse> listPublicCases(Pageable pageable);

    /** reporterId must come from the authenticated principal, never from the request. */
    ReporterCaseResponse getOwnCase(Long caseId, Long reporterId);
    PagedResponse<ReporterCaseSummaryResponse> listOwnCases(Long reporterId, Pageable pageable);

    @RequiresModerator AdminCaseResponse getAdminCase(Long caseId);
    @RequiresModerator PagedResponse<AdminCaseSummaryResponse> listReviewQueue(Pageable pageable);
}
```

```java
// internal/CasePageRequests.java
final class CasePageRequests {
    static final int MAX_PAGE_SIZE = 50;

    /** Keeps page + size only. Ordering is fixed by each repository method (id last), so a
     *  client sort can neither break page stability nor order by an admin-only column. */
    static Pageable fixed(Pageable requested) {
        int size = Math.clamp(requested.isPaged() ? requested.getPageSize() : 20, 1, MAX_PAGE_SIZE);
        int page = requested.isPaged() ? Math.max(requested.getPageNumber(), 0) : 0;
        return PageRequest.of(page, size);
    }
}
```

- [ ] **Step 1: Unit test `CasePageRequests`:** sort is dropped; `size = 10_000` → 50; `size = 0` impossible via `PageRequest`, so test `Pageable.unpaged()` → page 0, size 20.

- [ ] **Step 2: Failing service tests** (`CaseQueryServiceTest`, Postgres, rolled back):
- `publicReadReturnsAnApprovedCase` / `publicReadHidesSubmittedRejectedAndTakenDownCases` → `NotFoundException` for each of the three.
- `publicListContainsOnlyApprovedCasesNewestApprovalFirst`.
- `ownCaseIsReturnedWithSensitiveDetails`; `anotherReportersCaseIsNotFound` (not `AccessDenied` — D7).
- `ownCaseListIncludesRejectedAndTakenDownCases` (D8).
- `@WithMockUser(authorities = "MODERATOR") adminCaseIncludesReporterEmailFlagsLiveFilesAndConsents` — soft-deleted file absent; consents in acceptance order.
- `@WithMockUser(authorities = "MODERATOR") adminCaseWithAMissingAccountHasNullEmail` — `@MockitoSpyBean UserService`, stub `getAccountEmail(reporterId)` to return `Optional.empty()`; the response's `reporterAccountEmail` is null and nothing throws.
- `@WithMockUser(authorities = "REGISTERED_USER") adminReadsAreDeniedToRegisteredUsers` — both admin methods throw `AuthorizationDeniedException`.
- `@WithMockUser(authorities = "MODERATOR") reviewQueueShowsMinorsFirstAndExcludesDecidedCases`.
- `ignoresClientSortAndClampsPageSize` — call `listOwnCases(r, PageRequest.of(0, 10_000, Sort.by("duplicateFlag")))`; result `size()` is 50 and order is newest-first.
- `mappingWorksWithoutOpenSessionInView` — implicit in all of the above (no `LazyInitializationException`); no separate test.

- [ ] **Step 3: Run** → FAIL.

- [ ] **Step 4: Implement `CaseQueryServiceImpl`** (`@Service`, `@Transactional(readOnly = true)` on the class, constructor injection):

```java
@Override
public PublicCaseResponse getPublicCase(Long caseId) {
    return caseRepository.findByIdAndReviewStatus(caseId, ReviewStatus.APPROVED)
            .map(CaseMapper::toPublic)
            .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
}

@Override
public ReporterCaseResponse getOwnCase(Long caseId, Long reporterId) {
    CaseRecord c = caseRepository.findByIdAndReporterId(caseId, reporterId)
            .orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
    return CaseMapper.toReporter(c, sensitiveRepository.findById(caseId).orElse(null));
}

@Override
@RequiresModerator
public AdminCaseResponse getAdminCase(Long caseId) {
    CaseRecord c = caseRepository.findById(caseId).orElseThrow(() -> new NotFoundException(CASE_NOT_FOUND));
    return CaseMapper.toAdmin(c,
            sensitiveRepository.findById(caseId).orElse(null),
            userService.getAccountEmail(c.getReporterId()).orElse(null),
            fileRepository.findByCaseIdAndDeletedAtIsNullOrderByUploadedAtAscIdAsc(caseId),
            consentRepository.findByCaseIdOrderByAcceptedAtAscIdAsc(caseId));
}

@Override
@RequiresModerator
public PagedResponse<AdminCaseSummaryResponse> listReviewQueue(Pageable pageable) {
    return PagedResponse.from(caseRepository
            .findByReviewStatusInOrderByPriorityMinorDescSubmittedAtAscIdAsc(
                    List.of(ReviewStatus.SUBMITTED, ReviewStatus.UNDER_REVIEW), CasePageRequests.fixed(pageable))
            .map(CaseMapper::toAdminSummary));
}
// listPublicCases / listOwnCases: same shape with their repository method + CasePageRequests.fixed.
```

`CASE_NOT_FOUND = "Case not found"` — add to `shared/CustomMessages.java` if that's where `USER_NOT_FOUND_MSG` lives; otherwise a private constant. Put `@RequiresModerator` on both the interface and the impl methods (see Task 3 note).

- [ ] **Step 5: Architecture rule** (`CaseArchitectureTest`, ArchUnit, D13):

```java
@Test
void noControllerExposesAnEntity() {
    JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.souldealers.crowdtracebackend");

    methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should(new ArchCondition<>("not return or accept a JPA entity") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    Stream.concat(Stream.of(method.getRawReturnType()), method.getRawParameterTypes().stream())
                            .filter(type -> type.isAnnotatedWith(Entity.class))
                            .forEach(type -> events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " exposes entity " + type.getName())));
                }
            })
            .allowEmptyShould(true)
            .check(classes);
}
```

Run it; it must pass against the existing identity controllers too. (Generic return types like `ApiResponse<User>` are not caught by raw types — acceptable; the casefile responses are records by construction.)

- [ ] **Step 6: Run** `./mvnw test -Dtest='CaseQueryServiceTest,CasePageRequestsTest,CaseArchitectureTest'` → PASS; then the full suite with Docker env vars; confirm Postgres tests ran.

- [ ] **Step 7: Changelog + commit.** Add to `docs/changelog.md`: `- CT-012: case entities, repositories, and public/reporter/admin projections behind CaseQueryService.`

```bash
git commit -m "feat(casefile): enforce case visibility, ownership and moderator access in CaseQueryService [CT-012]"
```

---

## Acceptance-criteria trace

| Acceptance criterion | Where |
|---|---|
| Public mapping excludes every admin-only field and private identity | D2/D3; `publicJsonHasExactlyThePublicFields`, `publicJsonCarriesNoAdminOnlyValue`; `publicReadHidesSubmittedRejectedAndTakenDownCases` |
| Reporter mapping is ownership-scoped | `findByIdAndReporterId`; `anotherReportersCaseIsNotFound`; `reporterJsonHasTheirDetailsButNoReviewFlags` |
| Admin mapping requires role authorization | `@RequiresModerator` on `getAdminCase`, `listReviewQueue`, `getAccountEmail`; `adminReadsAreDeniedToRegisteredUsers`, `refusesARegisteredUser` |
| Pagination is stable | D9; `pagesAreStableWhenTimestampsTie`, `ignoresClientSortAndClampsPageSize` |
| Controllers never serialize entities | D2 (records only), `CaseArchitectureTest` |
| Test: sensitive fields absent from public JSON | `CaseProjectionContractTest` |

## Handoff notes

- **CT-013:** controllers resolve `reporterId` from `Authentication` — identity will need a public `Long getUserId(String email)` (or a principal accessor) because `SecurityUser` wraps the internal `User`. Return `ReporterCaseResponse` / a safe reference from submission; never an entity (`CaseArchitectureTest` will catch it).
- **CT-015:** add photos to `PublicCaseResponse` / `ReporterCaseResponse` once the storage port can produce URLs; extend the allowlist tests in the same change.
- **Phase 3:** the review queue and admin detail are ready for the governance controllers; add review notes/source when `case_reviews` exists.
- **Phase 4:** public search filters (name, region, status, age, gender) extend `listPublicCases`; keep server-fixed ordering with `id` last.
