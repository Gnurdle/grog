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
   :password-secret "ODOO_STAGE_PASSWORD"
   :allow-write false}
  {:name "prod"
   :url "https://odoo.example.com"
   :db "odoo18"
   :user "admin"
   :password-secret "ODOO_PROD_PASSWORD"
   :allow-write false}
]}
```

- `name` is the only identifier the model can use. Selection is **locked to these
  names**: the server resolves the name from the allowlist and no tool accepts a
  URL/host/db from the model.
- **With more than one instance, every call must name one** via the `instance`
  argument. There is no "first configured" fallback — a bare call is REFUSED, so
  nothing can silently hit the wrong database. With exactly one instance the
  argument is optional, since it is unambiguous.
- `:password-secret` names an **account in grog's secret store** — the password
  itself is never in this file. Store each one once:
  `/secret set ODOO_STAGE_PASSWORD <value>` (OS keyring; grog's `secrets.edn` is
  the headless fallback). This is the **only** credential source: there is no
  `:password` field, no `${ENV}` credential and no credential file, because
  several instances can be configured and each carries the NAME of its account.
- `:allow-write` (default **false**) is the per-instance write switch. False
  refuses any statement that could modify data *before Odoo is called*. True
  passes mutating SQL through to Select-O-Matic, which still requires the Odoo
  **superuser** account and an explicit confirmation.
- There is **no `:sql` block and no database connection**. SQL goes through the
  `select_o_matic` addon over the Odoo API, using the instance's own login — so
  no database host/port/credentials are involved at all.

### Single instance

`~/.config/grog/odoo.edn` can carry one instance directly:

```edn
{:url "https://odoo.example.com"
 :db "odoo18"
 :user "admin"
 :password-secret "ODOO_DEFAULT_PASSWORD"}
```

Otherwise the default instances file `~/.config/grog/odoo-instances.edn` is used
(`GROG_ODOO_CONFIG` overrides that path). Connection/auth is resolved lazily on
the first tool call, per instance — no credential is read at registration time.

Note: the `GROG_ODOO_URL` / `GROG_ODOO_DB` / `GROG_ODOO_USER` env vars are **not
read by this server**; put the connection details in one of the two files above.
There is deliberately **no `GROG_ODOO_PASSWORD`** — a password in the process env
would be written verbatim into the generated ECA config.

## Tools

| Tool | Purpose |
|------|---------|
| `odoo_list_instances()` | list pre-configured instances (name/url/db/allow-write only — never credentials) |
| `odoo_authenticate(instance?)` | authenticate an instance |
| `odoo_search_read(instance?, model, domain, fields, limit, offset, order)` | search/read records |
| `odoo_get_fields(instance?, model, attributes)` | inspect a model's fields |
| `odoo_execute_sql(instance?, sql, row-limit?)` | SQL via the Select-O-Matic addon |

`instance` is optional **only** when exactly one instance is configured. With
more than one, it is required on every call: a bare `odoo …` is refused.

Record write tools (`odoo_create`, `odoo_write`, `odoo_unlink`,
`odoo_call_method`) are intentionally **not exposed** — the model cannot modify
records through the API. SQL writes are possible only through
`odoo_execute_sql`, only on an instance marked `:allow-write true`, and there
Select-O-Matic applies its own guards (superuser only, explicit confirmation,
single statement, row ceiling, statement timeout).

## Security notes

- **Instance selection cannot be broken out of.** The model only ever passes an
  instance *name*; the server resolves it from the pre-configured allowlist and
  rejects unknown names. Endpoints/URLs/database hosts are never accepted from
  the model.
- **No direct database access.** There is no JDBC connection and no database
  credentials anywhere: SQL runs through the `select_o_matic` addon, over the
  Odoo API, as the configured Odoo user. The database host/port never leave the
  Odoo server.
- **Read-only unless an instance opts in.** `odoo_execute_sql` accepts
  `SELECT` / `WITH` / `SHOW` / `EXPLAIN` / `DESCRIBE` / `VALUES` / `TABLE`
  statements always; anything that could change data is refused before Odoo is
  called **unless** that instance is marked `:allow-write true`. The switch is
  per instance — `prod` can stay read-only while `stage` does not.
- **Requires the `select_o_matic` addon** on the Odoo instance, and the
  configured Odoo user must be in its `group_select_o_matic` group. Writes
  additionally need the superuser account (Select-O-Matic's own rule).
- Credentials are never included in tool outputs.

## Example usage via the LLM

- "Which instances can I use?" → `odoo_list_instances()`
- "Find the last 5 open sales orders on stage" →
  `odoo_search_read(instance="stage", model="sale.order", domain=[["state","=","sale"]], fields=["name","amount_total","partner_id"], limit=5)`
- "Which products are inactive? (prod)" →
  `odoo_execute_sql(instance="prod", sql="SELECT name, active FROM product_product WHERE active = false")`

Note the `instance=` in every call: with more than one instance configured there
is no default, by design.

## Structure

- `src/grog_odoo/xmlrpc.clj` — self-contained XML-RPC client (encode + decode, fault-aware)
- `src/grog_odoo/main.clj` — MCP stdio server: multi-instance config, per-call
  instance selection, Odoo record tools, SQL via the Select-O-Matic addon

## Status

Functional: MCP handshake, tool discovery, clean missing-config errors verified;
XML-RPC encode/decode verified against sample Odoo-shaped responses. Multi-instance
config parsing, the per-call instance rule, the per-instance write switch, and the
`select_o_matic` call path are covered by the MCP server's own error paths.