# Grog Session State

## Date
2026-08 (current session) — "migrate grog to ECA" workstream is **complete and
live**. grog's GUI now drives ECA end-to-end, the workspace abstraction is gone,
and the MCP server suite is wired into the generated ECA config. This session has
also iterated hard on GUI feel (fonts, toolbar, status/trust indicators).

## The big idea (see `doc/gap-analysis-grog-vs-eca.md` for the full analysis)
Rather than rebuild grog's agentic loop to match ECA, **attach grog to ECA as a client**.
ECA is a server with a documented JSON-RPC-2.0-over-stdio protocol (`docs/protocol.md` in
the ECA repo). grog keeps its client shell (Swing GUI, embedded terminal, appearance)
and exposes its special tools as standalone **MCP servers** that ECA's loop calls.

Key decisions locked in (all done):
- **Workspace model dies** ✅ done: grog's `:workspace :default-root` + `resolve-workspace-path!` +
  containment abstraction has been removed (`workspace_paths.clj` deleted). ECA uses
  `workspaceFolders` (declared at `initialize`) pointed at the repo root
  (`grog.config/repo-root` → `grog.home` / `GROG_HOME` / cwd). MCP servers take paths
  **as given**; grog tool paths are plain absolute/repo-root-relative. → doc §6.7.
- **Memory is Clojure/SQLite (JVM)** (EDN-store idea dropped): `grog-memory` =
  associative `assoc_*` store keyed on `~/.config/grog/memory.edn` (`:db`),
  served by the `grog-mcp` bundle (`--server grog-memory`). No `memory_*` EDN-file
  emulation. (The Python server is kept only as legacy reference.)
- **Language split**: imaging = JVM Clojure; memory = JVM Clojure (SQLite via JDBC); odoo = JVM Clojure.

## What exists now (under `/d/gni/grog/`)
Self-contained MCP servers over stdio, all implemented & verified. The generated ECA
config (see §ECA config wiring) registers **five**: grog-imaging, grog-memory,
grog-odoo, grog-office, grog-search.

1. **`grog-imaging/`** (JVM Clojure, MCP SDK 0.8.0)
   - `deps.edn` (pdfbox 3.0.4, tess4j 5.14.0, boofcv 1.2.2, poi-ooxml 5.3.0, cheshire)
   - `src/grog_imaging/image.clj` — vendored self-contained engine (OCR/PDF/crop/png),
     renamed from grog's `grog.image`.
   - `src/grog_imaging/tools.clj` — real handlers: `read_pdf_document`, `ocr_pdf_document`,
     `analyze_pdf_line_drawings`, `read_office_document`, `write_workspace_png`,
     `crop_workspace_image`.
   - `src/grog_imaging/main.clj` — MCP server wiring the 6 tools.
   - **Verified** via MCP round-trips: `read_office_document` (docx), `read_pdf_document`
     (11 pages), `write_workspace_png` (valid PNG). Compiles, no diagnostics.
   - Gotcha fixed: MCP SDK passes tool `arguments` as `java.util.Map` — `map?` is false,
     so `parse-args` must handle `java.util.Map`/Jackson nodes.
   - Run: `clojure -M:mcp` (alias `:mcp`).

2. **`grog_mcp/` (memory tools) — the default `grog-memory`** (Clojure, SQLite via JDBC)
   - `src/grog_mcp/memory.clj` — byte-compatible Clojure re-implementation of the
     Python `assoc_*` server (same 7 tools, same schema, same `memory.edn`
     `:db`); served by the bundle restricted to one server:
     `clojure -M:mcp --server grog-memory`. Existing `mem.db` files work as-is.
   - **Verified**: MCP `initialize` + `tools/list` (7 tools) + store→get→delete.
   - **`grog-memory/` (Python, legacy)** — the original FastMCP server; no longer
     wired (kept as reference only).

3. **`grog-odoo/`** (JVM Clojure)
   - `src/grog_odoo/xmlrpc.clj` — self-contained XML-RPC client (clj-http + clojure.xml),
     fault-aware. **Verified**: encode/decode against sample Odoo-shaped responses.
   - `src/grog_odoo/main.clj` — MCP server: `odoo_authenticate`, `odoo_search_read`,
     `odoo_create`, `odoo_write`, `odoo_unlink`, `odoo_call_method`, `odoo_get_fields`.
   - Env connection: `GROG_ODOO_URL/DB/USER/PASSWORD` or `GROG_ODOO_CONFIG`
     (multi-instance JSON). **Verified**: handshake + tools/list.
   - Run: `clojure -M:mcp`.

4. **`grog-office/`** (JVM Clojure)
   - `src/grog_office/core.clj` — Apache POI docx manipulation (import/block model,
     find/replace/delete-table-row; optional LibreOffice render via `office.edn` `:bin`).
   - `src/grog_office/main.clj` — MCP server: `import_document`, `list_handles`,
     `list_blocks`, `get_text`, `find_text`, `replace_text`, `delete_table_row`,
     `render`, `save`, `close_document`.
   - Run: `clojure -M:mcp -m grog-office.main`. (Wired in config; verified handshake.)

5. **`grog-search/`** (JVM Clojure) — Brave web search as MCP
   - `src/grog_search/main.clj` — MCP server exposing `brave_web_search` (query + optional
     count). Restores the tool ECA's model loop used to get from grog's old self-hosted
     tool dispatch (see the gap-analysis keep-table). API key from OS keyring service
     `grog`, account `BRAVE_SEARCH_API` (same as before). **Verified**: initialize +
     tools/list show `brave_web_search`; a live `tools/call` returned real formatted hits.
   - Run: `clojure -M:mcp -m grog-search.main`.

**Also present:** `datascript-mcp-server/` (upstream sample, not ours — gitignored/untracked),
`notes/` (scratch), `e` (stray untracked file, likely an accidental editor artifact).

## ECA config wiring (live)
`grog.eca-config/generate-config!` starts from `~/.config/eca/config.json` (working
providers), merges the **five** `mcpServers` (grog-imaging / grog-memory / grog-odoo /
grog-office / grog-search) + tool-approval allowlist + `defaultModel`
(qualifies via `grog.models/qualify-eca-model`), writes
`~/.config/grog/eca-config.generated.json`, and the GUI launches
`eca server --config-file <that>`. The model sees the tool(s) of every server that
reaches `running`.

Example shape:
```json
{ "mcpServers": {
    "grog-imaging": { "command": "clojure", "args": ["-M:mcp", "-m", "grog-imaging.main"] },
    "grog-memory":  { "command": "bash", "args": ["-lc", "cd '<repo>/grog_mcp' && clojure -M:mcp --server grog-memory"] },
    "grog-odoo":    { "command": "clojure", "args": ["-M:mcp", "-m", "grog-odoo.main"],
                      "env": { "GROG_ODOO_CONFIG": "~/.config/grog/odoo-instances.edn" } }
} }
```
Note: grog's old subfolder `workspace/` + data (e2-csv, PDFs, memory) are now just a
normal directory to ECA (no workspace-root semantics).

## GUI (this session's polish — all live)
- **Fonts follow the system L&F** (`grog.ui.widgets`): `ui-font` / `mono-font` scale
  the desktop's UI font ×1.5; `button-font` is compact (×1.3) for the toolbar.
  `scale-ui-fonts!` bumps the L&F base font keys after FlatLaf setup.
- **Footer is a real `JToolBar`**: icon-only op buttons (Send/Stop/Terminal/Settings/
  Export/Clear) with hover tooltips (`widgets/toolbar-button` + `action-icon`
  vector glyphs); status (idle/running/question/error) and trust (yolo on/off) are
  **coloured-dot icons** (`grog.ui.footer`), right-aligned; model name is small/dim.
- Approval dialog options: **Approve / Reject / YOLO** (YOLO approves + turns trust on).
- **Copy UX**: drag → Enter (or Ctrl+C, or right-click menu) copies and unselects
  (`transcript/copy-selection!`).
- **`/clear` and the Clear tool-bar button wipe the transcript AND reset YOLO off.**
- Window opened at **1350×1020** (~50% bigger both ways).
- **Logging is per-instance and in-process** (`grog.log`): each running grog writes
  its own `<base>.<pid>.log` (`~/grog-ui.<pid>.log` on Linux,
  `%USERPROFILE%\grog-ui.<pid>.log` on Windows) so concurrent instances never
  interleave. On startup the oldest instance logs are pruned, keeping
  `GROG_UI_LOG_KEEP` (default 5). The JVM tees `System.out`/`System.err` to the
  console **and** the file — no shell redirection, no rotation scripts, no
  platform-specific PID logic.

## Superseded grog loop / tool code
- ✅ **Workspace-scoped tool loop pruned** from `grog.fs` / `grog.core`:
  `read/write/grep/stat/…_workspace_*` and `crop_workspace_image` tool specs + run-*
  dispatch are gone from the old loop. The old loop (`run-tool-loop-on-messages`) **stays**
  because `grog.jobs` / `grog.chron` / CLI still use it with the **non-workspace** tools
  (office/pdf/ocr/analyze, memory, skills, brave, babashka, mcp).
- ⬜ Not yet pruned (still-present dead-ish code): `grog.core/chat-tools-payload` +
  `execute-tool-call!` (kept for jobs/chron), `grog.mcp` client, `chat_context` trim,
  dead tool namespaces `render.clj` / `e_trade.clj` / `ai.clj` / `physics.clj`.
  Low priority while jobs/chron still invoke the loop.

## External prerequisite (satisfied)
`eca` is installed and `eca server` works; the "attach to ECA" client path is proven
end-to-end. Providers are configured in `~/.config/eca/config.json`. Working model
used for testing: `openrouter/moonshotai/kimi-k2.6` (set via grog.edn `:eca :model`).
The ECA repo at `/d/ericdallo/eca` holds `docs/protocol.md` (the client reference,
including `chat/promptSteer` — grog's `eca/steer!` exists but the GUI does **not**
expose steering yet; that's a candidate next step).

## `doc/gap-analysis-grog-vs-eca.md`
The canonical analysis doc — includes the keep/drop tool table (§6.5), MCP granularity
(§6.6), and the workspace-model change (§6.7). Sections 1–5 are the "rebuild instead"
comparison; §6+ is the "attach to ECA" path being pursued.

## Fix: chat log was fed back as standing context (runaway context)
**Symptom:** prompts appeared to go in but nothing happened — the model stalled /
rambled / quoted the whole conversation. Standing context (`eca-rules.md`) had grown
to **~1 MB**, and the project's `dialog/thread.edn` was **~1 MB and doubling**.
**Root cause:** `grog.projects/load-context` walked the project's `dialog/` dir and
`list-context-files` treated `.edn` as context text, so it slurped the **entire chat
log** (`dialog/thread.edn`) back in as "project context". `grog.eca-config/project-rules-file`
embeds `load-context` into the ECA `rules` file, and `grog.chat-context` embeds it as a
system message — so every prompt contained the full, unbounded history **in addition to**
the actual conversation. The model's replies then quoted that history, which got appended
to `thread.edn`, which was re-embedded even bigger next turn (exponential growth → stall).
**Fix** (`src/grog/projects.clj`):
- `list-context-files` now excludes the chat log via `context-text-file?` /
  `chat-log-filename` (`thread.edn`); `notes/` context is unaffected. Bounded dialog
  replay still happens where intended, via `grog.project-dialog/thread-as-system-appendix`.
- `read-text-file` gained a `max-context-file-bytes` (256 KB) cap as defense-in-depth.
**Verified:** `load-context` 1 MB → 7.9 KB; regenerated `eca-rules.md` 1,048 KB → 12.6 KB
and contains no `:turns`; CLI `-M:run` replies cleanly again. The stale ~1 MB
`thread.edn` files remain on disk (user data) but no longer feed the prompt.
