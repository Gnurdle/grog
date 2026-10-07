#!/usr/bin/env bash
# Build a GraalVM native image of the grog backend (grog.server).
#
#   scripts/native-image.sh            # AOT → native-image (uses committed config)
#   AGENT=1 scripts/native-image.sh    # ALSO regenerate the reachability config
#                                      #   from a DEEP run under the tracing agent
#
# Output: target/native/grog-server
#
# Why this shape:
#   * Clojure needs AOT classes for native-image to see them at all.
#   * graal-build-time (the :native alias) initializes Clojure at BUILD time, so
#     the image doesn't boot all of Clojure on every launch.
#   * native-image fails at RUNTIME (not build time) for reflective paths the
#     tracing agent never saw. Our stack is very reflective (cheshire, clj-http,
#     sqlite-jdbc, keyring, ECA child spawn). So the agent must be driven through
#     the DEEP paths — opening a session, prompting — not just `projects`.
#     scripts/smoke-server.js does exactly that, and its output
#     (native-image/reachability-metadata.json) is committed so ordinary builds
#     are reproducible and offline. Use AGENT=1 only when dependencies change.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRAALVM_HOME="${GRAALVM_HOME:-/usr/lib/jvm/java-25-graalvm}"
NI="$GRAALVM_HOME/bin/native-image"
[ -x "$NI" ] || { echo "native-image not found at $NI — set GRAALVM_HOME" >&2; exit 1; }

CLASS_DIR="target/native/classes"
CFG_DIR="native-image"          # committed: reachability-metadata.json
OUT="target/native/grog-server"

echo "==> clean"
rm -rf "$CLASS_DIR" "$OUT"
mkdir -p "$CLASS_DIR" "$CFG_DIR"

echo "==> AOT compile grog.server.main → $CLASS_DIR"
clojure -M:native -e "(binding [*compile-path* \"$CLASS_DIR\"] (compile 'grog.server.main))"

CP="$CLASS_DIR:$(clojure -Spath -M:native)"

if [ "${AGENT:-0}" = "1" ]; then
  echo "==> regenerating $CFG_DIR/reachability-metadata.json from a deep run"
  echo "    (drives open/prompt/close under the agent — needs network + a model key)"
  node scripts/smoke-server.js "$GRAALVM_HOME/bin/java" \
    -agentlib:native-image-agent=config-output-dir="$CFG_DIR" \
    -cp "$CP" clojure.main -m grog.server
fi

[ -f "$CFG_DIR/reachability-metadata.json" ] \
  || { echo "missing $CFG_DIR/reachability-metadata.json — run with AGENT=1" >&2; exit 1; }

echo "==> native-image → $OUT"
"$NI" \
  -cp "$CP" \
  -o "$OUT" \
  -H:+ReportExceptionStackTraces \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time \
  --initialize-at-run-time=com.sun.jna,com.github.javakeyring,org.jline,org.apache.http \
  -H:ConfigurationFileDirectories="$CFG_DIR" \
  "$@" \
  grog.server.main

echo "==> done: $OUT"
ls -lh "$OUT" 2>/dev/null || true
