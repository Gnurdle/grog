#!/bin/sh
# model-picker-test.sh — run the model-picker test against a THROWAWAY config
# home.
#
# Same two redirects as recents-test.sh, for the same reason: the test reads and
# WRITES grog.edn, and `grog.config` ALSO merges a legacy fragment at
# `~/.config/grog/grog.edn` whose `~` comes from `user.home` (the JVM passwd
# entry, not $HOME). Without both, the test would run against the developer's
# real config.
#
# The test also STUBS the local endpoints it probes, so it never depends on a
# server being up (see the stub in model-picker-test.clj).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(dirname "$here")
scratch=$(mktemp -d "${TMPDIR:-/tmp}/grog-model-picker-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT INT TERM
GROG_CONFIG_HOME="$scratch" XDG_CONFIG_HOME="$scratch" exec clojure \
  -J-Duser.home="$scratch" \
  -J--add-opens=java.base/java.lang=ALL-UNNAMED \
  -M "$root/scripts/model-picker-test.clj"
