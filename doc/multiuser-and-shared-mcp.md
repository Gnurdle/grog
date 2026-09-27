# V2 contract — multi-user, headless, and ONE shared MCP instance

> Status: **design / contract** (nothing implemented). Companion to
> `doc/server-and-client.md`, which covers the client/server split. This doc
> covers the two requirements that doc under-specifies: **multi-user identity**,
> and **a single MCP instance serving many sessions with hard separation**.

## 0. Requirements (as stated)

1. **Multi-user + headless server.** The Swing GUI is *kept* — not as the
   product, but as the instrument that shows what data must cross the boundary.
2. **One MCP instance, many sessions.** All MCP servers run as a *single*
   instance that multiple sessions talk to concurrently.
   *They do not have to be threaded — but they must keep sessions separate.*

Requirement 2 is explicitly **isolation over concurrency**: serialization is an
acceptable implementation, session bleed is not.

---

## 1. What already exists (verified, not aspirational)

| Need | State today | Evidence |
|---|---|---|
| One JVM, all tools | ✅ built | `grog_mcp.main/mcp-server` with no `--server` → `(keys servers)` = 13 servers, ~52 tools |
| Multi-session transport | ✅ in dependency | `mcp-0.8.0.jar`: `HttpServletSseServerTransportProvider`, `HttpServletMcpSessionTransport$ClientSession` |
| Bundle free of global active-project | ✅ | `grep "active-project" grog_mcp/src/` → **no hits** |
| Shared transport actually used | ❌ | repo only uses `StdioServerTransportProvider`; `grep StreamableHttp\|sse` → nothing |
| Per-session scoping of tool state | ❌ | see §4 |
| Any notion of "user" | ❌ | `grep "current-user\|user-id\|:principal" src/` → **nothing** |

So the single-instance goal is **not** a rebuild. The process and the transport
already exist; the missing piece is *context* — today a server process learns
which project it serves from its **environment**, fixed at spawn time.

---

## 2. Identity & authentication

**DECIDED (2026-09-25): v1 has no in-server authn and no TLS.** The server
sits behind an *external guard* (whatever already fronts it — proxy, VPN,
localhost-only binding); anything that reaches `grog-server` is **assumed
authenticated**. No bearer tokens are issued or checked in-process, no TLS
material anywhere in Clojure. When a deployment without an external guard
appears, the tier table below is the plan it falls back to — the seam is the
request boundary, so adding it later is additive, not a rewrite.

**Deferred plan — two tiers, matched to the transport (not built in v1):**

| Transport | Client | Authn | Notes |
|---|---|---|---|
| stdio / in-process | local GUI, `grog.client.local` | **implicit `local` user** | preserves today's single-user behaviour exactly (doc §9 compat requirement) |
| HTTP (SSE / Streamable) | remote GUI, `grog-server` clients | **external guard** → `user`; bearer token is the fallback design | no in-server TLS (DECIDED); token gate only if/when the guard disappears |

- **Identity still exists even without authn**: a **user** is a server-side
  record `{:id "slug" :display "Name"}` with the token list added when auth
  lands. Registry lives under config-home (`<config-home>/users.edn`),
  provisioned out-of-band in v1.
- `user.id` is a stable slug used in **paths** and in **event payloads**.
- No OIDC/SSO in v1 — bolt-on later (DECIDED, together with the token gate).
- **The client never holds provider keys** (doc §3/§5 already says this). Keys
  stay server-side, scoped per user (§3.3).

---

## 3. State namespace — where a user's world lives

### 3.1 Projects

**Target layout (deferred): directory partition.**

```
~/grog-projects/<user>/<project>/        ← instead of ~/grog-projects/<project>/
```

Why partitioning is the right *end state* rather than an `owner` field alone:

- `projects/workspace-folders` roots ECA's `workspaceFolders` at the project
  dir. With the user segment in that path, **ECA's own per-connection workspace
  containment enforces isolation for free** — a user's agent cannot be pointed
  at another user's tree even if grog's own checks have a defect.
- Filesystem permissions become the backstop (`chmod`/`chown` per user dir),
  which `server-and-client.md` §7 demands.

**DECISION D1 — migration is deferred: projects cannot be moved yet.**
Existing projects stay exactly where they are. The future move's cost is
measured now so it is not a surprise later: **0/27** manifests hold an absolute
path, **0** scripts hardcode `grog-projects`, `memory.edn` /
`project-search.edn` / `sessions/*.json` regenerate on spawn, and **8** memory
entries hold absolute paths (`dailynews.how-to-run` + 7 project keys) needing a
one-shot rewrite. Tracked as its own deferred task — **not** a contract blocker.

**Interim isolation — logical, enforced by the server, not by path layout:**

1. `project.edn` gains `:owner "<user-id>"`; existing projects get
   `:owner "default"` **in place, with no move**. The field is needed either
   way — without it the server cannot answer "whose project is this?".
2. Authorization is enforced **at the path resolvers**, because every path a
   tool or ECA can ever see originates there:

   `list-project-names` · `project-exists?` · `project-dir` · `project-root` ·
   `ensure-project-dir!` · `workspace-folders` · `state-dir` · `memory-db-path`

   Each resolves against the *current user context*; a project the caller does
   not own resolves as **absent**, never "denied" — probing must reveal nothing.
3. **Consequence — project names are globally unique while the layout is flat.**
   All projects share one directory, so two users cannot hold the same name and
   creating a colliding name must fail. Partitioning lifts this later.

**Honest risk:** pre-migration, isolation is *logical*. A defect in the
authorization layer is a path leak, where post-migration the directory layout
would catch it. §8 asserts *behaviour*, and the deferred migration removes this
exposure class.

### 3.2 Per-project state follows the partition automatically

`state/` (memory db, chat-id, `memory.edn`, `project-search.edn`, `eca-rules.md`),
`dialog/`, `jobs/` all hang off `project-dir`, so partitioning projects moves
**all** of it — no per-file changes.

### 3.3 Secrets

- Today: OS keyring service **`"grog"`** + file fallback
  `<config-home>/secrets.edn`, `harden-file!` sets owner-only perms.
- Proposal: keyring **account** becomes `<user>/<account>` (service stays
  `grog`), and the file fallback becomes `<config-home>/users/<user>/secrets.edn`.
- Server-side only. A client asks the server to *use* a secret, never to *send* it.

### 3.4 Cross-cutting config

| File | Today | Multi-user |
|---|---|---|
| `<config-home>/grog.edn` | one global | server-global defaults + **optional per-user override** |
| `<config-home>/global-mem.db` | one global assoc store | **needs a decision (§7.2)** — per-user `global`, or truly shared |
| `<config-home>/sessions/*.json` | one per project | one per **(user, project)** |
| `.active-project` marker | global mutable | **must go server-side** — resolved per session, never written globally |

### 3.5 Project ownership + concurrency — **TABLED / UNRESOLVED**

> **Status: open.** Build no lock machinery and delete none either. Everything
> else in this doc proceeds with locks *assumed unresolved*.

**Settled:** the **server** owns each project; clients never do. That is
architecture, not concurrency policy — it stands.

**Unsettled: how two clients on ONE project coordinate.**

- Two clients in the same project must be separated either by **turns**
  (one active turn at a time) **or** by some lock.
- Before picking a mechanism: **what are we locking, and why?** The candidates
  are the project's backing stores — `dialog/`, `state/mem.db`, `state/chat-id`,
  per-project generated configs. Those are the resources a lock would protect.
- **The deeper doubt, worth settling first:** two LLMs with *different contexts*
  concurrently writing two backing stores may be a poor design regardless. If
  concurrent same-project work is undesirable, the answer is "don't allow it",
  not "lock it".

**What tableing means operationally:**

| | |
|---|---|
| `grog.session` (lock ns) | **left exactly as-is** — no rename, no user dimension, no deletion |
| `.session.lock` | still present, still honoured |
| `claim!` / `holder` / `release!` | untouched (called only from `ui.clj`) |
| client protocol | no lock calls added, nothing removed |
| Phase 1 | does **no** lock work — and therefore cannot claim the name `grog.session` |

**Consequence for naming:** Phase 1's headless core was going to take the name
`grog.session`, which needed the lock ns renamed first. With that tabled, the
core takes a different name — **`grog.chat`** — which is the more honest name
anyway: bottom-up the core manages *chats* (`chatId`-scoped; one ECA per
project, many chats per ECA), while the *server* owns projects.

**True regardless of locking:** project names stay globally unique while the
layout is flat — a path constraint from §3.1 (no migration), unrelated to locks.

---

## 4. ONE MCP instance, many sessions — the actual work

### 4.1 The four blockers (all verified in V2 today)

| # | Blocker | Evidence | Consequence if ignored |
|---|---|---|---|
| **B1** | Per-project config read from **process env** | `grog_mcp/memory.clj:42` `GROG_MEMORY_CONFIG`; `grog-project-search/main.clj:50` `GROG_PROJECT_SEARCH_CONFIG`; both injected by `eca_config/memory-env` at spawn | a shared process has *one* env ⇒ every session reads project A's memory/index |
| **B2** | Mutable tool state **not session-keyed** | `grog_office/core.clj:28` `!handles` keyed `doc.N` with **no owner**; `memory.clj:78` `stores` = one global `LinkedHashMap`, global LRU `max-open` (default 8) | session A can `list-handles`/`close-handle!` session B's documents; A's traffic **evicts B's open DB connections** |
| **B3** | **No session identity** on a tool call | schemas carry `path`/`key`/`handle`/`name` only | server cannot attribute a call, so it cannot scope anything |
| **B4** | Transport is **stdio ⇒ one client per process** | `StdioServerTransportProvider` only | second session must spawn a second instance |

### 4.2 Design: per-request context, not per-process env

**Carry the session on the transport, bind it around the handler, serialize.**

```
ECA #1 ─┐                                    ┌─ tool fn
ECA #2 ─┼─ HTTP/SSE ──► grog-mcp (ONE JVM) ──┤   reads *session-ctx*
ECA #3 ─┘   X-Grog-Session / Authorization    └─ resolves config, keys, paths
                    │
                    └─► dispatcher: one call at a time (serialization is OK)
```

1. **B4** → switch the bundle to `HttpServletSseServerTransportProvider`.
   Each ECA points at it by `url` (doc §6 fact3 already established ECA
   supports remote MCP over `url`). Stdio stays available for the local path.
2. **B3** → the server extracts the session identity **from the request**
   (header), not from the tool arguments.
   *Why headers and not a `session` arg on every tool:* that would touch all
   ~52 tool schemas, every client, and every allowlist. Headers are invisible
   to the MCP contract and leave `tools/list` unchanged.
3. Context is bound as a **dynamic var** for the duration of one tool call:
   `(binding [*session-ctx* ctx] (f args))`.
4. **B1** → config readers (`config-file`, `default-db`, project-search's
   config path, …) consult `*session-ctx*` **first**, then fall back to env.
   Absent context ⇒ today's behaviour ⇒ local stdio path keeps working
   unchanged (doc §9 compat requirement).
5. **B2** → key the state:
   - `!handles` → `{[session-id handle] …}`; `list-handles` filters by session.
   - `stores` LRU → key by `[session-id abs-path]`, and make `max-open`
     **per session** (or raise it and scope eviction per session) so one
     session cannot evict another's connections.
   - `token-cache` / IMAP `sessions` → already account-keyed; add the session
     dimension only where an account can be reached by more than one user.
6. **Isolation, not concurrency** (per requirement 2): a single dispatcher lock
   around tool execution is a legitimate v1. Handlers then need no locking, and
   `binding` can never leak across sessions. Throughput can be revisited later
   without changing the contract.

### 4.3 What this does NOT require

- No change to tool **names**, schemas, or descriptions (so existing
  allowlists keep working — `eca_config` explicitly protects the
  `grog-memory__assoc_*` names).
- No thread-safety audit of all handlers (serialization defers it).
- No rewrite of the tools' business logic — only the *context lookup*.

---

## 5. What Phase 1 must carry from day one

The event/session contract is cheap to extend now and expensive to retrofit:

```clojure
{:session "uuid"      ; grog session
 :user    "slug"      ; NEW — absent ⇒ implicit "local"
 :chatId  "uuid"
 :type    …}          ; unchanged from server-and-client.md §4
```

- `grog.session` core must own a **`{:user :project :session}` context** and
  expose it, not a bare project name.
- The GUI (our instrument) must be able to *display* that context, otherwise we
  can't observe it — which is the whole point of keeping the client.

---

## 6. Phase impact

| Phase | Change from `server-and-client.md` |
|---|---|
| 1 — headless session core | **+** carry `:user` in events/context; **no lock work** — the server owns projects, so `grog.session` is *not* renamed to `project-lock` and its key gains no user dimension (§3.5) |
| 2 — `grog.client` | unchanged |
| 3 — `grog-server` (headless) | **+** identity/authn gate; per-user namespace live here |
| **3.5 — shared MCP** *(new)* | **promoted from Phase 4.** One `grog-mcp` over HTTP, per-request context, session-keyed state. Independent of Phase 3 and independently shippable |
| 4 — scale | now only *concurrency* + pooled/multiplexed ECA + auth hardening |

Rationale for promoting shared MCP: it is a **stated requirement**, not a scale
optimisation, and it is verifiable on one machine with two ECAs — no multi-user
deployment needed to prove isolation.

---

## 7. Decisions — **ALL CLOSED 2026-09-25**

1. **Project migration** — ✅ **RESOLVED: deferred.** Projects cannot be moved
   yet (user constraint). Interim = `:owner` recorded in place + server-side
   authorization at the path resolvers (§3.1). A future `mv` under `default/` is
   its own task with the cost already measured: 8 memory refs, 0 manifests,
   0 scripts.
2. **`global-mem.db`** — ✅ **RESOLVED: the concept does not survive
   server-side.** The global store is a *client/per-user* thing; the server has
   no global store. Observed contents (7 keys: 3 laptop notes, 1 leaked project
   workflow, 1 preference, 1 service how-to, 1 runtime snapshot) confirm it
   should shrink toward nothing — project stores carry the load. **Circle back
   when the client/server line is drawn** to place per-user/client globals
   where they make sense.
3. **Project sharing** — ✅ **RESOLVED: "accreate only" collaboration.** Other
   users may *add* new content (creates are naturally disjoint — no write
   conflicts); nobody mutates what's already there. Anything that must mutate
   shared state gets **wrapped in a lock** (the existing `state/.session.lock`
   primitive suffices). No ACLs, no invites, no reader lists — §3.5's full
   ownership table stays TABLED.
4. **Auth strength for remote** — ✅ **RESOLVED: none in v1.** External guard
   fronts the server; anything that reaches `grog-server` is assumed
   authenticated (§2). Token+TLS / OIDC designs are deferred to "deployment
   without a guard", additive at the request boundary.
5. **Where TLS terminates** — ✅ **RESOLVED: nowhere — no TLS in v1.** Same
   reasoning as 4: no in-server TLS, no proxy TLS requirement. Revisit only if
   the server is ever exposed past a trusted boundary.

---

## 8. Acceptance criteria (how we'll know it worked)

- **Isolation:** two sessions, one `grog-mcp` instance; session A cannot
  `assoc_keys`/`list_handles`/read a path belonging to session B. Asserted by a
  headless test, not by inspection.
- **No eviction bleed:** A performing >`max-open` store operations does not close
  B's open store.
- **Compat:** with no session context (local stdio), every tool behaves exactly
  as today; `tools/list` is byte-identical before/after.
- **Multi-user:** user B cannot resolve user A's project path through
  `workspace-folders`. Because migration is deferred (§3.1), this must hold
  **without** directory layout — the resolver returns *absent*, never a path or
  a "denied" that confirms existence.
- **No migration side effects:** `~/grog-projects/*` is byte-identical after
  this work — no project moved, renamed, or rewritten.

---

## 9. Coexistence — V1 stays in charge

**Constraint:** V1 (`/d/gni/grog`) remains authoritative for the existing
projects until the migration is tested. V2 (`/d/gni/grog-2`, branch `v2`) runs
*alongside* it on one machine, and the two must not touch each other's state.

### 9.1 What is shared today

| resource | shared? | collision |
|---|---|---|
| `~/grog-projects` | ✅ | `state/.session.lock` → second instance hits the "already open" chooser; `state/mem.db` + `memory.edn` written by both |
| `~/.config/grog` | ✅ | `sessions/<project>.json` keyed by **project name only** → V1/V2 clobber each other's generated ECA config |
| `~/.config/eca/config.json` | ✅ | **hardcoded** to `user.home/.config/eca` (`default-eca-config-path`), ignores `GROG_CONFIG_HOME` → both write it |
| keyring service `grog` | ✅ | benign — same provider keys, read from both |
| logs | ❌ separate | `~/grog-ui.<pid>.log` is already per-PID |

### 9.2 Mechanism — two config overrides, no code change

1. **Separate projects home.** `config/projects-dir` reads
   `:projects {:dir …}` from `grog.edn`, and **each repo has its own
   cwd-relative `grog.edn`** — so V2 sets:

   ```clojure
   :projects {:dir "~/grog-projects-v2"}
   ```

   Note `GROG_PROJECTS_DIR` does **not** exist; no code reads it.
2. **Separate config home.** `platform/config-home-dir` already honors
   `GROG_CONFIG_HOME`:

   ```bash
   GROG_CONFIG_HOME=~/.config/grog-v2 ./grog-ui
   ```
3. **Seed, don't move.** V2's projects home is populated by *copying* the real
   projects needed to exercise the migration. V1's originals are never touched —
   which is exactly what "V1 in charge" requires.

With all three the instances share **no mutable state**: separate locks,
separate `mem.db`, separate `memory.edn`, separate `sessions/*.json`.

### 9.3 Gap to close

`~/.config/eca/config.json` is written by both regardless of the two overrides.
Either make `default-eca-config-path` honor `GROG_CONFIG_HOME`, or skip writing
the default path when `--config-file` is supplied. **Required before V1 and V2
can both spawn ECA concurrently without racing.**

### 9.4 Acceptance

- V1 and V2 running simultaneously on identically-named projects: no lock
  conflict, no `sessions/*.json` clobber, no `eca-config` race.
- V1's `~/grog-projects` byte-identical after a V2 migration test run.
