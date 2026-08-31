# grog-imap — Status & Completion Plan

> Last updated: Phase 4 complete for Gmail (live MCP verification) + grog launch
> integration. This file is the source of truth for where the project stands and
> the ordered work needed to finish it. The single most important architectural
> decision (confirmed by the user) is stated in **Target architecture**.

---

## 1. Where we are (current, verified state)

**Phases 1 (protocol), 2 (public library), and 3 (MCP server) are done and green.**
Files: `README.md`, `deps.edn`, `build.clj`, `build-uberjar.{sh,bat}`,
`src/grog_imap/{protocol,core,oauth,main}.clj`, `docs/OAuth2.md`,
`scripts/{gmail-smoke,oauth-authorize}.clj`, and `test/` with `support.clj`,
`protocol_test.clj`, `core_test.clj`, `oauth_test.clj`, `main_test.clj`.

### What works (all verified)

- **Both namespaces compile clean** — `(require 'grog-imap.protocol
  'grog-imap.core)` succeeds with zero warnings/errors.
- **Full test suite green**: `clojure -M:test` → 50 tests / 223 assertions /
  0 failures. Coverage includes error paths (server NO response), read-only
  EXAMINE, UID variants, envelope/body FETCH normalization, multi-literal
  APPEND, OAuth refresh/XOAUTH2 wire, and the MCP tool surface (discovery,
  allowlist, read-only gating, no-secret echoes). Live: TLS + greeting +
  CAPABILITY verified against Dovecot / Gmail / MS365, and a full Gmail
  LOGIN + LIST/EXAMINE/SEARCH round trip (non-destructive). `core.clj` has
  **no MCP SDK dependency**.
- **Protocol layer (Phase 1)** — tokenizer/parser, command encoding, literals,
  SASL AUTHENTICATE, STARTTLS, single-read plumbing, all fixed and covered by
  unit tests plus a real-socket round trip against an in-process fake server.
- **Public library `grog-imap.core` (Phase 2)**:
  - Config/account model — `load-config` reads `GROG_IMAP_CONFIG` (path or
    inline JSON), keyword-normalized metadata, `:read-only` defaults true,
    strict `get-account` allowlist (unknown names throw). No passwords in
    metadata.
  - Session registry — `connect-account!` / `ensure-connected!` (reconnect on
    drop) / `connected?` (NOOP probe) / `disconnect-account!` /
    `disconnect-all!`. Credentials injected by the caller as a map or a
    `[account] -> credential` fn; never stored in config.
  - Mailbox ops — `list-mailboxes` (decorated), `select`/`examine` (mailbox
    state incl. unseen/uidnext/uidvalidity/read-write), `mailbox-status`,
    `close-mailbox`, `expunge`, create/rename/delete/subscribe/unsubscribe.
  - Message ops — `search`, `fetch`/`uid-fetch` (normalized message maps with
    uid/flags/size/internal-date/envelope/body/header), `set-flags`,
    `mark-read`, `mark-unread`, `delete-messages`, `move`/`copy` (+ UID),
    `append`.
- **Build** — `build.clj` + `build-uberjar.{sh,bat}` produce
  `target/grog-imap.jar` (verified; `target/` is gitignored by the root
  `.gitignore`).
- **Shared test support** — `test/grog_imap/support.clj` hosts the in-process
  fake IMAP server (multi-connection, handles multi-literal APPEND via a
  lookahead timeout) used by both test namespaces.
- **XOAUTH2 + OAuth2** — `protocol/authenticate-xoauth2` (SASL-IR initial
  response + failure-continuation handling), `grog-imap.oauth` (zero-dep
  java.net.http: authorize-url / interactive authorize! / refresh-token! /
  cached access-token!), and `core` wiring for `:sasl :xoauth2` via
  `{:access-token ...}` or `{:oauth {...} :refresh-token ...}`. TLS connect now
  verifies the peer hostname.
- **MCP server `grog-imap.main` (Phase 3)** — stdio server, a thin adapter
  over `core` with 11 tools (`imap_list_accounts`, `imap_use_account`,
  `imap_authenticate`, `imap_list_mailboxes`, `imap_search`, `imap_fetch`,
  `imap_set_flags`, `imap_delete`, `imap_move`, `imap_copy`, `imap_append`).
  Credentials resolved from `GROG_IMAP_PASSWORD_<NAME>` /
  `GROG_IMAP_REFRESH_<NAME>` env (never tool args/results). Mutation tools are
  rejected for read-only accounts (`:read-only` defaults true). Uberjar
  compiles `grog-imap.main`.

### What is still missing

- STARTTLS needs a live TLS-capable server to confirm the handshake (Phase 4).
- **Real-mailbox login tests still need a user step** for non-Gmail providers
  (secrets never transit the model context): a password for private Dovecot
  `LOGIN`, or an OAuth registration + one-time `grog-imap.oauth/authorize!` for
  a refresh token (required for MS-365, optional for Gmail). Gmail is verified
  live both via `core` (LOGIN round trip) and via the MCP server.

### Phase 4 status (complete for Gmail + grog integration)

- ✅ README refreshed for the 3-layer dual-use architecture.
- ✅ `scripts/mcp-smoke.py` — minimal stdio MCP client (env or file credential).
- ✅ Live MCP run against Gmail: initialize / tools-list / all read-only tools
  OK, including `imap_search` and `imap_fetch` (returning real message data);
  read-only gate rejected `imap_set_flags`.
- ✅ **Bug fixes surfaced by live Gmail**: mailbox-scoped tools now auto-`SELECT`
  `:mailbox` (default `INBOX`); `parenthesized` returns a `{:paren …}` marker so
  data-item/flag lists are sent **inline** (Gmail rejects them as literals);
  `safe-astring?` allows `:`/`,` so sequence-sets like `1:10` stay inline.
- ✅ **Credential-by-file**: the MCP server's `credential-provider` reads
  `~/.grog-imap-<name>` (or `GROG_IMAP_PASSWORD_FILE_<NAME>`) as a fallback —
  the secret stays on disk, never in grog.edn/env results.
- ✅ **Uberjar**: `java -cp target/grog-imap.jar clojure.main -m grog-imap.main`
  serves the same tools and authenticates against live Gmail.
- ✅ **grog launch wiring** (upstairs, `/d/gni/grog`): `grog-mcp-servers`
  registers `grog-imap` conditionally when IMAP account metadata is available —
  from the **email project** (`~/grog-projects/email/state/imap-accounts.edn`,
  legacy `.json` accepted) or (backward compat) `:imap` in `grog.edn`.
  `imap-env` writes account *metadata* to `~/.config/grog/imap-accounts.edn` and
  sets `GROG_IMAP_CONFIG`. Account data lives in the email project; grog.edn
  fallback is metadata only (no password). Registered ids: imaging, memory,
  odoo, office, search, **imap**.

### Remaining for other providers / deployment

- Dovecot `LOGIN` and MS-365 `XOAUTH2` are implemented but need a credential
  provisioned locally (password for Dovecot; OAuth `authorize!` for MS-365/Gmail
  if not using an app password). Gmail (app password) is verified live.
- STARTTLS needs a live TLS-capable server to confirm the handshake.
- To go live in grog: restart grog/ECA so it regenerates the config and spawns
  grog-imap; add the `imap_*` tool names to the ECA permanent-approval allowlist
  if you want them to run without prompting.

Bottom line: **the whole stack works end-to-end: `core` (no MCP SDK) is the
contract, `main` is a thin MCP adapter over it, tests are green (50 tests / 223
assertions), and Gmail was verified live through both `core` and the MCP wire.
Phase 4 remains: re-verify Gmail search/fetch via the MCP after the
auto-select fix, run the uberjar, and (optionally) test Dovecot/MS-365.**

---

## 2. Target architecture (user's confirmed requirement)

> **Requirement:** the IMAP *library* must be usable **both** from application
> code **and** from the MCP. That means: build a clean, public, dependency-light
> **library API with no MCP coupling**, and make the MCP server a **thin adapter**
> over that same library — the model's tools call the exact same functions app
> code calls. Never re-implement logic in the MCP layer.

Three layers, from lowest to highest:

```
src/grog_imap/
  protocol.clj   L0  low-level IMAP protocol (private-ish):
                   socket/TLS, framing, literal handling, response parsing,
                   raw commands (SELECT/FETCH/STORE/SEARCH/...). Returns the
                   normalized {:tagged ... :untagged [...]} shape.
  core.clj       L1  PUBLIC LIBRARY (what app code + MCP both use):
                   account/config model, connection & session registry,
                   high-level operations that return plain Clojure data
                   (lists of mailboxes, message maps, search results).
                   NO dependency on the MCP SDK. Imports only protocol.clj
                   + stdlib + maybe data.json.
  main.clj       L2  MCP stdio server: thin wrappers. Each tool:
                   parse args -> call core.clj fn -> JSON-encode result.
                   No business logic of its own.
```

**Rationale / rules:**

- `core.clj` is the contract. Application code does `(require '[grog-imap.core
  :as imap])` and calls ergonomic fns. The MCP tools are 1-line adapters that
  carry args over to `core` calls. If a tool needs new behavior, that behavior
  is added to `core.clj`, not `main.clj`.
- `protocol.clj` stays low-level; `core.clj` is where connection lifecycle,
  account resolution, and high-level ergonomics live.
- Library must not depend on the MCP SDK — that keeps it reusable in ordinary
  JVM/Clojure/babashka apps and keeps the MCP dependency quarantined to `main.clj`.
- Shape follows `grog-odoo`: multi-account `GROG_IMAP_CONFIG` JSON, strict
  pre-selected account *names* (never a host the model supplies), lazy auth.

### Credentials & secrets (dual-use — decided)

**The library never acquires secrets itself.** It separates *account metadata*
(`:name :host :port :tls :user`) from *credentials* (`:password` or OAuth
token), and it is always handed a living credential by its caller. There is no
"transmit a secret to the MCP from app code" step — the two paths are disjoint:

- **From application code** — the app resolves the secret itself (env, Vault,
  OS keychain) and connects directly, in-process:
  `(imap/connect (assoc account :password (app/get-secret ...)))`. The secret
  never leaves the app process and never touches the MCP or the model.
- **From the MCP** — `main.clj` holds account *metadata* plus a *credential
  provider* wired at process startup (per-account env var `GROG_IMAP_PASSWORD_<NAME>`,
  a secrets/json file the MCP process alone reads, or a pluggable
  secret-resolver fn hitting a keychain). It injects the resolved credential
  into the same `core` connect call.

**Hard rule (applies to both):** credentials are never (a) accepted as MCP tool
arguments, (b) returned in tool results, or (c) included in account-listing /
selection metadata (`imap_list_accounts` / `imap_use_account` return only
names). Tool arguments and outputs transit the model context, so secrets must
not travel those paths. `imap_use_account` resolves a *name* against the
allowlist; the password is filled in from the provider, never from the model.

### Mutation posture (decided)

IMAP is inherently stateful (delete/move/copy/append mutate the mailbox), unlike
grog-odoo's read-only surface. **`:read-only` defaults to `true` (safe by
default); mutations are opt-in** by explicitly setting `:read-only false` on an
account. When `:read-only` is true, the mutation-oriented MCP tools are not
exposed / are hidden for that account.

---

## 3. Completion plan (ordered)

### Phase 1 — Make the protocol layer build and be correct — ✅ DONE
1. **Fix the compile blocker** in `protocol.clj` (order the greeting read after
   `read-response*` is defined, or add a proper forward `declare`).
2. **Fix `read-response` double-read** (make it return the single parsed line).
3. **Resolve the `status` collision** — rename the tagged-status helper
   (e.g. `completion-status`) so `ok?`/`throw-unless-ok` work; keep the mailbox
   `STATUS` command named `status`.
4. **Fix `auth-command!`** (remove invalid `...`; make SASL LOGIN/PLAIN correct).
5. **Wire `starttls` socket upgrade** (currently throws "not yet wired").
6. **Implement a correct `idle`** (continuation-driven streaming) as a stretch
   item — or drop IDLE from scope for v1.
7. **Add unit-test scaffolding** (`test/` dir, `:test` alias) and tests for:
   tokenizer/parser (responses, literals, NIL, flags, status words, seq-sets),
   command encoding (`encode-arg`, `seq-set`, `parenthesized`, `flag-wire`),
   and a mock line-feed/read harness for `read-response`/completion.
8. **Spike an end-to-end smoke test** against a local server (e.g. a throwaway
   Dovecot/`greenmail` container) to prove connect → login → SELECT → FETCH →
   SEARCH round-trips.

**Exit criteria:** `(require 'grog-imap.protocol)` compiles clean; tests green;
round-trip smoke test passes.

### Phase 2 — Build the public library (`grog-imap.core`) — ✅ DONE
1. **Account/config model**: `GROG_IMAP_CONFIG` JSON loader of account
   *metadata* (`{:accounts [{:name :host :port :tls :user}]}` — **no passwords in
   metadata**), strict named selection, lazy per-account connection. Credentials
   are injected separately (see Credentials & secrets above); the loader never
   reads or stores secrets itself. Port the robust config-loading style from
   `grog-odoo.main`, minus its password-in-config assumption.
2. **Session registry**: an atom mapping account-name → live connection,
   connection reuse, reconnect-on-drop, disconnect on shutdown.
3. **High-level ops** (map to business-ish results, plain data in/out):
   - `list-mailboxes` (decorate LIST output with delimiters/flags/attrs)
   - `select`/state introspection (messages, recent, unseen, uidnext, uidvalidity)
   - `search` → human criteria API (FROM/TO/SUBJECT/NEW/UNSEEN/SINCE/BODY etc.)
   - `fetch` → normalized message maps (envelope, headers, body, flags, uid, size)
   - `set-flags` / `mark-read` / `mark-unread` / `delete` (STORE + EXPUNGE)
   - `move` / `copy` (plain + UID variants)
   - `append` (drafts/outbox-style inject)
4. **`build.clj` + `build-uberjar.{sh,bat}`** + `.gitignore` (exclude `.cpcache`,
   `.clj-kondo`, `.lsp`, `target`).
5. Library-level tests (config parsing, selection allowlist, mocked connection).

**Exit criteria:** app code can drive the whole inbox/manage surface through
`grog-imap.core` with no MCP SDK on the classpath.

### Phase 3 — MCP server on top (`grog-imap.main`) — ✅ DONE
1. Port the MCP boilerplate from `grog-odoo.main` (async tool spec helper, text
   content/result/error helpers, `kargs`, JSON encoding, stdio server,
   `-main` loop).
2. **Tools** (thin adapters over `core.clj`):
   - `imap_list_accounts` — configured account names (never credentials)
   - `imap_use_account(name)` — strict allowlisted selection
   - `imap_authenticate()` — lazy auth for the active account
   - `imap_list_mailboxes()`
   - `imap_search(query...)`
   - `imap_fetch(...)` — normalized message maps
   - `imap_set_flags(...)` / `imap_delete(...)` / `imap_move(...)` / `imap_copy(...)`
   - `imap_append(...)`
3. **Mutation gating (decided)**: `:read-only` defaults to `true`; when true the
   mutation-of-mailbox tools (`imap_set_flags`/`imap_delete`/`imap_move`/
   `imap_copy`/`imap_append`) are hidden for that account. Mutation is opt-in per
   account via explicit `:read-only false`.
4. MCP-level tests: discovery, arg normalization, clean missing-config errors,
   credential-provider injection, and confirmation that no tool accepts or echoes
   a secret.

**Exit criteria:** MCP server speaks stdio MCP, discoverable tools, each tool
verified against the same smoke-test server used in Phase 1.

### Phase 4 — Docs, verification, packaging
1. Update `README.md` to reflect the real 3-layer structure and the
   app-code-vs-MCP dual-use story.
2. Reconcile/replace the stale "Status" section of README (which currently
   claims smoke-tested bootstrap).
3. End-to-end verification via ECA: config a real account, `imap_search` / `imap_fetch`.
4. Build uberjar, verify `java -cp grog-imap.jar clojure.main -m grog-imap.main`.

**Exit criteria:** dual-use satisfied (same core fns callable from a REPL/app and
as MCP tools); whole surface tested; jar artifacts documented.

---

## 4. Decisions (resolved vs still open)

**Resolved:**

- **Mutation posture**: `:read-only` **defaults to `true`** (safe by default);
  mutations are **opt-in** per account via explicit `:read-only false`. Mutation
  tools are hidden when read-only.
- **Secrets separation**: the library is injected credentials by its caller and
  never acquires/stores secrets itself. App code hands secrets to the library
  in-process; the MCP pulls credentials from its own startup credential provider.
  Credentials are never MCP tool arguments or tool outputs — they never transit
  the model context.
- **Out of scope**: SMTP outbound send — belongs in a separate `grog-smtp`, not here.
- **IDLE**: deferred for v1. `protocol/idle` now throws a clear "not implemented"
  error rather than hanging; revisit streaming IDLE later if push is wanted.

## 5. Immediate next step

Phases 1–3 are complete: protocol layer, public `core` library, and the MCP
stdio server are all green (50 tests / 223 assertions). Gmail was verified live
(LOGIN + read-only ops). The next step is **Phase 4 — docs, verification, and
packaging**: refresh the README's stale Status/architecture sections, verify
against the private Dovecot and (optionally) MS-365 accounts, and confirm the
uberjar runs as `java -jar grog-imap.jar`.