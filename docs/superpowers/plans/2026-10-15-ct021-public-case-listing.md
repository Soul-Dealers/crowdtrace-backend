# CT-021 Public Case Listing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guests can search the public registry with `GET /api/public/cases`, using name, region, age, gender, status, last-seen and approval-date filters. Results are stably ordered, bounded, paginated and indexed, and include only approved cases.

**Architecture:** `discovery` owns the HTTP contract (controller and filter record). `casefile` owns visibility, querying, projection and thumbnail selection, behind `CaseQueryService.searchPublicCases(PublicCaseSearchQuery, Pageable)`. The query is a JPA `Specification` with one predicate per supplied filter, as in `CaseReviewQueueSpecification`. V20 adds `pg_trgm` and indexes that lead with `review_status`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring Data JPA (Hibernate 6), PostgreSQL 16 + `pg_trgm`, Flyway, Testcontainers.

**Spec:** GitHub issue #21, `docs/tasks/phase_4_discovery.md` (CT-021), and the CT-018 handoffs in `docs/superpowers/plans/2026-10-14-ct018-case-lifecycle.md`.

## Global Constraints

- Public rows are always `review_status = 'APPROVED'`. Pending, rejected and taken-down cases never contribute rows, totals or thumbnails.
- Every item is built from `CaseMapper.toPublic`, so `FOUND_DECEASED` withholding and `presentation` hold (CT-018 handoff).
- No storage keys, URLs, reporter ids, review data, sensitive details or admin fields in public JSON.
- No frontend-only filtering. Every filter is enforced in SQL.
- Tests need OrbStack: `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true`.
- Commits: conventional, suffixed `[CT-021]`, no attribution trailers.
- Audit tables are untouched. This task is read-only.

## Decisions

| # | Decision |
|---|---|
| D1 | Endpoint `GET /api/public/cases`. The controller lives in `modules/discovery`; casefile keeps all rules. The old `listPublicCases(Pageable)` is replaced by `searchPublicCases`. |
| D2 | Filters (all optional, combined with AND): `name`, `region`, `gender`, `status` (repeatable, any of the four `CaseStatus` values; omitted = all four), `ageMin`/`ageMax` (0–130 inclusive, min ≤ max), `lastSeenFrom`/`lastSeenTo` (ISO dates, inclusive, from ≤ to), `approvedFrom`/`approvedTo` (ISO dates, inclusive, filtering `approved_at` in UTC over `[from 00:00, to+1 00:00)`, from ≤ to). |
| D3 | The product owner chose the approval date as the "reported" filter. It is named `approvedFrom`/`approvedTo` so the field it filters is unambiguous. |
| D4 | The product owner chose literal name search. After trimming, `name` must be 3–100 characters. The match is a case-insensitive substring: `lower(full_name) LIKE '%' + escaped(lower(name)) + '%' ESCAPE '\'`. `%`, `_` and `\` are matched literally. Accents and word order matter. |
| D5 | Ordering is fixed: `approved_at DESC, id DESC`, and client `sort` is ignored. Offset pages use the existing size rules (default 20, clamped 1–50). an offset `page × size ≥ 10,000` is rejected with 400 (VALIDATION_FAILED), so deep offset scans are bounded. Pages past the end return 200 with empty `content` and real totals. |
| D6 | Items are `PublicCaseSummaryResponse(id, fullName, age, gender, region, lastSeenDate, caseStatus, presentation, approvedAt, resolvedAt, thumbnailPhotoId)`. Long text, contact number and closing statement stay in the detail view (CT-022). |
| D7 | The product owner chose to expose `thumbnailPhotoId` now. It is the case's earliest attached, undeleted, `PURPOSE=PHOTO`, `VISIBILITY=PUBLIC` file, ordered by `uploaded_at`, then `id`, and null if there is none. It is batch-loaded in one query per page, never per row. It is an id only; CT-023 serves the bytes. Deceased cases keep their thumbnail. |
| D8 | Errors: bad enums, dates or numbers return 400 `INVALID_REQUEST` (existing `MethodArgumentTypeMismatchException` handler). Bounds, ranges, length and page depth return 400 `VALIDATION_FAILED` (`ValidationException`). The endpoint has no 401/403/404. |
| D9 | V20 enables `pg_trgm` and indexes. Btree indexes lead with `review_status` and are not partial. Hibernate binds the enum as a parameter, and a generic plan cannot prove a partial predicate `review_status = 'APPROVED'`. Indexes: `(review_status, approved_at DESC, id DESC)`, `(review_status, region, approved_at DESC, id DESC)`, `(review_status, case_status, approved_at DESC, id DESC)`, `(review_status, age)`, plus GIN `lower(full_name) gin_trgm_ops`. Drop the superseded `idx_cases_public_status`. Last-seen ranges reuse `idx_cases_last_seen (last_seen_date, id)`. Gender stays a residual filter because it has low cardinality. |
| D10 | Query-plan proof follows `ReviewQueueFilterTest`: seed, `ANALYZE`, `SET LOCAL enable_seqscan = off`, `EXPLAIN` the same SQL shape for page and count, and assert no `Seq Scan` and the expected index. This proves the indexes are eligible, not that they perform. Pilot performance sign-off belongs to CT-024. |

## Review Focus

1. **A taken-down or pending case in counts.** `totalElements` must exclude it as well as `content`. The count query uses the same Specification.
2. **Wildcard input.** `name=%` (rejected as too short) and `name=50%_\x` must match the literal characters only, never everything.
3. **A deceased case in the list.** `presentation=DECEASED_NOTICE` must be present, and no withheld field can appear, because the summary has none.
4. **Equal `approved_at` values.** Walking every page must return each case exactly once (the id tie-breaker).
5. **Thumbnail leaks.** A `PRIVATE` file (the report), a deleted photo or another case's photo must never become the thumbnail. A taken-down case is never queried at all.

## File Structure

- Create `src/main/resources/db/migration/V20__index_public_case_search.sql`.
- Create `modules/casefile/PublicCaseSearchQuery.java`: validated query record (casefile root, public API).
- Create `modules/casefile/PublicCaseSummaryResponse.java`.
- Create `modules/casefile/internal/repository/PublicCaseSpecification.java`.
- Modify `modules/casefile/internal/repository/CaseFileRepository.java`: thumbnail batch query.
- Modify `modules/casefile/CaseQueryService.java` and `internal/CaseQueryServiceImpl.java`: replace `listPublicCases`.
- Modify `modules/casefile/internal/CaseMapper.java`: `toPublicSummary`.
- Modify `modules/casefile/internal/repository/CaseRecordRepository.java`: drop `findByReviewStatusOrderByApprovedAtDescIdDesc` if it has no other caller.
- Create `modules/discovery/PublicCaseController.java` and `modules/discovery/PublicCaseFilter.java`.
- Modify `shared/config/OpenApiConfig.java` and `OpenApiContractTest.java`.
- Tests: `PublicCaseSearchQueryTest` (unit), `PublicCaseSearchTest` (Postgres), `PublicCaseEndpointTest` (MockMvc), `PublicCaseQueryPlanTest` (Postgres), and an index assertion in the existing migration tests.

All source paths are under `src/main/java/com/souldealers/crowdtracebackend/`; tests mirror them under `src/test/java/...`.

---

### Task 1: V20 search indexes

**Files:** Create `V20__index_public_case_search.sql`. Test `src/test/java/.../modules/casefile/internal/repository/PublicCaseIndexMigrationTest.java` (extends `CasePostgresTestSupport`).

**Interfaces:** Produces the index names `idx_cases_public_recent`, `idx_cases_public_region`, `idx_cases_public_outcome`, `idx_cases_public_age` and `idx_cases_public_name_trgm`, which Task 5 asserts.

- [ ] **Step 1: Failing test.** It queries `pg_indexes` for the five names on `cases`, checks `pg_extension` for `pg_trgm`, and checks that `idx_cases_public_status` is absent.

```java
@Test
void publicSearchIndexesExist() {
    List<String> names = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'cases'", String.class);
    assertThat(names).contains("idx_cases_public_recent", "idx_cases_public_region", "idx_cases_public_outcome",
            "idx_cases_public_age", "idx_cases_public_name_trgm").doesNotContain("idx_cases_public_status");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
            .isOne();
}
```

- [ ] **Step 2: Run it.** `./mvnw test -Dtest=PublicCaseIndexMigrationTest` should fail because the indexes are missing.
- [ ] **Step 3: Migration.**

```sql
-- CT-021: public case search. Indexes lead with review_status instead of being partial, because
-- Hibernate binds the enum as a parameter and a generic plan cannot match a partial predicate.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_cases_public_recent  ON cases (review_status, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_region  ON cases (review_status, region, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_outcome ON cases (review_status, case_status, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_age     ON cases (review_status, age);
CREATE INDEX idx_cases_public_name_trgm ON cases USING gin (lower(full_name) gin_trgm_ops);

DROP INDEX idx_cases_public_status;
```

- [ ] **Step 4: Run it.** It should pass. Then run `GovernanceQueryTest`, `ReviewQueueFilterTest` and any test that referenced `idx_cases_public_status` (`grep -rn idx_cases_public_status src/test`), and update those references to `idx_cases_public_outcome`.
- [ ] **Step 5: Commit.** `feat(casefile): index public case search [CT-021]`

---

### Task 2: Validated search query

**Files:** Create `modules/casefile/PublicCaseSearchQuery.java`. Test `modules/casefile/PublicCaseSearchQueryTest.java` (plain JUnit).

**Interfaces:** Produces:

```java
public record PublicCaseSearchQuery(String name, GhanaRegion region, Gender gender, Set<CaseStatus> statuses,
        Integer ageMin, Integer ageMax, LocalDate lastSeenFrom, LocalDate lastSeenTo,
        LocalDate approvedFrom, LocalDate approvedTo) {
    public static final int MIN_NAME = 3, MAX_NAME = 100, MIN_AGE = 0, MAX_AGE = 130;
    public static PublicCaseSearchQuery all();             // no filters
    public Set<CaseStatus> statuses();                      // never empty: defaults to all four
    public String nameLikePattern();                        // null when name is null; "%" + escaped lower + "%"
    public LocalDateTime approvedFromInclusive();           // approvedFrom at 00:00, or null
    public LocalDateTime approvedToExclusive();             // approvedTo + 1 day at 00:00, or null
}
```

- [ ] **Step 1: Failing tests.** Each line below is one assertion:
  - A blank `name` (`"   "`) throws `ValidationException`.
  - `"ab"` throws; `" abc "` is stored as `"abc"`; a 100-character name passes; 101 throws.
  - `ageMin = -1` throws; `131` throws; `ageMin = 40, ageMax = 39` throws; `ageMin = ageMax = 40` passes.
  - `lastSeenFrom` after `lastSeenTo` throws, and equal dates pass. The same holds for the approval dates.
  - `statuses` null or empty gives all four values; `{MISSING}` gives `{MISSING}`.
  - `nameLikePattern()` for `"50%_\\x"` is `"%50\\%\\_\\\\x%"`, and for `"Kofi"` is `"%kofi%"` (lowercased with `Locale.ROOT`).
  - `approvedToExclusive()` for `2026-02-28` is `2026-03-01T00:00`; for leap day `2028-02-29` it is `2028-03-01T00:00`.
- [ ] **Step 2: Run them.** They should fail to compile.
- [ ] **Step 3: Implement.** Use a compact constructor like `CaseReviewQueueQuery`. Messages: `"name must be 3-100 characters"`, `"ageMin must be between 0 and 130"`, `"ageMin must not exceed ageMax"`, `"lastSeenFrom must not be after lastSeenTo"`, `"approvedFrom must not be after approvedTo"`. Copy `statuses` into an `EnumSet`. Escape `\` first, then `%` and `_`.
- [ ] **Step 4: Run them.** They should pass.
- [ ] **Step 5: Commit.** `feat(casefile): validate public case search filters [CT-021]`

---

### Task 3: Search service, specification and thumbnails

**Files:**
- Create `PublicCaseSummaryResponse.java`, `internal/repository/PublicCaseSpecification.java`.
- Modify `CaseQueryService`, `CaseQueryServiceImpl`, `CaseMapper`, `CaseFileRepository` and `CaseRecordRepository`. Remove `listPublicCases` from the interface, the implementation and any caller or test; `grep -rn listPublicCases src`.
- Test `modules/casefile/internal/PublicCaseSearchTest.java` (extends `CasePostgresTestSupport`).

**Interfaces:**
- Consumes `PublicCaseSearchQuery` (Task 2).
- Produces:

```java
// CaseQueryService
/** Approved cases matching the query, newest approval first, then id; client sorting is ignored. */
PagedResponse<PublicCaseSummaryResponse> searchPublicCases(PublicCaseSearchQuery query, Pageable pageable);

public record PublicCaseSummaryResponse(Long id, String fullName, int age, Gender gender, GhanaRegion region,
        LocalDate lastSeenDate, CaseStatus caseStatus, CasePresentation presentation,
        LocalDateTime approvedAt, LocalDateTime resolvedAt, Long thumbnailPhotoId) {}

// PublicCaseSpecification
public static final Sort ORDER = Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id"));
public static Specification<CaseRecord> matching(PublicCaseSearchQuery q);

// CaseFileRepository: one row per case id, earliest public photo
@Query(value = """
        SELECT DISTINCT ON (case_id) case_id AS caseId, id AS photoId
        FROM case_files
        WHERE case_id IN (:caseIds) AND purpose = 'PHOTO' AND visibility = 'PUBLIC' AND deleted_at IS NULL
        ORDER BY case_id, uploaded_at, id
        """, nativeQuery = true)
List<CaseThumbnail> findThumbnails(@Param("caseIds") Collection<Long> caseIds);
// CaseThumbnail: interface projection { Long getCaseId(); Long getPhotoId(); } in internal/repository
```

- [ ] **Step 1: Failing tests** in `PublicCaseSearchTest`. Seed with the builder pattern from `LifecycleAuditRollbackTest.base()`, with explicit `approvedAt` values:
  - `onlyApprovedCasesAppearInContentAndTotals`: one case each in SUBMITTED, UNDER_REVIEW, REJECTED, TAKEN_DOWN and APPROVED. Only the approved case is returned, and `totalElements == 1`.
  - `everyOutcomeIsPublicByDefaultAndStatusNarrows`: four approved cases, one per outcome. The default returns all four; `{MISSING}` returns one; `{FOUND_SAFE, CLOSED}` returns two.
  - `eachFilterNarrowsAndFiltersCombineWithAnd`: parameterized over region, gender, age range (inclusive at both ends), last-seen range (inclusive at both ends) and approval range. Approvals at `to 23:59:59` are included and at `to+1 00:00` excluded. A test combining name, region and status checks the AND behaviour.
  - `nameMatchesCaseInsensitiveSubstringOnly`: `"mensah"` finds `"Kofi MENSAH"`; `"Mensah Kofi"` doesn't; `"Kofi"` doesn't find `"Kòfi"`.
  - `wildcardsInNameAreLiteral`: cases `"Ama 50%"` and `"Ama 500"`; the query `"50%"` returns only the first. Query `"a_a"` doesn't match `"aba"`.
  - `orderIsNewestApprovalThenIdAndPagesNeverRepeat`: 7 cases, 3 sharing one `approvedAt`. At size 2, walking pages 0–3 returns all ids exactly once, in `(approvedAt DESC, id DESC)` order.
  - `emptyAndBeyondLastPagesAreStable`: no match gives `content=[]`, `totalElements=0`, `totalPages=0`, `last=true`. Page 5 of a 1-result search gives empty `content`, `totalElements=1`, `last=true`.
  - `deceasedSummaryCarriesTheNotice`: `presentation == DECEASED_NOTICE`.
  - `thumbnailIsTheEarliestPublicLivePhoto`. Case A has a PRIVATE REPORT file at t0, a deleted PHOTO at t1, PHOTO p2 at t3 and PHOTO p3 at t2: the thumbnail is p3. Case B has no photo: null. Case C (taken down) never appears.
  - `thumbnailsLoadInOneQuery`: with Hibernate `Statistics` (or a `QueryCountHolder`, if the project has one; otherwise count with `@MockitoSpyBean CaseFileRepository`), searching 5 cases calls `findThumbnails` exactly once.
  - `deepPagesAreRejected`: `PageRequest.of(200, 50)` (offset 10,000) throws `ValidationException`; `PageRequest.of(199, 50)` (offset 9,950) doesn't. The check is `(long) page * size >= 10_000`, after size clamping.
- [ ] **Step 2: Run them.** `./mvnw test -Dtest=PublicCaseSearchTest` should fail to compile.
- [ ] **Step 3: Implement.**
  - **Specification:** always add `reviewStatus = APPROVED` and `caseStatus IN statuses`. Add these only when the filter is set:
    - name: `cb.like(cb.lower(root.get("fullName")), pattern, '\\')`
    - region and gender: equality
    - age: `>=` / `<=`
    - lastSeen: `>=` / `<=`
    - approvedAt: `>= fromInclusive`, `< toExclusive`
  - **Service:**
    1. Run `CasePageRequests.fixed(pageable)`, then the depth check.
    2. Call `caseRepository.findAll(spec, PageRequest.of(page, size, ORDER))`.
    3. Collect the ids and run `fileRepository.findThumbnails(ids)` once, skipping it when there are no ids, into a `Map<Long, Long>`.
    4. Map each row with `CaseMapper.toPublicSummary(c, thumbnails.get(c.getId()))`.
  - **Mapper:** `toPublicSummary` takes `presentation` from `toPublic(c)`, so deceased handling has one source.
- [ ] **Step 4: Run them.** They should pass. Then run `./mvnw test -Dtest='Case*Test,Review*Test'` to catch callers of the removed method.
- [ ] **Step 5: Commit.** `feat(casefile): search approved cases with filters and thumbnails [CT-021]`

---

### Task 4: Public endpoint and OpenAPI

**Files:**
- Create `modules/discovery/PublicCaseController.java` and `modules/discovery/PublicCaseFilter.java`.
- Modify `OpenApiConfig`: replace the `/api/public/cases` placeholder; leave `/{caseId}` planned for CT-022.
- Test `modules/discovery/PublicCaseEndpointTest.java` (MockMvc + Postgres, following `CaseLifecycleEndpointTest`), and modify `OpenApiContractTest`.

**Interfaces:**
- Consumes `CaseQueryService.searchPublicCases` (Task 3).
- Produces the route `GET /api/public/cases` → `ApiResponse<PagedResponse<PublicCaseSummaryResponse>>`, message `"Public cases retrieved successfully"`.

```java
/** HTTP search filters; translated into casefile's own query so casefile never depends on discovery. */
public record PublicCaseFilter(String name, GhanaRegion region, Gender gender, List<CaseStatus> status,
        Integer ageMin, Integer ageMax, LocalDate lastSeenFrom, LocalDate lastSeenTo,
        LocalDate approvedFrom, LocalDate approvedTo) {
    public PublicCaseSearchQuery toQuery() { /* status null -> null set */ }
}
```

The controller uses `@RestController`, `@RequestMapping("/api/public/cases")` and `@Tag(name = "Public Cases")`, with no `@SecurityRequirement`. It takes `@RequestParam(required = false)` per field (dates use `@DateTimeFormat(iso = DATE)`) plus `@ParameterObject Pageable`, following `ReviewController.queue`.

- [ ] **Step 1: Failing tests.**
  - `guestCanSearchWithoutAToken`: 200, `$.data.content[0].fullName`.
  - `responseFieldsAreExactlyTheAllowlist`: the key set of `content[0]` equals the 11 D6 names. No `reporterId`, `storageKey`, `lastSeenLocation`, `publicContactNumber`, `reviewStatus` or `version`.
  - `authenticatedModeratorSeesTheSamePublicProjection`: the same key set with a moderator token, and no pending case.
  - Each of these returns 400 with `$.code == "INVALID_REQUEST"`: `status=BOGUS`, `region=MARS`, `ageMin=abc`, `lastSeenFrom=2026-13-01`.
  - Each of these returns 400 with `$.code == "VALIDATION_FAILED"`: `name=ab`, `ageMin=50&ageMax=40`, `page=201&size=50`. Check the code value against `GlobalExceptionHandler` and use the real one.
  - `repeatedStatusParamsAreUnioned`: `status=MISSING&status=CLOSED` returns both.
  - `sortParamIsIgnored`: `sort=fullName,asc` gives the same order as without it.
- [ ] **Step 2: Run them.** They should fail with 404 (no route) or a compile error.
- [ ] **Step 3: Implement the controller.** The `@Operation` description states the ordering, the inclusive date semantics in UTC, the name rules, the 10,000 depth cap, and that offset pages can shift when cases are published or taken down. Document 400 and 500 responses with ProblemDetail.
- [ ] **Step 4: OpenAPI.**
  - Replace the placeholder with the real operation (`security: []`, query parameters with min/max/enum, and a `PublicCaseListResponse` built from `PagedResponse` of `PublicCaseSummaryResponse`). Nullable fields use 3.1 `type` unions, as in CT-018: `resolvedAt` and `thumbnailPhotoId`.
  - Add `OpenApiContractTest` assertions:
    - the path has no planned marker;
    - the operation has empty security;
    - the parameter names exactly match D2 plus `page` and `size`;
    - the item schema has exactly the D6 properties.
- [ ] **Step 5: Run.** `./mvnw test -Dtest='PublicCaseEndpointTest,OpenApiContractTest,CrowdtraceModulesTest'` should pass. Modulith confirms that discovery depends only on casefile's public API.
- [ ] **Step 6: Commit.** `feat(discovery): expose public case search [CT-021]`

---

### Task 5: Query-plan proof

**Files:** Test `modules/casefile/internal/repository/PublicCaseQueryPlanTest.java` (extends `CasePostgresTestSupport`).

- [ ] **Step 1: Write the test** (it should pass on first run, since the indexes exist from Task 1; if it doesn't, fix the index or the SQL, never the assertion).
  - Seed 60 cases across review states, regions and outcomes.
  - Run `ANALYZE cases`, then `SET LOCAL enable_seqscan = off`.
  - Parameterize over `(whereFragment, expectedIndex)`:

| Filter shape | Expected index |
|---|---|
| none | `idx_cases_public_recent` |
| `region = 'ASHANTI'` | `idx_cases_public_region` |
| `case_status IN ('MISSING')` | `idx_cases_public_outcome` |
| `age BETWEEN 10 AND 17` | `idx_cases_public_age` or `idx_cases_public_recent` (assert one of these) |
| `lower(full_name) LIKE '%mensah%' ESCAPE '\'` | `idx_cases_public_name_trgm` |
| `last_seen_date BETWEEN ...` | `idx_cases_last_seen` or a `public` index |

  - Each shape is prefixed with `review_status = 'APPROVED' AND case_status IN (all four)`.
  - For the page query `... ORDER BY approved_at DESC, id DESC LIMIT 20` and the count query, assert the plan has no `Seq Scan` and contains the expected index.
  - Also capture the SQL Hibernate actually emits for one shape (`spring.jpa.show-sql` is not needed: build it from a `StatementInspector` test bean or a `pg_stat_statements`-free approach of logging via `org.hibernate.SQL` at DEBUG into a ListAppender). Assert that it uses `lower(` and `like ... escape`, so the hand-written EXPLAIN shapes match production. If capturing the statement is impractical, assert through `PublicCaseSearchTest` that a `%`-literal search works, and note the gap.
- [ ] **Step 2: Run it.** It should pass.
- [ ] **Step 3: Commit.** `test(casefile): prove public search filters use indexes [CT-021]`

---

### Task 6: Docs

**Files:** Modify `docs/changelog.md` (Unreleased, CT-021 entry above CT-018), `docs/TASKS_INDEX.md` (tick CT-021), and `.superpowers/sdd/...` if it is in use.

- [ ] **Step 1: Changelog entry.** It covers the endpoint, filters and bounds; approved-only visibility; the summary fields; the thumbnail rule; V20 indexes and why they aren't partial; and the 10,000-row depth cap. Handoffs:
  - CT-022: detail reuses `toPublic`, and returns 404 for non-approved cases.
  - CT-023: serve `thumbnailPhotoId` only for PUBLIC photos of APPROVED cases.
  - CT-024: performance target on a pilot-sized dataset.
- [ ] **Step 2: Run the full suite.** It should show 0 failures and 0 errors.
- [ ] **Step 3: Commit.** `docs(discovery): record CT-021 decisions [CT-021]`
