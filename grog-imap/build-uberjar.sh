#!/usr/bin/env bash
# Build the self-contained grog-imap uberjar (target/grog-imap.jar).
# Requires the Clojure CLI only on the *build* machine; the resulting jar runs
# on any machine with a JRE 17+ (`java -jar grog-imap.jar`).
set -euo pipefail
cd "$(dirname "$0")"
clojure -T:build uber
echo "Built: $(pwd)/target/grog-imap.jar"
