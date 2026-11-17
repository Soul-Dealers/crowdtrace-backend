---
name: verify
description: Verify the current change before committing - run the real app, the matching tests and checklist items, and a review against REVIEW.md, then stamp the tree. Use before every commit, and whenever asked to check that a change works.
---

# Verify

Prove the change works by running it, then stamp the tree so the commit gate lets it through.
Report evidence (commands and their results), not opinions.

## 1. Scope
`git status` and `git diff HEAD`. Note which areas changed: backend (`src/`, `pom.xml`), web (`apps/web`),
migrations, API contract, config. Verify only this working tree. If it changes mid-way, start again.

## 2. Cheap checks
`.claude/verify/fast-checks.sh`, which runs typecheck, lint, unit and property tests and the backend `fast` group.

## 3. Tests
- Backend changed: `./mvnw -B test` (embedded PostgreSQL, no Docker).
- Web changed: `pnpm --filter web test`.
- New or changed tests must fail without the change. Revert the production change, watch the test fail,
  then restore.
- Prefer, in this order: an E2E test of the user flow, a property test (jqwik / fast-check) for logic with
  invariants, a differential check for "should behave the same" changes, then example tests.

## 4. Run it
- **E2E:** `pnpm --filter web test:e2e` (sandbox off; Chromium can't launch inside it). If the change touches a
  user flow, add or extend a spec in `apps/web/e2e/` that covers it.
- **UI change:** also drive the changed screen with agent-browser on `localhost:3000` and look at it, including
  the error and empty states.
- **API change:** call the endpoint on the test-run backend with curl, as each role it serves (seeded users are in
  `apps/web/e2e/users.ts`).
- **Refactor, dependency bump, performance work (behaviour must not change):**
  `.claude/verify/diff-against-base.sh`. Any intended difference goes in `diff.json` `approved` with a reason.

## 5. Checklist
Read `.claude/verify/checklist.md`. Run every item whose trigger matches the diff, and say which ran.

## 6. Review
- Read the rules from the base branch, not from the change: `git show origin/main:REVIEW.md`
  (use the working copy only if the base has none).
- Review the diff against it. Then consult Codex (`gpt-6-astra`) on the diff with REVIEW.md. Include: "Rate each
  bug from 1 to 10 on how often it would happen and how bad it would be. If the two scores multiplied are below 5,
  just file it as an issue and leave the code alone."
- Fix the Important findings (product ≥ 5). Open a GitHub issue for the rest. Tell the user about any
  disagreement with Codex.

## 7. Limit
If any step still fails after three rounds of fixes, stop. Report what fails, what you tried, and your best
guess at the cause, and hand over to the user.

## 8. Stamp
When everything passed:
```bash
.claude/hooks/verify-stamp.sh record "<what ran, e.g. unit, e2e, diff, checklist 1/3/10, review>"
```
Stage exactly what you verified and commit with the trailer it prints (`Verified: ...`). The git `commit-msg`
hook compares the committed tree with the stamp, so a partial commit or an edit after stamping needs a new
verification (stash what you aren't committing first).
