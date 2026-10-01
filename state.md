# Grog Internals

How grog is wired together. For configuration and usage see `README.md` and
`USERS-GUIDE.md`; for the MCP tool surface see `mcp-servers.md`.

## Architecture: grog attaches to ECA

grog does not implement its own agentic loop; it **attaches to ECA as a client**.
ECA is a server with a documented JSON-RPC-2.0-over-stdio protocol
(`docs/protocol.md` in the ECA repo). grog keeps its client shell (GUI, embedded
terminal, appearance) and exposes its special tools as standalone **MCP servers**
that ECA's loop calls.

## Workspace model

ECA uses `workspaceFolders` (declared at `initialize`) pointed at the repo root
(`grog.config/repo-root` → `grog.home` / `GROG_HOME` / cwd). There is no
workspace-root containment layer; MCP servers take paths **as given**, and grog
tool paths are plain absolute or repo-root-relative.

## Memory

`grog-memory` is a **Clojure/SQLite (JVM)** associative `assoc_*` store, served
by the `grog-mcp` bundle (`--server grog-memory`). The default store's path comes
from the **file config**, not env; grog writes that file to point at the
**active project's** store, giving per-project isolation. The store is
byte-compatible with the Python server in `grog-memory/` (same 7 tools, same
SQLite schema, same `memory.edn` `:db`), so existing `mem.db` files work as-is.

## Language split

- imaging — JVM Clojure
- memory — JVM Clojure (SQLite via JDBC)
- odoo — JVM Clojure

## MCP servers

Each server is a self-contained **stdio** MCP server; the full per-server,
per-tool inventory is in [`mcp-servers.md`](mcp-servers.md).

- **MCP SDK gotcha:** the SDK passes tool `arguments` as a `java.util.Map`, so
  `map?` returns false — argument parsing must handle `java.util.Map` / Jackson
  nodes.
- The `grog_mcp/` bundle registers all of the servers' tools on a single JVM
  (one `McpServer`, one classpath, one process). Its `vendor-src/` carries
  vendored copies of each server's `src/`; edits to a server's source should
  refresh `vendor-src/`.

## ECA config wiring

`grog.eca-config/generate-config!` starts from `~/.config/eca/config.json`
(providers), merges the `mcpServers` block + tool-approval allowlist +
`defaultModel` (qualified via `grog.models/qualify-eca-model`), writes
`~/.config/grog/eca-config.generated.json`, and the GUI launches
`eca server --config-file <that>`. The model sees the tool(s) of every server
that reaches `running`.

## GUI

- Approval dialog options: **Approve / Reject / YOLO** (YOLO approves and turns
  trust on).
- **`/clear`** and the Clear button wipe the transcript **and** reset YOLO off.
- **Logging is per-instance and in-process** (`grog.log`): each running grog
  writes its own `<base>.<pid>.log` (`~/grog.<pid>.log` on Linux,
  `%USERPROFILE%\grog.<pid>.log` on Windows) so concurrent instances never
  interleave. On startup the oldest instance logs are pruned, keeping
  `GROG_UI_LOG_KEEP` (default 5). The JVM tees `System.out`/`System.err` to the
  console **and** the file — no shell redirection, no rotation scripts, no
  platform-specific PID logic.

## Invariant: the chat log is never standing context

The project's `dialog/thread.edn` must never be embedded as standing context.
`grog.projects/load-context` excludes it via `context-text-file?` /
`chat-log-filename` (the project's `notes/` context is unaffected), and
`read-text-file` caps individual context files at `max-context-file-bytes`
(256 KB) as defense-in-depth. Bounded dialog replay happens only where intended,
via `grog.project-dialog/thread-as-system-appendix`.

A `.edn` chat log treated as ordinary context text would be re-embedded on every
prompt and grow without bound: `grog.eca-config/project-rules-file` embeds
`load-context` into the ECA rules file and `grog.chat-context` embeds it as a
system message, so each prompt would carry the entire history in addition to the
live conversation, and the model's quoting of that history would be appended
back and re-embedded even larger next turn.
