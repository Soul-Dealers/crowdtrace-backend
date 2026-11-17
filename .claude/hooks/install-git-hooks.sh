#!/usr/bin/env bash
# SessionStart hook: points git at .claude/hooks/git so the commit gate is active in every clone and worktree.
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -d "$root/.claude/verify" ] || exit 0 # repo hasn't opted in
current=$(git -C "$root" config core.hooksPath || true)
[ "$current" = .claude/hooks/git ] && exit 0
if [ -n "$current" ]; then
  echo "core.hooksPath is '$current', so the verification gate in .claude/hooks/git is not active. Tell the user."
  exit 0
fi
git -C "$root" config core.hooksPath .claude/hooks/git
