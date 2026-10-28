#!/bin/sh
# recents-test.sh — run the recents/LRU test against a THROWAWAY config home.
#
# `grog.platform/config-home-dir` resolves the config home from the
# GROG_CONFIG_HOME ENV VAR (then $XDG_CONFIG_HOME, then ~/.config). The test
# writes grog.edn, so it must be pointed at a scratch directory — a JVM system
# property will NOT do it, and getting this wrong writes the developer's real
# config (which is exactly what happened the first time this test ran).
#
# TWO redirects are needed, not one. Isolating the config HOME is not enough,
# because grog.config ALSO merges a LEGACY fragment at `~/.config/grog/grog.edn`
# (a second entry point into the same user config) — and `~` there comes from
# `user.home`, which the JVM reads from the passwd entry, NOT from $HOME. So a
# plain `GROG_CONFIG_HOME=…` run still loaded the developer's real grog.edn (with
# its real `:llm :recents`) as the legacy fragment, and the test's "an empty
# config has no recents" check failed on a machine with a non-empty recents list
# — a FALSE failure, diagnosed 2026-10-26. `-Duser.home` is what closes it.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(dirname "$here")
scratch=$(mktemp -d "${TMPDIR:-/tmp}/grog-recents-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT INT TERM
GROG_CONFIG_HOME="$scratch" XDG_CONFIG_HOME="$scratch" exec clojure \
  -J-Duser.home="$scratch" \
  -J--add-opens=java.base/java.lang=ALL-UNNAMED \
  -M "$root/scripts/recents-test.clj"
