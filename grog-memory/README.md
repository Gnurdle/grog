# grog-memory

> **⚠️ Legacy.** The default `grog-memory` is now the **Clojure/SQLite** server in
> `grog_mcp/src/grog_mcp/memory.clj` (served by the `grog-mcp` bundle:
> `clojure -M:mcp --server grog-memory`). It is byte-compatible (same 7 tools,
> same SQLite schema, same `memory.edn` `:db`), so existing stores work as-is and
> no Python is needed. This Python server is kept for reference only.

An **MCP server** (over stdio) exposing grog's **associative memory** — a
persistent key→value SQL store an ECA-driven agent loop can read/write.

Backed by Python's built-in **`sqlite3`** (no native/system deps, no JDBC/babashka).
There is **no EDN-file store** here — memory is a plain SQLite database.

## Setup & running

```bash
python3 -m venv .venv
.venv/bin/pip install "mcp>=1.9,<2.0"      # or: pip install -e .
PYTHONPATH=src .venv/bin/python -m grog_memory.server
```

Speaks MCP over **stdio**. Wire into ECA:

```json
{ "mcpServers": {
    "grog-memory": {
      "command": "python",
      "args": ["-m", "grog_memory.server"]
    }
} }
```

The default store's path comes from the **file config**, not env. grog writes that
file to point at the **active project's** store, giving per-project isolation.

## Configuration (file)

`~/.config/grog/memory.edn`:

```clojure
{:db "/abs/path/to/project/state/mem.db"   ;; the default SQLite store
 :max-open 8}                              ;; max concurrently-open stores (LRU)
```

(Env vars are intentionally **not** read — file-config normalization, like the
other grog servers.)

## Tools

Every `assoc_*` tool (except `assoc_open_store`/`assoc_close_store`) takes an
optional `handle`. Omit it to use the default store (`:db`); pass a
store's handle to target a specific database.

| Tool | Purpose |
|------|---------|
| `assoc_open_store(path)` | open (or create) an associative store at `path`; returns a handle (absolute path). Parent dirs are created automatically — the LLM can direct where the database lives |
| `assoc_close_store(handle)` | close + release a store opened by `assoc_open_store` |
| `assoc_store(key, value[, handle])` | upsert a key→value entry (JSON text for structured) |
| `assoc_get(key[, handle])` | read a value |
| `assoc_keys([handle])` | list all keys |
| `assoc_delete(key[, handle])` | delete an entry |
| `assoc_search(substring[, handle])` | search keys/values |

### Using multiple stores

```text
assoc_open_store("~/memory/notes.db")   →  "/home/you/memory/notes.db"   (handle)
assoc_store("project", "grog", "/home/you/memory/notes.db")
assoc_get("project", "/home/you/memory/notes.db")   →  "grog"
assoc_close_store("/home/you/memory/notes.db")
```

- The `handle` is the **absolute path**; opening the same path reuses the cached
  connection (no thrashing), and a small LRU (`:max-open`, default 8) keeps a few
  stores open concurrently, evicting the least-recently-used one.
- Closing a store closes its connection; it re-opens lazily if used again.
- Without a `handle`, everything targets the default store (`:db`).

## Status

Functional against SQLite out of the box, including multi-store open/close.
