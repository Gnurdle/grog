#!/usr/bin/env bash
# Build the self-contained grog-alpaca uberjar (target/grog-alpaca.jar).
# Requires the Clojure CLI only on the *build* machine; the resulting jar runs
# on any machine with a JRE 17+ (`java -jar grog-alpaca.jar`).
set -euo pipefail
cd "$(dirname "$0")"
clojure -T:build uber
echo "Built: $(pwd)/target/grog-alpaca.jar"
