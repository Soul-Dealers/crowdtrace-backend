# CT-021 Keyset Pagination Rewrite

User direction: replace public listing offset pagination with keyset pagination using Luna, while
preserving the layered style and verified incremental operation commits. This supersedes the
offset-specific parts of the 2026-10-15 public-listing plan. Current baseline: 803b5d7; preserve
thumbnail regression coverage and unrelated work. Do not alter audit tables.

## Finalized public listing contract

`GET /api/public/cases?size=20` starts at the newest matching approved case. The response contains
`content`, requested `size`, `hasNext`, and nullable `nextCursor`. When `hasNext` is true, use the
returned cursor on the next request and repeat the same filters, for example:

```text
GET /api/public/cases?size=20&region=ASHANTI
GET /api/public/cases?size=20&region=ASHANTI&cursor=<nextCursor>
```

The cursor is opaque and forward-only; page-number navigation is not supported, so clients continue
using returned cursors. It carries the
seek boundary, not the filters, and is not a snapshot: rows can change as cases are approved or
taken down. The existing approved-only visibility, 11-field public summary, deceased withholding,
and eligible thumbnail rules remain in force. No count query is run, and no exact totals are returned.

## Design

- The public listing uses `cursor` and `size`. An omitted cursor starts at the newest matching result;
  subsequent requests pass the opaque `nextCursor` and repeat the same filters. Size defaults to 20
  and must be 1–50. Malformed numeric size returns 400 `INVALID_REQUEST`; invalid bounds, cursor, or
  an explicit legacy `page` parameter return 400 `VALIDATION_FAILED`. The legacy parameter is hidden
  from OpenAPI. Client sort remains ignored.
- Response metadata is exactly `content`, `size`, `hasNext`, and `nextCursor`; `size` is the requested
  size. There are no `page`, `last`, or total fields. `nextCursor` is null when `hasNext` is false.
  Reporter/admin offset pagination and shared `PagedResponse` remain intact.
- Ordering is fixed at `approved_at DESC, id DESC`. The cursor encodes a version and the exact boundary
  timestamp/id as URL-safe Base64 without padding. Length is bounded, parsing is strict and canonical,
  ids must be positive Long values, and timestamps must meet supported range/precision. Signing is not
  needed because a cursor only narrows approved public rows and never authorizes access. No anchor
  lookup is required, so taking down or deleting the anchor does not break navigation. Filters are
  reapplied on every request; the cursor carries neither filters nor a snapshot.
- Preserve the SQL filter Specification, approved-only visibility, literal name search, date/age
  validation, deceased projection, and batched live PUBLIC thumbnail selection. Fetch `size + 1`
  rows without OFFSET or COUNT, trim the lookahead before mapping/loading thumbnails, and derive the
  next cursor from the last returned row only when more rows exist.
- Use a composite seek predicate with bound values so PostgreSQL can put the tuple comparison in
  `Index Cond` for deep cursors. Existing recent and region/outcome indexes suffice; do not change
  shipped migrations or add dependencies.
- Preserve PostgreSQL `DESC NULLS FIRST` behavior for approved rows with missing `approved_at` (the
  schema permits this). A null timestamp uses a fixed cursor marker. After a null-timestamp anchor,
  include remaining null timestamps with smaller ids, then all non-null timestamps. After a non-null
  anchor, use tuple seek, which naturally excludes null timestamps. Add boundary tests; do not add a
  migration/backfill or silently exclude these rows. Present cursor timestamps must have years
  1–9999 and microsecond precision.
- The verified Hibernate 7.4.1 SQL decision is public
  `HibernateCriteriaBuilder.sql("(?, ?) < (?, ?)", Boolean.class, ...)`, with root timestamp/id
  expressions and `hcb.value(...)` bound cursor values, wrapped as a predicate. The SQL pattern is
  static; do not use raw Comparable casts or unavailable tuple overloads. Spring Data JPA 4.1
  `SpecificationFluentQuery.limit(int).all()` fetches the bounded result. This preserves nullable
  `approved_at` handling and PostgreSQL ordering described above.

### Task 1: Replace public pagination across all layers

Ownership: casefile cursor codec record and tests, shared CursorResponse, CaseQueryService/interface implementation, public Specification, discovery controller, OpenAPI schemas/operation and contract tests, all search callers/tests and public query-plan tests. Preserve unrelated page-based operations.

1. Add meaningful failing tests for cursor roundtrips and invalid versions, blank/malformed/oversized
   tokens, id/date/precision bounds, first/middle/last/empty pages, tied timestamps and large IDs, strict
   seek excluding the anchor, traversal beyond 10,000, repeated filters, new approvals ahead of the cursor,
   a taken-down anchor, and visibility/outcome/filter/projection/thumbnail regressions. Keep the new
   thumbnail-order-vs-id and per-case tests.
2. Implement new service signature `CursorResponse<PublicCaseSummaryResponse> searchPublicCases(PublicCaseSearchQuery query, String cursor, int size)`. Validate at domain boundary, retrieve size + 1 via specification fluent limit/all, fixed order. Service enforces 1–50; HTTP defaults to 20 and validates binding as before. No null Pageable/unpaged fallback.
3. Controller exposes cursor+size, rejects explicit old page, preserves all other filter bindings/status handling and response envelope/message. Public JSON item allowlist stays11 fields; pagination metadata exactly content/size/hasNext/nextCursor.
4. OpenAPI names exactly the original 10 filters plus `cursor` and `size`. Cursor is an optional opaque
   string with bounded length; size defaults to 20 with min 1 and max 50. `nextCursor` uses a nullable
   OpenAPI 3.1 union. This operation has no count/page schemas. Keep the full-detail placeholder and
   admin/reporter paged schemas.
5. SQL capture integration asserts the service emits a bounded query and cursor seek, with no OFFSET or
   COUNT; examine nonempty and empty/no-more responses as useful. EXPLAIN on a representative 10,000-row
   fixture must show the composite boundary in `Index Cond` for deep plain and region seeks on recent/region
   indexes, including a generic bound plan, rather than only as a residual filter. Retain relevant
   name/trigram proof and capture the same shape from actual SQL. This proves eligibility, not pilot latency.
6. Run focused cursor/unit/search/endpoint/OpenAPI/Modulith plus affected CaseQueryService/ReviewProjection tests with OrbStack env. No full suite yet. Self-review; commit complete operation `feat(discovery): paginate public cases with keyset cursors [CT-021]`, no attribution trailers. Report RED/GREEN and SHA in ignored workspace, never force-add scratch.

### Task 2: Documentation and final verification

Ownership: this plan (record finalized implementation choices), the prior public-listing plan (mark offset
  decisions superseded with a link), `docs/changelog.md` CT-021 entry (cursor contract, no totals/count, new
  bounds/limitations), and any existing task docs needing pagination wording. No unrelated rewrites.

1. Parent runs full Maven verify once on completed implementation; fresh Luna review checks full diff before docs commit.
2. Document `GET /api/public/cases?size=20` followed by a cursor request, unchanged safe public projection,
   forward-only cursors (clients continue using returned cursors), repeated filters, and non-snapshot
   behavior. Do not claim an
   old 10,000 cap or exact totals.
3. Commit `docs(discovery): document public keyset pagination [CT-021]` after green full suite. Keep work on existing feature branch, no push/PR/merge requested.
