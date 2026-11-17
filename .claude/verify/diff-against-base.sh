#!/usr/bin/env bash
# Differential check: boots the base branch and the working tree on the same seeded data (no Docker), GETs every
# path in diff.json from both and reports unapproved differences. Usage: diff-against-base.sh [base-ref]
# The base ref must already contain TestCrowdtraceBackendApplication.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
base=${1:-origin/main}
tmp=$(mktemp -d)
git -C "$root" worktree add --detach "$tmp/base" "$base" >/dev/null
cleanup() { kill $(jobs -p) 2>/dev/null || true; git -C "$root" worktree remove --force "$tmp/base"; }
trap cleanup EXIT

boot() { # dir port
  (cd "$1" && ./mvnw -q -B spring-boot:test-run \
    -Dspring-boot.run.main-class=com.souldealers.crowdtracebackend.TestCrowdtraceBackendApplication \
    -Dspring-boot.run.arguments=--server.port="$2" >"$tmp/$2.log" 2>&1) &
}
boot "$tmp/base" 18081
boot "$root" 18080
for port in 18080 18081; do
  for _ in $(seq 1 80); do curl -sf "localhost:$port/api/public/cases" >/dev/null && break; sleep 3; done
  curl -sf "localhost:$port/api/public/cases" >/dev/null || { echo "port $port never came up:"; tail -30 "$tmp/$port.log"; exit 1; }
done
node "$root/.claude/verify/diff-api.mjs" http://localhost:18081 http://localhost:18080 "$root/.claude/verify/diff.json"
