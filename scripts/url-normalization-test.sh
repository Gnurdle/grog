#!/bin/sh
# url-normalization-test.sh — run the URL-field test against a THROWAWAY config
# home (the test's second half writes grog.edn via save-choice!).
#
# The two redirects (GROG_CONFIG_HOME + -Duser.home) are both required: see the
# note in recents-test.sh — grog.config also merges a LEGACY fragment under
# `~/.config/grog/grog.edn`, and `~` comes from the JVM's user.home, not $HOME.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(dirname "$here")
scratch=$(mktemp -d "${TMPDIR:-/tmp}/grog-url-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT INT TERM
GROG_CONFIG_HOME="$scratch" XDG_CONFIG_HOME="$scratch" exec clojure \
  -J-Duser.home="$scratch" \
  -J--add-opens=java.base/java.lang=ALL-UNNAMED \
  -M "$root/scripts/url-normalization-test.clj"
