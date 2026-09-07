"""grog-memory — an MCP server (stdio) exposing grog's associative memory.

Backed by Python's built-in `sqlite3` (no native/system deps, no JDBC/babashka).
It is the `assoc_*` associative-memory store — a persistent key->value SQL store
that an ECA-driven agent loop can read/write.  (No EDN-file store emulation.)

Stores are **files the LLM can direct**: `assoc_open_store("<path>")` opens (or
creates) a SQLite store at an arbitrary filesystem path and returns a *handle*
(abs-path). Pass that handle to any other `assoc_*` tool to use that database
instead of the default. A small LRU keeps a few stores open concurrently to avoid
re-opening the same file on every call; `assoc_close_store(handle)` closes one.

Configuration is file-based: ~/.config/grog/memory.edn
  {:db "grog-memory.db" :max-open 8}
The db is the path to the default SQLite store; max-open caps concurrently open
stores. env vars (GROG_MEMORY_DB / GROG_MEMORY_MAX_OPEN) are intentionally NOT
read — file-config normalization, like the grog Clojure servers. The .edn is
parsed with a tiny EDN-subset regex (no Python EDN lib); values are strings and
integers of the form above.

Run:  python -m grog_memory.server   (MCP over stdio)
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import threading
from collections import OrderedDict
from datetime import datetime, timezone

from mcp.server.fastmcp import FastMCP


def _read_config_edn() -> dict:
    """Read ~/.config/grog/memory.edn with an EDN-subset regex parse.

    The file is expected to contain a map like:
        {:db "grog-memory.db" :max-open 8}
    Returns {"db": str, "max_open": int} overlaid on the built-in defaults.
    Missing/invalid file -> {} (never crashes the server at boot)."""
    cfg = {}
    path = os.path.expanduser("~/.config/grog/memory.edn")
    try:
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return cfg
    m = re.search(r":db\s+[\"']([^\"']+)[\"']", text)
    if m:
        cfg["db"] = m.group(1)
    m = re.search(r":max-open\s+(\d+)", text)
    if m:
        cfg["max_open"] = int(m.group(1))
    return cfg


_CFG = _read_config_edn()
DEFAULT_DB = _CFG.get("db") or "grog-memory.db"
MAX_OPEN = _CFG.get("max_open", 8)

mcp = FastMCP("grog-memory")

_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS assoc (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
"""

# abs-path -> sqlite3.Connection ; LRU, always read/written while holding `_lock`.
_stores: "OrderedDict[str, sqlite3.Connection]" = OrderedDict()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _abs_path(path: str) -> str:
    return os.path.abspath(os.path.expanduser(str(path or "")))


def _connection(path: str) -> sqlite3.Connection:
    """Return (and cache, LRU) a connection for `path`. Caller holds `_lock`."""
    key = _abs_path(path)
    conn = _stores.get(key)
    if conn is None:
        d = os.path.dirname(key)
        if d:
            os.makedirs(d, exist_ok=True)  # allow "putting" the store somewhere new
        conn = sqlite3.connect(key, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.executescript(SCHEMA)
        conn.commit()
        _stores[key] = conn
    _stores.move_to_end(key)  # mark most-recently-used
    while len(_stores) > MAX_OPEN:
        _, evicted = _stores.popitem(last=False)
        try:
            evicted.close()
        except Exception:
            pass
    return conn


def _store_db(handle: str) -> str:
    """The absolute DB path for a tool call: `handle` if given, else the default."""
    if handle and handle.strip():
        return _abs_path(handle)
    return _abs_path(DEFAULT_DB)


@mcp.tool()
def assoc_open_store(path: str) -> str:
    """Open an associative-memory store at `path` (absolute or relative; parent
    directories are created as needed). The file is created if it doesn't exist.
    Returns a handle — pass it as the `handle` argument to the other assoc_*
    tools to use this database (omit `handle` to use the default store)."""
    with _lock:
        _connection(path)
        return _abs_path(path)


@mcp.tool()
def assoc_close_store(handle: str) -> str:
    """Close and release the store opened by `assoc_open_store(handle)`. Returns
    'closed' or 'absent'. The default store is closed the same way if you pass
    its absolute path; it is re-opened lazily on the next use."""
    with _lock:
        key = _abs_path(handle)
        conn = _stores.pop(key, None)
        if conn is None:
            return "absent"
        try:
            conn.close()
        except Exception:
            pass
        return "closed"


@mcp.tool()
def assoc_store(key: str, value: str, handle: str = "") -> str:
    """Store a key->value entry in associative memory. Value is stored as a
    string; use JSON text for structured data. `handle` (optional) is a store
    returned by `assoc_open_store`; omit it to use the default store.
    Returns the key."""
    with _lock:
        conn = _connection(_store_db(handle))
        conn.execute(
            "INSERT INTO assoc(key,value,updated_at) VALUES(?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
            (key, value, _now()),
        )
        conn.commit()
    return key


@mcp.tool()
def assoc_get(key: str, handle: str = "") -> str:
    """Return the value for `key`, or '' if absent. `handle` is an optional store
    from `assoc_open_store`; omit it to use the default store."""
    with _lock:
        conn = _connection(_store_db(handle))
        row = conn.execute("SELECT value FROM assoc WHERE key=?", (key,)).fetchone()
        return row["value"] if row else ""


@mcp.tool()
def assoc_keys(handle: str = "") -> str:
    """List all keys as a JSON array. `handle` is an optional store from
    `assoc_open_store`; omit it to use the default store."""
    with _lock:
        conn = _connection(_store_db(handle))
        rows = conn.execute("SELECT key FROM assoc ORDER BY key").fetchall()
        return json.dumps([r["key"] for r in rows])


@mcp.tool()
def assoc_delete(key: str, handle: str = "") -> str:
    """Delete the entry for `key`. Returns 'deleted' or 'absent'. `handle` is an
    optional store from `assoc_open_store`; omit it to use the default store."""
    with _lock:
        conn = _connection(_store_db(handle))
        cur = conn.execute("DELETE FROM assoc WHERE key=?", (key,))
        conn.commit()
        return "deleted" if cur.rowcount else "absent"


@mcp.tool()
def assoc_search(substring: str, handle: str = "") -> str:
    """Return all (key, value) pairs whose key or value contains `substring`,
    as a JSON object. `handle` is an optional store from `assoc_open_store`;
    omit it to use the default store."""
    like = f"%{substring}%"
    with _lock:
        conn = _connection(_store_db(handle))
        rows = conn.execute(
            "SELECT key, value FROM assoc WHERE key LIKE ? OR value LIKE ? ORDER BY key",
            (like, like),
        ).fetchall()
        return json.dumps({r["key"]: r["value"] for r in rows})


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
