# Review guide

How to review a change in this repo. Reviews read this file from the **base branch**, never from the
change under review: `git show origin/main:REVIEW.md`.

## Scope
- Review the current commit or working tree only. If it changes during the review, start over.
- Report only what you'd block a merge for, or would want fixed soon. No style nits that a linter or
  formatter could catch.

## Severity
Tag every finding:
- **Important**: wrong behaviour, data loss, security, a broken contract, a missing test for new behaviour.
- **Nit**: worth fixing, not worth blocking.
- **Pre-existing**: a real bug the change didn't introduce. The author isn't on the hook for it.

For each finding give the file and line, the concrete failure (input → wrong result), and the fix.
Rate it 1–10 for how often it would happen and 1–10 for how bad it would be. If the product is under 5,
file a GitHub issue instead of changing the code.

## Always check
- Does the change do what was asked, and nothing else?
- Is the new behaviour covered by a test that fails without it?
- Did a test, check or rule get weakened, skipped or deleted? Was a reason given?
- Errors: are they handled where they can be acted on, and surfaced otherwise?
- Inputs from users or the network: validated, bounded, escaped?

## This repo
<!-- Rules learned from real misses. Each one names the PR or incident behind it. Prune what stops mattering. -->
- **Audit and history tables are append-only.** A trigger must reject UPDATE, DELETE and TRUNCATE; the app uses an
  insert-only repository and an immutable entity; corrections are new rows that reference the original. Flag any
  update, delete or "fix-up" path. (cc9b06bc)
- **Modules talk through their public API.** No imports from another module's `internal` package;
  `CrowdtraceModulesTest` must pass. Cross-module side effects go through events.
- **Migrations are append-only.** Never edit a merged `V*.sql`; add a new version. Each migration that adds
  PostgreSQL-specific SQL needs a test on real PostgreSQL (`TestPostgres`), because the default test profile is H2.
- **Time is UTC.** Inject the `Clock`; DB defaults use `CURRENT_TIMESTAMP AT TIME ZONE 'UTC'`. (c19f24bc, d78f9dd1)
- **The OpenAPI contract is the web app's type source.** A changed request or response documents every field and
  nullability, and the web types are regenerated in the same change. (a87b85b3, 8421e1c6)
- **No slow work inside a transaction.** File I/O, HTTP and duplicate detection run after the connection is
  released. (b14a00bc)
- **Untrusted input never reaches logs or audit rows unvalidated.** (4cfc986e)
- **Web:** backend calls live in `lib/api/client.ts` (typed `openapi-fetch`; raw `fetch` only where it can't
  type the request, as with multipart upload).
