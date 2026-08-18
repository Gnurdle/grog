# grog-imap — Status & Completion Plan

> Last updated: Phase 1 complete. This file is the source of truth for where the
> project stands and the ordered work needed to finish it. The single most
> important architectural decision (confirmed by the user) is stated in
> **Target architecture**.

---

## 1. Where we are (current, verified state)

**Phase 1 (protocol-layer correctness) is done and green.** Files present:
`README.md`, `deps.edn`, `src/grog_imap/protocol.clj`, and `test/` with
`protocol_test.clj`.

### What works (all verified)

- **`protocol.clj` compiles clean** — `(require 'grog-imap.protocol)` succeeds with
  **zero warnings, zero errors**.
- **Full test suite green**: `clojure -M:test` → 17 tests / 90 assertions / 0 failures.
  Covers tokenizer, parser, command encoding, literal handling, read/completion
  plumbing, SASL AUTHENTICATE, and an **end-to-end round trip over real sockets**
  (an in-process fake IMAP server): connect → greeting → login → select → list →
  fetch → search → logout.
- **All Phase-1 blockers fixed**:
  1. Compile blocker — forward `(declare read-response* b64 utf8)`, and `connect`
     no longer self-references its own `let` binding.
  2. `read-response` single-read (was double-reading two lines).
  3. `status` collision resolved — helper renamed to `completion-status`; the
     mailbox `STATUS` command stays `status`; `ok?`/`throw-unless-ok` work.
  4. `auth-command!` repaired — SASL LOGIN/PLAIN send base64 after each `+`
     continuation; `authenticate-login`/`authenticate-plain` wire-verified.
  5. `starttls` implemented — wraps the existing socket with SSLSocketFactory
     (hostname verification on) instead of throwing "not yet wired".
  6. `idle` now **fails loudly** (deferred for v1) instead of deadlocking.
  7. `tokenize`/`parse-line` rewritten — the original had `recur` arity errors and
     ran quoted strings through `finish-atom` (turning them into symbols).

  Additionally fixed during the work: `parse-line`'s `(rest rest)` shadow bug,
  the CR/LF handling in `read-crlf-line` (lines after the first were read empty),
  `finish-atom` producing uppercase `:OK` (broke `ok?`), `parenthesized` defined
  too late for `status` and double-uppercasing flag strings, `uid-search` emitting
  duplicated criteria, `safe-astring?` allowing `"` in unquoted atoms, and dead
  `write-line!`/`delete-mbox!` removed.

### What is still missing (unchanged)

- **No `main.clj`** — the MCP stdio server does not exist yet.
- **No high-level library layer** (`core.clj` — see architecture below).
- No uberjar/build script (`build.clj`, `build-uberjar.{sh,bat}`).
- STARTTLS code is implemented but only exercised for the pre-TLS command; needs
  a live TLS-capable server to confirm the handshake (Phase 4 verification).

Bottom line: **the protocol layer now builds, is correct per its tests, and
proves the full connect→login→manage round trip in-process. Everything above it
(main.clj, core.clj, packaging) remains per the plan.**

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

### Phase 2 — Build the public library (`grog-imap.core`)
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

### Phase 3 — MCP server on top (`grog-imap.main`)
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

Phase 1 is complete: protocol layer compiles clean, tests green, and the in-process
fake-server round trip proves connect → login → select → fetch → search → logout.
The next step is **Phase 2 — build the public `grog-imap.core` library layer**
(account/config model, session registry, and the high-level operations), which
both app code and the eventual MCP server (`main.clj`, Phase 3) will call.