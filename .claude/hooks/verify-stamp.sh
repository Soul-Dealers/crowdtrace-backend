#!/usr/bin/env bash
# Ties a verification to the exact content it checked.
#   verify-stamp.sh tree           print a hash of the working tree (tracked + untracked, minus .gitignore)
#   verify-stamp.sh record "<how>" stamp the current working tree as verified, e.g. "e2e, unit, review"
# The commit-msg hook (.claude/hooks/git/commit-msg) compares the tree being committed with this stamp.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
gitdir=$(git -C "$root" rev-parse --absolute-git-dir)
state="$root/.claude/state"
mkdir -p "$state"

tree() {
  local idx rc
  idx=$(mktemp) || return 1
  if [ -f "$gitdir/index" ]; then cp "$gitdir/index" "$idx" || return 1; else rm -f "$idx"; fi
  if ! GIT_INDEX_FILE="$idx" git -C "$root" add -A; then rm -f "$idx"; return 1; fi
  GIT_INDEX_FILE="$idx" git -C "$root" write-tree && rc=0 || rc=$?
  rm -f "$idx"
  return "$rc"
}

case "${1:-}" in
  tree) tree ;;
  record)
    [ -n "${2:-}" ] || { echo "usage: verify-stamp.sh record \"<how>\"" >&2; exit 64; }
    hash=$(tree) || { echo "Could not hash the working tree; nothing stamped." >&2; exit 1; }
    printf '%s %s\n' "$hash" "$2" >"$state/verified"
    echo "Stamped. Stage all of it and commit with the trailer:  Verified: $2"
    ;;
  *) echo "usage: verify-stamp.sh tree|record <how>" >&2; exit 64 ;;
esac
