# Reliability checklist

The `verify` skill reads the diff and runs only the items whose trigger matches (usually 3–10).
Each item comes from a real bug. Add one when a bug escapes; delete it when it stops applying.

| # | Trigger (visible in the diff) | Check | Source |
|---|---|---|---|
| 1 | New or changed `db/migration/V*.sql` | `./mvnw test -Dtest='*MigrationTest,SchemaValidationTest'`. Every column an entity maps exists; `ddl-auto=validate` passes. Never edit a merged migration; add a new one. | f512817b users.deleted_at missing |
| 2 | SQL or entity with a timestamp default or `now()` | Defaults pinned to UTC (`CURRENT_TIMESTAMP AT TIME ZONE 'UTC'`), Java reads the UTC `Clock`, not `LocalDateTime.now()` | c19f24bc, d78f9dd1 |
| 3 | `*Controller.java`, `*Request.java`, `*Response.java`, `@Schema` | `OpenApiContractTest` passes; every field and nullable is documented; run `pnpm api:types` against a running backend and `pnpm typecheck` | a87b85b3, 8123e013, 8421e1c6, d5c4ca38 |
| 4 | `SecurityConfig`, filters, CORS, logout or token code | Preflight from `http://localhost:3000` succeeds; logout makes the old token 401; an unmapped route returns 404, not 500 | 4e8f978e, 588597f9, f729ee42 |
| 5 | Audit or history tables (`audit_events`, `case_reviews`, `*_history`) or their repositories | `AuditImmutabilityMigrationTest` passes; UPDATE, DELETE and TRUNCATE are rejected by a trigger; corrections are new rows | cc9b06bc |
| 6 | Request headers or user input written to logs, MDC or audit | Value is validated or replaced before it is stored (`CorrelationIdFilterPropertyTest` is the pattern) | 4cfc986e |
| 7 | `@Transactional` method that does duplicate detection, file I/O or HTTP calls | The DB connection is released before the slow work; no external call inside the transaction | b14a00bc |
| 8 | Case evidence, photos or duplicate-match changes | Case `version` is bumped so caches and clients see the change | 00faee04 |
| 9 | Concurrent writes (one-active-request rules, rate limits, token revocation) | The concurrency test for that rule passes on PostgreSQL (`*ConcurrencyTest`, `*RaceTest`); a DB constraint backs the rule | fc5bea92, 182d510c |
| 10 | `apps/web/src/app/(auth)/**`, `lib/auth.ts`, `lib/api/client.ts`, upload or admin action code | `pnpm --filter web test:e2e` passes; drive the changed flow, including the error path | bc10f812 |
| 11 | `docker-compose.yaml`, storage or S3 config | With Compose up, upload a case photo and open its signed link in the browser | 67512c72 |
| 12 | Lint directives (`eslint-disable`) in `apps/web` | `pnpm lint` passes; the rule named is one the root config loads | 7075dcaf |
