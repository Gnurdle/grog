# grog MCP servers — full surface area

The reference for every MCP server grog ships and every tool it exposes. Each
server is a self-contained **stdio** MCP server (one JSON-RPC-2.0-over-stdio
process). The runtime spawns them via the generated ECA config; the consolidation
build (`grog_mcp/`) registers the same tools on a single JVM — see
"Consolidated bundle" below.

Notation: the model sees a server's tools prefixed by its id, e.g.
`grog-odoo__odoo_search_read`.

## Per-server catalog

### grog-babashka — sandboxed Clojure execution
*Always enabled.* Runs short Babashka/Clojure transforms (stdin → stdout) in an
isolated, host-neutral sandbox. Python is off-limits.

| Tool | Description |
|---|---|
| `run_babashka` | Execute a Clojure/Babashka script in an isolated sandbox |

### grog-search — web search (Brave)
Public web search, current & source-linked. Needs a Brave API key in the OS keyring.

| Tool | Description |
|---|---|
| `brave_web_search` | Search the public web via Brave Search |

### grog-fetch — URL reader
Fetch a URL and return its readable text (articles, changelogs, docs).

| Tool | Description |
|---|---|
| `fetch_url` | Fetch a URL → readable text |

### grog-rss — feed reader
Fetch RSS/Atom feeds for monitoring news, blogs, release notes, calendar exports.

| Tool | Description |
|---|---|
| `fetch_feed` | Fetch an RSS/Atom feed → recent entries (JSON) |

### grog-project-search — current-project recall
Keyword search over the active project's `notes/` text files and `dialog/thread.edn`.

| Tool | Description |
|---|---|
| `project_search` | Ranked keyword search over the active project's notes + dialog |

### grog-big — the big model as a tool
*Local orchestrator, remote specialist.* Exposes a strong remote model as a callable
tool so a small local agent can delegate hard problems. Config via `GROG_BIG_*` env.

| Tool | Description |
|---|---|
| `big_model_ask` | Ask a large remote model for a self-contained response |

### grog-imaging — PDF / OCR / computer vision
PDF text + raster OCR + line/geometry extraction + image ops. Needs Tesseract.

| Tool | Description |
|---|---|
| `read_pdf_document` | Extract text from a PDF |
| `ocr_pdf_document` | OCR raster PDF pages |
| `analyze_pdf_line_drawings` | BoofCV line/geometry extraction from a PDF |
| `read_office_document` | Extract text from .docx/.xlsx/.xls |
| `read_png_image` | Decode PNG/JPG → metadata/color stats |
| `ocr_image` | Tesseract OCR on an image |
| `analyze_image_shapes` | BoofCV geometry: lines/rects/blobs/arrows |
| `crop_workspace_image` | Crop an image/PDF → PNG |
| `write_workspace_png` | Write PNG bytes to disk |
| `draw_overlay_png` | Draw overlay geometry → annotated PNG |

### grog-office — structured document editing
Manipulate .docx with a block model (paragraphs/tables), find/replace, render.
Optional LibreOffice for rendering.

| Tool | Description |
|---|---|
| `import_document` | Open a .docx for structured manipulation → handle |
| `list_handles` | List open document handles |
| `list_blocks` | Enumerate body as a block model |
| `get_text` | Logical text of a block/cell |
| `find_text` | Locate string occurrences |
| `replace_text` | Replace text in place, preserving run formatting |
| `delete_table_row` | Remove a visible table row |
| `render` | Render to PDF/PNG (needs LibreOffice) |
| `save` | Flush edits to disk |
| `close_document` | Free a document handle |

### grog-memory — associative key/value store
Persistent SQLite key/value memory. **Python/sqlite3 + FastMCP.** Per-project
isolation by pointing `GROG_MEMORY_DB` at a per-project file. (A Clojure/SQLite
re-implementation exists for the consolidated bundle.)

| Tool | Description |
|---|---|
| `assoc_open_store` | Open/create a store → handle |
| `assoc_close_store` | Close/release a store |
| `assoc_store` | Upsert key → value |
| `assoc_get` | Fetch a value |
| `assoc_keys` | List all keys |
| `assoc_delete` | Delete a key |
| `assoc_search` | Key/value substring search |

### grog-odoo — enterprise records (read-only)
Query Odoo records and metadata. **Strictly read-only**; SQL limited to
`SELECT/WITH/SHOW/EXPLAIN/DESCRIBE/VALUES/TABLE`. Credentials in your instance config.

| Tool | Description |
|---|---|
| `odoo_list_instances` | List configured instances |
| `odoo_use_instance` | Select the active instance |
| `odoo_authenticate` | Authenticate → uid |
| `odoo_search_read` | Search/read records |
| `odoo_get_fields` | Field metadata for a model |
| `odoo_execute_sql` | Read-only SQL |

### grog-imap — email (IMAP)
Email account setup, read/search, flag, move/copy/append, with OAuth.

| Tool | Description |
|---|---|
| `imap_list_accounts` | List configured IMAP accounts |
| `imap_use_account` | Select an account |
| `imap_authenticate` | Authenticate |
| `imap_list_mailboxes` | List mailboxes/folders |
| `imap_count` | Message count |
| `imap_search` | Search messages |
| `imap_fetch` | Fetch a message |
| `imap_append` | Append a message |
| `imap_copy` | Copy a message |
| `imap_move` | Move a message |
| `imap_move_sender` | Move by sender |
| `imap_delete` | Delete a message |
| `imap_set_flags` | Set message flags |

### grog-gitlab — GitLab REST API (read-only)
Co-opted from `bbutils/gitlab.clj` (babashka). Wraps the GitLab v4 API; read-only.
Config in `~/.config/grog/gitlab.edn` (`:url`, `:token-file`).

| Tool | Description |
|---|---|
| `gitlab_get_project` | Get a project by id or name |
| `gitlab_list_group_projects` | List projects in a group (paginated) |
| `gitlab_list_subgroups` | List direct subgroups of a group |
| `gitlab_list_branches` | List project branches |
| `gitlab_list_tags` | List project tags |
| `gitlab_list_commits` | List commits at a ref |
| `gitlab_get_file` | Raw contents of a file at a ref |
| `gitlab_file_exists` | Check a file exists (lightweight HEAD) |
| `gitlab_list_pipelines` | List pipelines (optionally by ref) |
| `gitlab_last_successful_pipeline` | Last `success` pipeline for a ref |
| `gitlab_list_jobs` | List jobs for a pipeline |
| `gitlab_last_successful_job` | Last successful job for a pipeline |
| `gitlab_compare_refs` | Compare two refs (commits in `to` not `from`) |
| `gitlab_list_merge_requests` | List MRs (state default `opened`) |
| `gitlab_list_issues` | List issues (state default `opened`) |

## Consolidated bundle (`grog_mcp/`)

`grog_mcp/` is a **build/assembly** that registers all of the above tools on a
**single JVM** (one `McpServer`, one classpath, one process) — the runtime
consolidation. The individual `grog-*` source trees stay canonical and standalone;
`grog_mcp/` carries vendored copies of their `src/` under `vendor-src/` (so edits
to a server's source should refresh `vendor-src/`).

- **Uberjar:** `grog_mcp/target/grog-mcp.jar` (build with `clojure -T:build uber`).
- **Run all:** `java -cp target/grog-mcp.jar clojure.main -m grog_mcp.main`
- **Run one (isolation):** `... -m grog_mcp.main --server grog-fetch`

The bundle delivers ~52 tools including the SQLite `assoc_*` memory store.

## Summary

| Server | Lang | Count | Tools (total) |
|---|---|---|---|
| grog-babashka | Clojure | 1 | `run_babashka` |
| grog-search | Clojure | 2 | `brave_web_search` |
| grog-fetch | Clojure | 1 | `fetch_url` |
| grog-rss | Clojure | 1 | `fetch_feed` |
| grog-project-search | Clojure | 1 | `project_search` |
| grog-big | Clojure | 1 | `big_model_ask` |
| grog-imaging | Clojure | 10 | pdf/ocr/vision |
| grog-office | Clojure | 10 | docx editing |
| grog-memory | Python | 7 | assoc kv-store |
| grog-odoo | Clojure | 6 | read-only Odoo |
| grog-imap | Clojure | 13 | email |
| grog-gitlab | Clojure | 15 | GitLab REST (read-only) |
| **TOTAL** | | **~67** | |

*See also:* [`eca-users-guide.md`](eca-users-guide.md) (enabling these in ECA on a
teammate's box), [`README.md`](README.md) (config mechanics), `state.md` (implementation).