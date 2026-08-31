# grog-odoo

An **MCP server** (over stdio) exposing **Odoo ERP query tools** so an ECA-driven
agent loop (or any MCP client) can inspect Odoo data — customers, orders,
inventory, accounting (AR/AP), etc.

**Strictly read-only**: the model can read records and run read-only SQL, but
has no way to create/update/delete anything.

Supports **multiple Odoo instances** behind a **strict, pre-configured selection**
(no arbitrary endpoints), plus **read-only raw SQL** scoped to the selected instance.

## Language / runtime

**JVM Clojure** (deps.edn, add `org.postgresql/postgresql` for the SQL backend).
Talks to Odoo through its **native XML-RPC API** (`src/grog_odoo/xmlrpc.clj` — a
small self-contained XML-RPC client built on clj-http + stdlib `clojure.xml`).
Raw SQL talks straight to the instance's Postgres (or a custom Odoo method).

## Running

```bash
clojure -M:mcp -m grog-odoo.main
```

Speaks MCP over **stdio** (newline-delimited JSON-RPC). Wire into ECA:

```json
{ "mcpServers": {
    "grog-odoo": {
      "command": "clojure",
      "args": ["-M:mcp", "-m", "grog-odoo.main"],
      "env": { "GROG_ODOO_CONFIG": "/home/you/.config/grog/odoo-instances.edn" }
    }
} }
```

## Build & share a standalone jar

For a teammate who should **not** have to install the Clojure CLI (just a JRE 17+):

```bash
./build-uberjar.sh          # Linux/macOS  (build-uberjar.bat on Windows)
```

This produces a self-contained **`target/grog-odoo.jar`** (all deps bundled). The
build machine needs the Clojure CLI; the *runtime* needs only a JRE 17+.

Run it directly:

```bash
java -cp target/grog-odoo.jar clojure.main -m grog-odoo.main
```

And wire it into ECA with a plain JRE command (no bash wrapper; works on Windows too):

```json
{ "mcpServers": {
    "grog-odoo": {
      "command": "java",
      "args": ["-cp", "/path/to/grog-odoo.jar", "clojure.main", "-m", "grog-odoo.main"],
      "env": { "GROG_ODOO_CONFIG": "/path/to/odoo-instances.edn" }
    }
} }
```

## Configuration

### Multiple instances (recommended)

Point `GROG_ODOO_CONFIG` at an **EDN** file (legacy JSON also accepted):

```edn
{:instances [
  {:name "stage"
   :url "https://exclave.cmsaero.com"
   :db "odoo18_stage"
   :user "admin"
   :password "secret-or-api-key"
   :sql {:type "postgres"
         :host "127.0.0.1"
         :port 5432
         :db "odoo18_stage"
         :user "odoo"
         :password "..."}}
  {:name "prod"
   :url "https://odoo.example.com"
   :db "odoo18"
   :user "admin"
   :password "..."}
]}
```

- `name` is the only identifier the model can use. Selection is **locked to these
  names**: `odoo_use_instance` validates against the allowlist and no tool accepts
  a URL/host/db from the model.
- `sql` is **optional** per instance. Without it, `odoo_execute_sql` reports a
  clean error for that instance. Two backends are supported:
  - `"postgres"` — direct JDBC to the Odoo Postgres (default, shown above).
  - `"odoo-method"` — run SQL inside Odoo by calling a method you expose:
    ```edn
    :sql {:type "odoo-method" :model "custom.sql.runner" :method "run_sql"}
    ```

### Legacy single instance (backwards compatible)

When `GROG_ODOO_CONFIG` is unset, the server falls back to env vars:

| Env | Meaning |
|-----|---------|
| `GROG_ODOO_URL` | Odoo server base URL |
| `GROG_ODOO_DB` | Odoo database name |
| `GROG_ODOO_USER` | login / uid |
| `GROG_ODOO_PASSWORD` | password or API key |

Connection/auth is resolved lazily on the first tool call, per instance.

## Tools

| Tool | Purpose |
|------|---------|
| `odoo_list_instances()` | list pre-configured instances (name/url/db only — never credentials) |
| `odoo_use_instance(instance)` | select the instance for all subsequent calls (enum of configured names) |
| `odoo_authenticate()` | authenticate the currently selected instance |
| `odoo_search_read(model, domain, fields, limit, offset, order)` | search/read records |
| `odoo_get_fields(model, attributes)` | inspect a model's fields |
| `odoo_execute_sql(sql)` | **read-only** SQL on the selected instance's `sql` backend |

Write tools (`odoo_create`, `odoo_write`, `odoo_unlink`, `odoo_call_method`)
are intentionally **not exposed** — the model cannot modify the database.

## Security notes

- **Instance selection cannot be broken out of.** The model only ever passes an
  instance *name*; the server resolves it from the pre-configured allowlist and
  rejects unknown names. Endpoints/URLs/database hosts are never accepted from
  the model.
- **SQL is scoped to the selected instance** and never to a caller-supplied
  database. The `sql` connection comes entirely from that instance's config.
- **Read-only means read-only.** `odoo_execute_sql` accepts only `SELECT` / `WITH`
  / `SHOW` / `EXPLAIN` / `DESCRIBE` / `VALUES` / `TABLE` statements; anything
  that could change data (`INSERT`/`UPDATE`/`DELETE`/`DROP`/`CREATE`/…) is
  refused before any connection is made. There is no `read_only=false` escape
  hatch.
- Credentials are never included in tool outputs.

## Example usage via the LLM

- "Which instances can I use?" → `odoo_list_instances()`
- "Use the stage instance" → `odoo_use_instance("stage")`
- "Find the last 5 open sales orders" →
  `odoo_search_read("sale.order", [["state","=","sale"]], ["name","amount_total","partner_id"], 5)`
- "Which products are inactive?" →
  `odoo_execute_sql("SELECT name, active FROM product_product WHERE active = false")`

## Structure

- `src/grog_odoo/xmlrpc.clj` — self-contained XML-RPC client (encode + decode, fault-aware)
- `src/grog_odoo/main.clj` — MCP stdio server: multi-instance config, strict
  selection, Odoo tools, raw SQL (postgres / odoo-method backends)

## Status

Functional: MCP handshake, tool discovery, clean missing-config errors verified;
XML-RPC encode/decode verified against sample Odoo-shaped responses. Multi-instance
config parsing, strict instance allowlist, and read-only SQL guarding are covered
by the MCP server's own error paths.