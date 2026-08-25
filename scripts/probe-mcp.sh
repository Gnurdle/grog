#!/usr/bin/env bash
# Probe an MCP stdio server: send initialize + tools/list, print tool names.
set -e
SRV="$1"  # e.g. grog-oracle or grog-babashka
DIR="/d/gni/grog/${SRV}"
cd "$DIR"
#
# Build JSON-RPC frames and pipe to the server, then capture its NDJSON out.
init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
notif='{"jsonrpc":"2.0","method":"notifications/initialized"}'
list='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
(
  printf '%s\n' "$init"
  sleep 2
  printf '%s\n' "$notif"
  printf '%s\n' "$list"
  sleep 2
) | timeout 30 clojure -M:mcp 2>/dev/null \
  | python -c '
import sys, json
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try: m=json.loads(line)
    except Exception: continue
    if m.get("id")==2:
        tools=[t["name"] for t in m.get("result",{}).get("tools",[])]
        print("TOOLS:", tools)
'