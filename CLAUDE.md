# CrowdTrace

Spring Boot 4.1 / Java 21 modulith (`src/`) and a Next.js 16 app (`apps/web`, pnpm). Docs and decisions live in
`docs/`; review rules in `REVIEW.md`.

## Commands
- Backend tests: `./mvnw test` (real PostgreSQL via embedded binaries, no Docker). Fast subset: `-Dgroups=fast`.
- Run the backend with seeded data, no Docker: `./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.souldealers.crowdtracebackend.TestCrowdtraceBackendApplication`
  (users in `db/dev-seed/R__dev_seed.sql`, password `Password123!`).
- Web: `pnpm web`, `pnpm typecheck`, `pnpm lint`, `pnpm --filter web test` (unit/property),
  `pnpm --filter web test:e2e` (Playwright boots a fresh backend on :18080 and web on :3100, clear of the
  Compose app on :8080 and other dev servers on :3000).
- Playwright's Chromium does not launch inside the Claude sandbox; run E2E with the sandbox off.

## Verification

A change is done when it has been verified, not when it compiles. Verify the change by running it.

**The loop**
1. Fast checks run on their own when you finish (`.claude/verify/fast-checks.sh`, from the Stop hook).
   If they fail, fix the cause. After three failed rounds, stop and tell me what's failing.
2. Before every commit, run the `verify` skill. It drives the real app, runs the matching checklist
   items, gets a review against `REVIEW.md`, and stamps the tree.
3. Commit with the `Verified: <how>` trailer it gives you. If verification truly can't run, use
   `Unverified: <why>` instead. The commit gate enforces this. `git log --grep Unverified` finds the gaps.

**Rules**
- Never edit a check to make it pass: tests, `REVIEW.md`, `.claude/hooks/`, `.claude/verify/`.
  If a check is wrong, say so and ask.
- Flaky tests: never skip, add sleeps or retry until green. Find the root cause. If you can't, delete the
  test and record why in the commit message.
- Prefer, in order: an E2E test of the user flow, a property test, a differential test, an example test.
- A changed test must still fail without the change it covers. Check this by reverting the change.
- When a bug slips past verification, add the check that would have caught it: a test, a
  `.claude/verify/checklist.md` item (trigger, check, source), or a `REVIEW.md` rule. Same for a
  repeated review comment.
- Keep the verification layer small. When a rule or check stops earning its keep, propose deleting it.
