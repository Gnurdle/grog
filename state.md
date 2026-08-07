# Grog Session State

## Date
2026-08 (current session) — "migrate grog to ECA" workstream. ECA client build is
in progress: stdio client + GUI stream rendering + control surface + config
generation are implemented and verified (see Plan below). Remaining: prune
superseded grog loop/tool code.

## The big idea (see `doc/gap-analysis-grog-vs-eca.md` for the full analysis)
Rather than rebuild grog's agentic loop to match ECA, **attach grog to ECA as a client**.
ECA is a server with a documented JSON-RPC-2.0-over-stdio protocol (`docs/protocol.md` in
the ECA repo). grog keeps its client shell (Swing GUI, embedded terminal, appearance)
and exposes its special tools as standalone **MCP servers** that ECA's loop calls.

Key decisions locked in:
- **Workspace model dies**: grog's `:workspace :default-root` + `resolve-workspace-path!` +
  containment is a grog-only invention ECA ignores. ECA uses `workspaceFolders` (declared
  at `initialize`). MCP servers take paths **as given** (no workspace layer). → doc §6.7.
- **Memory is Python/SQLite** (EDN-store idea dropped): `grog-memory` = associative
  `assoc_*` store keyed on `GROG_MEMORY_DB`. No `memory_*` EDN-file emulation.
- **Language split**: imaging = JVM Clojure; memory = Python (sqlite3); odoo = JVM Clojure.

## What exists now (under `/d/gni/grog/`)
Three self-contained MCP servers over stdio, all implemented & verified:

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
   - Env connection: `GROG_ODOO_URL/DB/USER/PASSWORD`. **Verified**: handshake + tools/list.
   - Run: `clojure -M:mcp`.

## ECA config wiring (for later)
Each README has the `mcpServers` JSON to drop into a generated ECA `config.json`
(JSON only; grog will generate it and launch `eca server --config-file <gen.json>`).
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
normal directory to ECA.

## Plan / remaining steps (from eca task tracker)
1. ✅ Migrate grog-imaging into `grog-imaging/` (done).
2. ✅ Align grog-memory (EDN store dropped → SQLite assoc) (done).
3. ✅ **Build grog's ECA client** — the critical path (done & verified):
   - ✅ JSON-RPC-2.0-over-stdio client (`src/grog/eca.clj`): LSP-style `Content-Length`
     framing; spawns `eca server`, `initialize` with `workspaceFolders`, `chat/prompt`,
     dispatches `chat/contentReceived`. Verified against real `eca` 0.134.2
     (`clojure -M:eca-test` streams reasonStarted→reasonText→text→reasonFinished).
   - ✅ Render the stream into the Swing transcript (`src/grog/ui/eca_stream.clj`
     maps text/reason*/toolCall*/usage/metadata/flag/progress onto styled runs;
     `src/grog/ui.clj` now sends prompts via ECA instead of `run-tool-loop-on-messages`).
   - ✅ Control surface: Stop→`chat/promptStop`; `toolCallRun` with `manualApproval`
     → approve/reject dialog (`chat/toolCallApprove`/`chat/toolCallReject`);
     `/eca-model <name>` → `chat/selectedModelChanged`.
   - ✅ grog generates ECA's JSON config (`src/grog/eca_config.clj`): starts from
     `~/.config/eca/config.json` (working providers), merges the three `mcpServers`
     (grog-imaging / grog-memory / grog-odoo) + `defaultModel`, writes to
     `~/.config/grog/eca-config.generated.json`, and the GUI launches
     `eca server --config-file <that>`. **Verified**: all three MCP servers start
     and reach `running` with tools loaded.
4. ⬜ Prune superseded grog loop/tool code once the client is live (core.clj tool
   dispatch, `chat-tools-payload`, `execute-tool-call!`, grog's `mcp.clj` client,
   chat_context trim, dead code like `render.clj`/`e_trade.clj`/`ai.clj`/`physics.clj`).

## External prerequisite (satisfied)
`eca` 0.134.2 is installed at `/usr/local/bin/eca`; `eca server` works, and the
ECL "attach to ECA" client path is proven end-to-end. Providers are configured in
`~/.config/eca/config.json` (moonshot / xai / ollama / openrouter). Working model
used for testing: `openrouter/moonshotai/kimi-k2.6` (set via grog.edn `:eca :model`;
the config's old `defaultModel` `openrouter/deepseek/...` had no matching model and
was replaced by grog's generated config). Note the ECA repo at `/d/ericdallo/eca`
holds `docs/protocol.md` (the client reference).

## `doc/gap-analysis-grog-vs-eca.md`
The canonical analysis doc — includes the keep/drop tool table (§6.5), MCP granularity
(§6.6), and the workspace-model change (§6.7). Sections 1–5 are the "rebuild instead"
comparison; §6+ is the "attach to ECA" path being pursued.
