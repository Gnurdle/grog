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
- **Memory is Python/SQLite** (EDN-store idea dropped): `grog-memory` = associative
  `assoc_*` store keyed on `GROG_MEMORY_DB`. No `memory_*` EDN-file emulation.
- **Language split**: imaging = JVM Clojure; memory = Python (sqlite3); odoo = JVM Clojure.

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

2. **`grog-memory/`** (Python, `mcp>=1.9,<2.0`)
   - `src/grog_memory/server.py` — FastMCP stdio server; SQLite `assoc` store:
     `assoc_store/get/keys/delete/search`. DB via `GROG_MEMORY_DB`.
   - **Verified**: initialize + assoc_store→assoc_get persisted.
   - Note: pinned `mcp<2` because `FastMCP` was removed in the mcp 2.x rewrite.
   - Run: `PYTHONPATH=src .venv/bin/python -m grog_memory.server`.

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
     find/replace/delete-table-row; optional LibreOffice render via `GROG_OFFICE_BIN`).
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
    "grog-memory":  { "command": "python", "args": ["-m", "grog_memory.server"],
                      "env": { "GROG_MEMORY_DB": "/abs/path/.grog-memory.db" } },
    "grog-odoo":    { "command": "clojure", "args": ["-M:mcp", "-m", "grog-odoo.main"],
                      "env": { "GROG_ODOO_URL": "...", "GROG_ODOO_DB": "...",
                               "GROG_ODOO_USER": "...", "GROG_ODOO_PASSWORD": "..." } }
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
- **`grog-ui` truncates `~/.grog-ui.log` on every launch** (fresh log per session).

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
