#!/usr/bin/env bash
# Shared by the navigation tasks' checks: passes if the agent's final reply, on stdin, contains every fact given as an
# argument (as a plain string, ignoring case). Prints the missing ones on stderr.
reply=$(cat)
missing=0
for fact in "$@"; do
  if ! grep -qiF -- "$fact" <<<"$reply"; then
    echo "missing from the reply: $fact" >&2
    missing=1
  fi
done
exit "$missing"
