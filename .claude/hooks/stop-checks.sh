#!/usr/bin/env bash
# Stop hook. When Claude finishes with uncommitted changes, runs .claude/verify/fast-checks.sh.
# Failure sends Claude back to fix it; after three failed rounds it stops and hands over to a human.
# Skips when the tree is unchanged since the last green run.
input=$(cat)
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
checks="$root/.claude/verify/fast-checks.sh"
[ -x "$checks" ] || exit 0
state="$root/.claude/state"
mkdir -p "$state"
session=$(jq -r '.session_id // "none"' <<<"$input")
fails="$state/fast-fails-$session"

tree=$("$root/.claude/hooks/verify-stamp.sh" tree) || exit 0
[ "$tree" = "$(git -C "$root" rev-parse 'HEAD^{tree}' 2>/dev/null)" ] && exit 0
[ "$tree" = "$(cat "$state/fast-green" 2>/dev/null)" ] && exit 0

if out=$(cd "$root" && "$checks" 2>&1); then
  echo "$tree" >"$state/fast-green"
  rm -f "$fails"
  exit 0
fi

n=$(( $(cat "$fails" 2>/dev/null || echo 0) + 1 ))
echo "$n" >"$fails"
if [ "$n" -gt 3 ]; then
  jq -n '{systemMessage: "Fast checks still fail after three rounds. Claude stopped; this needs a human."}'
  exit 0
fi
jq -n --arg r "Fast checks failed (round $n of 3). Find and fix the cause. Do not skip, retry or loosen a check.
$(tail -n 60 <<<"$out")" '{decision: "block", reason: $r}'
