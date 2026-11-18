#!/usr/bin/env bash
# Differential check: boots the base branch and the working tree on the same seeded data (no Docker), GETs every
# path in diff.json from both and reports unapproved differences. Usage: diff-against-base.sh [base-ref]
# The base ref must already contain TestCrowdtraceBackendApplication.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
base=${1:-origin/main}
tmp=$(mktemp -d)
git -C "$root" worktree add --detach "$tmp/base" "$base" >/dev/null
set -m # each background job gets its own process group, so cleanup reaches mvnw's children (app, PostgreSQL)
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  git -C "$root" worktree remove --force "$tmp/base"
}
trap cleanup EXIT

for port in 18080 18081; do # a leftover server would answer for code that never started
  if curl -s -o /dev/null "localhost:$port"; then echo "port $port is already in use; stop that server first"; exit 1; fi
done

boot() { # dir port
  (cd "$1" && exec ./mvnw -q -B spring-boot:test-run \
    -Dspring-boot.run.main-class=com.souldealers.crowdtracebackend.TestCrowdtraceBackendApplication \
    -Dspring-boot.run.arguments=--server.port="$2" >"$tmp/$2.log" 2>&1) &
  pids+=("$!")
}
boot "$tmp/base" 18081
boot "$root" 18080
i=0
for port in 18080 18081; do
  pid=${pids[$i]}; i=$((i + 1))
  for _ in $(seq 1 80); do
    curl -sf "localhost:$port/api/public/cases" >/dev/null && break
    kill -0 "$pid" 2>/dev/null || { echo "server for port $port exited during startup:"; tail -30 "$tmp/$port.log"; exit 1; }
    sleep 3
  done
  curl -sf "localhost:$port/api/public/cases" >/dev/null || { echo "port $port never came up:"; tail -30 "$tmp/$port.log"; exit 1; }
done
node "$root/.claude/verify/diff-api.mjs" http://localhost:18081 http://localhost:18080 "$root/.claude/verify/diff.json"
