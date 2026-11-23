# CT-024 Public API Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove guests get only the public projection, can't page without bounds, and that public search meets 50 ms on a pilot-sized dataset.

**Architecture:** Tests only, plus one OpenAPI description fix. A recursive allowlist walker checks every public JSON body; existing endpoint and contract tests gain the missing bounds and schema checks; a new Postgres test times the public search service on the shared 10,060-case seed.

**Tech Stack:** Spring Boot 4.1, MockMvc, JUnit 5, AssertJ, embedded PostgreSQL (`CasePostgresTestSupport`).

**Spec:** `docs/superpowers/specs/2026-11-22-ct024-public-api-safety-design.md`

## Global Constraints

- No production change except the comment-count OpenAPI description.
- Never edit an existing assertion to make it pass. Only add assertions.
- Each new test must be shown to fail against a deliberate temporary mutation, then the mutation reverted.
- Performance target: under 50 ms per representative search, median of 5 runs after one warm-up.
- No retries, sleeps or skips. If timing flakes, stop and report.

## Review Focus

1. A nested object (comment `author`) gaining a private field such as `email` must fail the leak test → Task 1 walks every depth.
2. A validation 400 whose `errors` map echoes internal names must fail → Task 1 checks error bodies, including `errors`.
3. Replies paged with `size=51` must be refused like root comments → Task 2.
4. The name filter matches anywhere in the name, so the timed search must be a substring and not a prefix → Task 4.
5. A page of 50 cases with photos must not push the thumbnail lookup over target → Task 4 seeds photos.

## Ruling (deviation from the spec)

Spec item 4 says `EXPLAIN (ANALYZE)` on the captured Hibernate SQL. Hibernate logs SQL with `?` placeholders
and no values, so that would mean rebuilding every bind list by hand. Instead, Task 4 times
`CaseQueryService.searchPublicCases` in-process: no HTTP, real SQL, real binds, and the thumbnail lookup
included. Cost if wrong: Hibernate mapping time counts toward the 50 ms. At 50 rows that cost is small.

---

### Task 1: Recursive public-field allowlist

**Files:**
- Create: `src/test/java/com/souldealers/crowdtracebackend/PublicApiLeakTest.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/casefile/internal/CaseFileAccessEndpointTest.java` (add one assertion)

**Interfaces:**
- Produces: nothing later tasks use.

- [ ] **Step 1: Write `PublicApiLeakTest`.** Use the same Spring setup and seeding helpers as `CommentEndpointTest`. Seed one APPROVED case with a comment and a reply by a verified author, plus one SUBMITTED case. As a guest, `GET`:
  - `/api/public/cases`, then `?name=<seeded name>`;
  - `/api/public/cases/{approved}`;
  - `/api/public/cases/{approved}/comments`, then `?parentCommentId=<root>`;
  - `/api/public/cases/{approved}/comments/count`;
  - errors: `/api/public/cases/{submitted}` (404), `/api/public/cases?size=0` (400), `/api/public/cases?ageMin=abc` (400), `/api/public/cases/{approved}/comments?cursor=x` (400).

  Walk each body recursively and assert that every object's key set is a subset of the allowlist for its path. The allowlists are hardcoded literals, not derived from the records:

  | Path | Allowed keys |
  |---|---|
  | envelope | `success, message, data, errors` |
  | page (`data` of a list) | `content, size, hasNext, nextCursor` |
  | case summary (`data.content[*]`) | `id, fullName, age, gender, region, lastSeenDate, caseStatus, presentation, approvedAt, resolvedAt, thumbnailPhotoId` |
  | case detail (`data`) | `id, fullName, age, gender, lastSeenDate, region, lastSeenLocation, physicalDescription, clothing, circumstances, caseStatus, closingStatement, publicContactNumber, approvedAt, resolvedAt, presentation, photoIds` |
  | comment (`data.content[*]`) | `id, parentCommentId, author, body, createdAt, replyCount, deleted, removed` |
  | comment author | `displayName, avatarSeed, verified, badgeType` |
  | count (`data`) | `topLevel` |
  | problem | `type, title, status, detail, instance, code, correlationId, errors` |

  Inside a problem's `errors`, the keys may be request parameter names, but every value must be a string or an array of strings. The failure message names the JSON path and the extra key, e.g. `data.content[0].author has unexpected key 'email'`.

- [ ] **Step 2: Run it.** `./mvnw test -Dtest=PublicApiLeakTest`. Expected: PASS.
- [ ] **Step 3: Mutation check.** Temporarily add a field `String email` to `PublicUserResponse`, filled from the user's email, so it shows up on comment authors. Rerun. Expected: FAIL naming `author` and `email`. Revert the mutation and rerun. Expected: PASS.
- [ ] **Step 4: Photo body.** In `approvedCasePhotoRedirectsToAShortLivedSignedUrl`, add an assertion that the response body is empty. Run `-Dtest=CaseFileAccessEndpointTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `test(discovery): recursive allowlist over every public response [CT-024]`

### Task 2: Comment paging bounds

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/community/CommentEndpointTest.java`

- [ ] **Step 1: Add `rejectsInvalidReplyPagingInput`.** Use `?parentCommentId=<root>` with:
  - `size=0`, `size=51` and `size=10000` → 400 each;
  - `cursor=not-a-cursor` → 400.
- [ ] **Step 2: Add `rootPageNeverExceedsFifty`.** Seed 51 root comments, then `GET ?size=50`. Expect `data.content.length() == 50` and `data.hasNext == true`.
- [ ] **Step 3: Run.** `./mvnw test -Dtest=CommentEndpointTest`. Expected: PASS.
- [ ] **Step 4: Mutation check.** Temporarily widen the comment size limit to 100 in the comment service. Rerun. Expected: FAIL in `rejectsInvalidReplyPagingInput`. Revert and rerun. Expected: PASS.
- [ ] **Step 5: Commit.** `test(community): bound reply paging and full pages [CT-024]`

### Task 3: OpenAPI matches behavior

**Files:**
- Modify: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/community/CommentController.java:59-60`

- [ ] **Step 1: Add the schema tests.** Follow the `commentSchemaHasExactlyTheCommentResponseFields` pattern:
  - `publicCaseDetailSchemaHasExactlyThePublicCaseResponseFields`: the property names of schema `PublicCaseResponse` equal the record components of `PublicCaseResponse`;
  - `commentCountSchemaHasExactlyTheCountFields`: schema `CommentCountResponse` equals `CommentCountResponse`.
- [ ] **Step 2: Add `commentCountDescriptionMatchesTheCountedRows`.** The `get` operation on `/api/public/cases/{caseId}/comments/count` has a description that contains `removed comments included as placeholders` and does not contain `removed comments excluded`.
- [ ] **Step 3: Run.** `./mvnw test -Dtest=OpenApiContractTest`. Expected: the two schema tests PASS, and the description test FAILS.
- [ ] **Step 4: Fix the description.** Change it to: `"Returns how many top-level comments an approved case's thread shows, author-deleted and removed comments included as placeholders."`
- [ ] **Step 5: Rerun and check the schema tests.** Rerun. Expected: all PASS. Then temporarily drop one property from the `PublicCaseResponse` schema in `OpenApiConfig`, confirm the detail schema test FAILS, and revert.
- [ ] **Step 6: Commit.** `fix(community): document that removed comments count as placeholders [CT-024]`

### Task 4: Pilot search performance

**Files:**
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/casefile/internal/repository/PublicCaseSeed.java`
- Modify: `src/test/java/com/souldealers/crowdtracebackend/modules/casefile/internal/repository/PublicCaseQueryPlanTest.java` (call the seed instead of the inline `INSERT`)
- Create: `src/test/java/com/souldealers/crowdtracebackend/modules/casefile/internal/repository/PublicCaseSearchPerformanceTest.java`

**Interfaces:**
- Produces: `static void insertBulk(JdbcTemplate jdbc, long reporterId)` in `PublicCaseSeed`. It holds the existing 10,000-row `INSERT … generate_series` statement, moved verbatim.

- [ ] **Step 1: Extract the seed.** Move the bulk `INSERT` from `PublicCaseQueryPlanTest.seedCases` into `PublicCaseSeed.insertBulk`. Leave the 60 lifecycle rows, `gin_clean_pending_list`, `ANALYZE` and `SET LOCAL enable_seqscan = off` in `PublicCaseQueryPlanTest`. Run `-Dtest=PublicCaseQueryPlanTest`. Expected: PASS, unchanged.
- [ ] **Step 2: Write `PublicCaseSearchPerformanceTest`.** It extends `CasePostgresTestSupport`. Use one `@Test`, `publicSearchesMeetThePilotTarget`, so the seed runs once.
  - Seed with `PublicCaseSeed.insertBulk`.
  - Insert one PHOTO `case_files` row for every third approved bulk case, using the same columns the existing photo fixtures use.
  - Run `gin_clean_pending_list` and `ANALYZE`. Do **not** disable sequential scans.

  For each search below, call `caseQueryService.searchPublicCases(query, cursor, 50)` once to warm up, then time 5 calls with `System.nanoTime()` and take the median. Assert under 50 ms using `SoftAssertions`, so one run reports every slow shape, each named with its ms value:

  | Name | Query |
  |---|---|
  | none | `PublicCaseSearchQuery.all()` |
  | name substring | name `"mensa"` (matches the middle of `Mensah`/`Mensima`) |
  | region | `ASHANTI` |
  | status + region | `MISSING` + `ASHANTI` |
  | age range | 10–17 |
  | last-seen range | 2026-08-10 to 2026-08-20 |
  | deep cursor | `all()` with `new PublicCaseCursor(LocalDateTime.of(2026, 9, 25, 0, 0), 3000).encode()` |

  Log each median with SLF4J at INFO, so the numbers show up in the test output for the PR.
- [ ] **Step 3: Run.** `./mvnw test -Dtest=PublicCaseSearchPerformanceTest`. Expected: PASS. Record the medians.
- [ ] **Step 4: Mutation check.** Temporarily set the limit to 0.001 ms. Expected: FAIL listing all 7 shapes. Revert and rerun. Expected: PASS. If a real shape is 50 ms or more, stop and report it. Do not add an index without asking.
- [ ] **Step 5: Run the full suite.** `./mvnw test > <workspace>/full.log 2>&1`, then read the tail. Expected: 0 failures.
- [ ] **Step 6: Commit.** `test(casefile): pilot-sized public search stays under 50 ms [CT-024]`
