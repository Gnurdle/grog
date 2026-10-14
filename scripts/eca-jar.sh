#!/bin/sh
# Build the ECA that grog ships.
#
# THE ECA WE BUILD IS THE TWO LINES BELOW. Nothing else selects it: no
# environment variables, no fallback to a local checkout, no reuse of a jar that
# happens to be lying around. Whatever this clone produces is what lands in
# target/eca.jar.
#
# ECA_REF is a BRANCH on purpose: it tracks ECA as it is being worked on, so
# shipping a new ECA needs no edit in grog. The price is that the ref moves, and
# two builds of one grog version can therefore differ — which is why the resolved
# COMMIT is written to target/eca.jar.provenance.edn beside the jar. That record,
# not this file, is what says which ECA a given build carries. Pin a sha or a tag
# instead if you ever want two builds to be identical.
#
# Change a line, rebuild. That is the entire interface.
#
# Why not a native binary: native-image cannot cross-compile, so a Windows
# artifact must be built on Windows, while one jar is platform-independent and
# grog already requires a JRE for the spine. See doc/eca-fork.md.
set -e

ECA_REPO="https://github.com/Gnurdle/eca.git"
ECA_REF="dmsl-tools"

GROG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLONE="$GROG_ROOT/target/eca-src"
OUT="$GROG_ROOT/target/eca.jar"

command -v clojure >/dev/null || { echo "clojure CLI not found" >&2; exit 1; }

case "$ECA_REPO" in
  *CHANGE-ME*)
    echo "ECA_REPO is still the placeholder. Put the real ECA repo URL in the two" >&2
    echo "constants at the top of this script, then re-run." >&2
    exit 1 ;;
esac
printf 'ECA repo : %s\n' "$ECA_REPO"
printf 'ECA ref  : %s\n' "$ECA_REF"

# A fresh shallow clone of exactly that ref, every time. No cache to go stale and
# no working tree that can be dirty — which is what makes "a jar that matches no
# commit" structurally impossible here instead of merely discouraged.
rm -rf "$CLONE"
mkdir -p "$CLONE"
git -C "$CLONE" init -q
git -C "$CLONE" remote add origin "$ECA_REPO"
if ! git -C "$CLONE" fetch -q --depth 1 origin "$ECA_REF"; then
  echo "could not fetch $ECA_REF from $ECA_REPO" >&2
  echo "  - is that commit there? a push keeps shas; a rebase or force-push does not" >&2
  echo "  - can git authenticate? this uses your normal git credentials and it" >&2
  echo "    CANNOT PROMPT (there is no terminal). So: an SSH key, or for https a" >&2
  echo "    STORED credential — credential.helper, or ~/.netrc. CI supplies its own." >&2
  exit 1
fi
git -C "$CLONE" checkout -q --detach FETCH_HEAD
commit="$(git -C "$CLONE" rev-parse HEAD)"

# The pin is the pin: if ECA_REF is a full sha, the checkout MUST be that sha.
if [ "${#ECA_REF}" = 40 ] && [ "$commit" != "$ECA_REF" ]; then
  echo "checked out $commit but ECA_REF says $ECA_REF — refusing to build" >&2
  exit 1
fi
printf 'commit   : %s\n' "$commit"

printf 'building : clojure -T:build prod-cli (AOT uberjar, a few minutes)\n'
( cd "$CLONE" && clojure -T:build prod-cli )
[ -f "$CLONE/target/eca.jar" ] || { echo "build produced no $CLONE/target/eca.jar" >&2; exit 1; }

cp "$CLONE/target/eca.jar" "$OUT"
printf 'wrote    : %s (%s bytes)\n' "$OUT" "$(wc -c < "$OUT" | tr -d ' ')"

# Provenance, printed as data rather than assembled as text so it cannot end up
# malformed: the answer to "which ECA is in this build?" sits next to the jar.
bb -e '(let [[p repo ref sha when] *command-line-args*]
         (spit p (pr-str {:eca {:repo repo :ref ref :commit sha} :built-at when}))
         (prn (read-string (slurp p))))' \
  "$OUT.provenance.edn" "$ECA_REPO" "$ECA_REF" "$commit" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
