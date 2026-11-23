# CT-024 — Public API safety and performance verification

## Why

Guests reach `/api/public/**` without logging in. CT-024 proves they can't read more than the public
projection, can't page without bounds, and that search is fast enough for the pilot. Tests only: no
production change unless a test exposes a defect.

## Already covered (not redone)

- Exact top-level field sets for the case list item and case detail (`PublicCaseEndpointTest`).
- Case list `size` 1–50, tampered cursor, non-public cases 404, invalid filters 400.
- Only approved-case photos are served (`CaseFileAccessEndpointTest`).
- OpenAPI field sets for the list item and comment schemas (`OpenApiContractTest`).
- Index eligibility on a 10,000-case seed (`PublicCaseQueryPlanTest`).

## What CT-024 adds

1. **Recursive field allowlist** (`PublicApiLeakTest`). Seed one approved case with a photo, a
   comment and a reply, plus a pending case. Call every public GET as a guest: list, detail, photo,
   comments, replies, comment count, and the 400/404 error bodies. Walk each JSON body at every depth and
   require each object's keys to equal the allowed set for its path (case item, case detail, comment,
   comment author, page wrapper, error). An allowlist catches new fields a denylist would miss; the
   failure message names the path and key. The photo endpoint must answer with a redirect and no body.
2. **Pagination bounds**. Extend `CommentEndpointTest`: replies get the same `size` 0/51 and tampered
   cursor 400s that root comments already have, and `size=50` returns at most 50 when more exist. The
   case list already has these.
3. **OpenAPI matches behavior**. The documented field sets for the public case detail and the comment
   count equal a real response's fields. Fix the comment count description: it says removed comments are
   excluded, but they are counted as placeholders on purpose (`CommentRepository.countRoots`), so the
   count matches the thread.
4. **Pilot performance** (`PublicCaseSearchPerformanceTest`). Reuse the `PublicCaseQueryPlanTest` seed
   (10,060 cases, about 70% of the bulk approved), moved into a shared helper. With the normal planner
   (sequential scans allowed), run `EXPLAIN (ANALYZE)` on the SQL Hibernate actually emits for each
   representative search: no filter, name substring (the API matches anywhere in the name), region,
   status + region, age range, last-seen date range, and a deep cursor page, plus the photo-thumbnail
   lookup `CaseQueryServiceImpl` runs for each page. Warm up once, take the median of 5 runs, and require
   **under 50 ms** each.

## Approaches considered

- **A. Database-only timing (chosen).** Runs in the normal suite and measures the queries that dominate
  request time. Codex flagged that sustained CI contention could still push a fixed threshold over
  (rated 4 × 4). The expected times are far below 50 ms; if it ever flakes, it moves to a tagged job
  rather than getting retries.
- B. Full-request p95 timing through MockMvc. Closer to the user experience, but CI timing noise makes
  it flaky.
- C. One-off benchmark in the docs. No regression protection.

## Non-goals

- No load testing, no HTTP-level latency budget, no new indexes unless the 50 ms target fails.
- No change to authenticated endpoints.

## Left to the builder

Helper names, the exact SQL for each search shape (copy what Hibernate emits, as
`PublicCaseQueryPlanTest` already does), and where the seed helper is shared.
