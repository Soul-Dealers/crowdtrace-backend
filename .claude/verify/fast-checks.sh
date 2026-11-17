#!/usr/bin/env bash
# Cheap checks the Stop hook runs whenever Claude finishes with changes. Keep it under ~2 minutes;
# the full test suite, E2E and review belong to the verify skill and CI.
set -euo pipefail
changed=$({ git diff --name-only HEAD; git ls-files --others --exclude-standard; })
if grep -qE '^(apps/web/|package\.json|pnpm-lock\.yaml|eslint\.config)' <<<"$changed"; then
  pnpm -s typecheck
  pnpm -s lint
  pnpm --filter web -s test
fi
if grep -qE '^(src/|pom\.xml)' <<<"$changed"; then
  ./mvnw -q -B test -Dgroups=fast # compiles everything, runs @Tag("fast") tests
fi
