# Architecture and layout

## How grog runs

Everything runs on the client machine. grog is a desktop app; it starts its own
backend and the backend starts the tools:

```
grog                                  the desktop app
 └─ java -cp target/grog-spine.jar                  the backend ("the spine")
     ├─ eca                                           the agent loop
     └─ bash -lc "cd grog_mcp && java -cp \
                   grog_mcp/target/grog-mcp-<v>.jar \
                   clojure.main -m grog_mcp.main --server <id>"    ×N   the tool servers
```

There is no daemon, no socket and no attach step. The tools act on the machine
they run on — that is the point of grog — so they belong next to the files, in
the same process tree the app owns.

**The client seam lives in the app's main process.** Spawning the backend,
owning the transports and fanning events out to the interface belongs there; the
interface itself is a pure view over IPC. A different transport or runtime is
therefore a one-file change.

**The interface is a single window with tabs.** A tab can be torn off into its
own window: because the main process owns the session registry, a second window
is another subscriber to the same session — one set of backend and tool
processes, not two. Dialogs for questions and approvals are raised only when
their tab is selected and the window is focused; otherwise the tab carries a
badge.

## Where things live

```
grog-2/
  deps.edn            ; thin: aliases only, no heavy deps at top level
                      ;   :core  :server  :client  :mcp  :endpoint

  core/               ; headless engine — no UI, no tools
    src/grog/         ;   chat.clj        event stream + turn pipeline
                      ;   client.clj      THE client contract (all clients speak this)
                      ;   client/local.clj, client/remote.clj (adapters)
                      ;   eca.clj, eca_config.clj, config.clj, models.clj,
                      ;   projects.clj, secrets.clj, session.clj (locks), log.clj

  server/             ; wiring + entrypoints
    src/grog/         ;   server.clj      JSON-RPC over stdio (the backend the app owns)
                      ;   mcp_http.clj    supervises the MCP endpoint child

  clients/
    web/              ; the desktop app (UI + the seam above)
    tui/              ; future — each is a grog.client adapter + its own skin

  mcps/               ; tool layer, one project per server + the bundle
    grog_mcp/         ;   bundle (:local/root deps) + the Streamable-HTTP endpoint
    grog-*/           ;   individual servers (still runnable standalone)

  doc/
```

The tree is flat today; this is the arrangement it moves to. Each future client
is an addition, never a fork.

## Rules

1. **`core/` has zero UI and zero tool dependencies.** A client that adds a
   toolkit must not be able to drag the engine's dependency surface with it. The
   seam checks (the `:import` form test in `chat.clj`) enforce this.
2. **`grog.client` is the only door.** The backend implements it (local and
   remote adapters); every client consumes it. A new client is a new adapter
   plus a skin.
3. **Tools live with the tools.** The endpoint stays inside `mcps/grog_mcp`
   because it needs those dependencies; the rest of the tree only supervises it.
4. **Per-client dependencies are declared in the client's own project**, so the
   engine cannot see an office or OCR library.
5. **Spawn the backend, don't attach to one.** The app owns its backend as a
   child process.

## Configuration, install and release

* **Config home is `~/.config/grog` on every platform** — one code path.
  `grog doctor` prints the resolved location.
* **Installs are per-user** (`%LOCALAPPDATA%\grog`, `~/.local/share/grog`). The
  installer owns the jars. **ECA is user-provided**, version-pinned, and checked
  by `grog doctor`.
* **Secrets live in the OS secret store** (Secret Service / Credential Manager);
  the interface never sees them.
* **Release is one script.** `bb dist` produces the collective: the jars (which
  ride inside the app) plus the installer. grog ships as **one version**, taken
  from `VERSION` and stamped into the jars as `grog-version.edn`. All updates
  are manual.
* **`grog doctor` is the guard rail**: dependency probing, config solvency with
  provenance, and version-mismatch reporting.

## Output limits

grog does not set an output-token limit for the agent loop; that is ECA's
setting. grog's own direct OpenAI-compatible client (used by the command-line
chat) honours `:llm :max-tokens`, but the desktop app's path does not use it.
