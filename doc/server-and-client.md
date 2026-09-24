# grog as a client/server — design & implementation plan

> Status: **design only** (nothing here is implemented). The multi-tab UI is being
> built first, pragmatically (each tab its own process pile). This document defines
> the contract the tab work should not paint us into a corner on.

## 1. Motivation

Today "grog" is one Swing process that owns *everything*: the GUI, the ECA server
child, all MCP servers, provider keys, projects, memory, and config. That couples
the UI to the whole machine setup, which:

- makes the Windows story heavy (Clojure CLI, ECA binary, MCP JVMs, keyring,
  Tesseract/sqlite/PDFBox all must be present locally),
- makes multi-user / remote use impossible,
- and makes every UI feature (tabs, sessions) a rewrite when the backend changes.

The goal is to split grog into a **headless server** that owns the heavy,
stateful, secret-bearing parts, and a **thin client** that owns pixels — talking
over one protocol. Local (in-process) and remote are then just two adapters.

## 2. Target architecture

```
        grog-server  (headless)
        ├── ECA process(es) + generated config
        ├── MCP servers (memory, imaging, office, odoo, project-search, …)
        ├── projects, memory dbs, per-project config files
        ├── OS keyring / secrets, provider keys
        ├── project session locks
        └── grog.session ×N ──► event stream (chatId-routed)
                     ▲
                     │  protocol: JSON-RPC-2.0 over stdio (local) / WS (remote)
                     ▼
        grog-client  (thin; same Swing skin)
        ├── tabs, transcript, prompt, status bar
        └── NO ECA, NO MCPs, NO keyring, NO native libs
```

## 3. The client boundary (`grog.client`)

The UI must be written against this and **never** call ECA/MCP directly again:

```clojure
grog.client
  (sessions)                 ; -> [{:id :project :status :model :trust}]
  (open! project)            ; -> session-id (claims the project lock)
  (close! id)
  (prompt! id text)
  (stop! id)  (steer! id text)
  (answer! id {:decision …}) ; approval / question responses
  (set-model! id model)  (set-trust! id bool)
  (subscribe! f)             ; -> events (below), tagged with :chatId/:session
  (unsubscribe! f)
```

Adapters:
- `grog.client.local` — calls `grog.session` in-process (today's behavior).
- `grog.client.remote` — JSON-RPC over stdio (same machine) or WebSocket (remote).

## 4. Event schema (the wire format)

The transcript/status events are already `chatId`-routed by ECA; grog re-emits a
normalized superset:

```clojure
{:session "uuid"            ; grog session id
 :chatId  "uuid"            ; ECA chat id
 :type    :banner|:status|:user|:assistant|:thinking|:tool|:approval|:question
 :at      <epoch-ms>
 ;; type-specific:
 :text "…"  :state "finished"
 :tool {:name … :server … :args … :summary … :status :running|:done|:error}
 :approval {:id … :name … :summary … :manual true}
 :question {:prompt … :options […]}
 }
```

The Swing transcript is one subscriber; a future web client is another; a local
client is the identity adapter.

## 5. What moves where

| Concern | Today | Target |
|---|---|---|
| ECA process + generated config | client machine | **server** |
| MCP servers | client machine | **server** |
| Provider API keys / keyring | client machine | **server only** |
| Projects, memory dbs, per-project configs | client | **server** |
| Project session locks | client | **server** |
| Transcript *model* (messages/events) | inside the Swing component | **server session** (client renders) |
| Terminal/PTY, appearance, rendering | client | **client** |
| Window/tabs/input | client | **client** |

## 6. Facts that constrain the design (verified in ECA's docs)

1. **`chat/contentReceived` and `chat/statusChanged` carry `chatId`**; `chat/prompt`
   accepts `chatId` and per-prompt `contexts`. → many chats can share one ECA
   connection, routed by `chatId`.
2. **`workspaceFolders` (and the project `rules`) are set at `initialize`** — i.e.
   **per connection**, not per chat. → true per-project isolation needs one ECA
   connection per project (today's model), unless we accept a shared workspace root.
3. **ECA supports remote MCP servers over `url` (Streamable HTTP)**, not just
   `command`/stdio. → MCP servers can be **shared** across sessions.
4. **ECA has a remote mode** (`src/eca/remote/`: HTTP/SSE server exposing chats to
   non-stdio clients). → ECA itself can be shared/multiplexed.

## 7. Deployment targets & per-session overhead

| Tier | Per session (server-side) | Dozens of users? |
|---|---|---|
| A — today (client JVM + ECA + ~11 MCP JVMs) | ~2–8 GB | ❌ |
| B — consolidated `grog-mcp` bundle (client + ECA + 1 JVM) | ~0.4–0.7 GB | ❌ |
| C — shared services (pooled HTTP MCP + shared ECA-remote, thin client) | ~KB–MB state + a share of shared procs | ✅ plausible |

Tier C requires:
- **Session-agnostic tools**: project context passed **per call**, not baked into
  server env/files (this is the same problem as the global `memory.edn` /
  `project-search.edn` clobbering — a shared server *forces* per-call context).
- **Concurrency**: shared handlers thread-safe; heavy tools (OCR, office render,
  SQL) behind limits/bulkheads.
- **Containment/authorization**: a shared tool server must scope each session to
  its own files/dbs (grog *removed* workspace containment; a shared server must
  reintroduce a per-session scope).
- **Auth + TLS** on client↔server and server↔MCP.
- Accept that the real ceiling is **LLM provider throughput**, not processes.

## 8. Implementation plan

Each phase is independently shippable and reversible.

### Phase 0 — (this change) Multi-tab UI, local, one process pile per tab
- `JTabbedPane`; one tab = one session (project + its own ECA connection + MCPs).
- Shared bottom status bar bound to the **active** tab.
- Shortcuts: `Ctrl+Tab`/`Ctrl+Shift+Tab` cycle, `Ctrl+1…9` jump, `Ctrl+T` new tab,
  `Ctrl+W` close tab.
- Per-tab project lock; opening a project already in a tab **focuses** it.
- *Not* a step toward the server in code structure, but it defines the UX.

### Phase 1 — Extract `grog.session` (headless)
- Move event handling / worker / connect wiring out of `grog.ui` into
  `grog.session`; the transcript becomes an **event stream** with the Swing
  component as one subscriber.
- Rename the current lock ns `grog.session` → `grog.project-lock`.
- Accept: the GUI runs identically with the UI talking only to `grog.session`.

### Phase 2 — `grog.client` + `grog.client.local`
- Introduce the client interface; implement the in-process adapter.
- Rewrite the GUI against `grog.client`. Accept: no behavior change.

### Phase 3 — `grog.client.remote` + `grog-server`
- JSON-RPC server exposing the client API + event stream (stdio first, WS next).
- `grog-server` runs ECA + MCPs + projects + secrets; the client holds none.
- Accept: the Swing client connects to a remote `grog-server` and works
  end-to-end (same skin).

### Phase 4 — Shared services (scale)
- Run `grog-mcp` as a shared Streamable-HTTP MCP; point every ECA at it by `url`.
- Multiplex ECA (remote mode) or run a small pool.
- Make tools take per-call project context; add containment + auth.
- Accept: dozens of concurrent sessions on one server within a memory budget.

## 9. Open questions / risks

- **Workspace root**: share one ECA (accept a common root, rely on absolute paths)
  vs one ECA per project. Phase 3 must decide.
- **Containment**: a shared server needs per-session filesystem scoping — a
  security feature we deleted earlier; it must come back in a different form.
- **Secret handling**: keys stay server-side; the client must never receive them
  (a real security win of the split).
- **GraalVM**: the *client* drops the native/JNI surface (sqlite/tess4j/keyring/
  POI), so native-image for a thin Swing client is far smaller than for the
  kitchen sink. The *server* can stay a plain JVM / uberjar.
- **Compatibility**: Phase 0/1/2 must keep the single-user local path working the
  whole time.
