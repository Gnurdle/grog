# Multi-client tree layout (target)

## Decisions (2026-09-28) — read these first

**Server line: PARKED.** The `grog.server` daemon, unix/TCP transports, attach
mode and systemd units are abandoned as a product direction. Reason (user's
call, and correct): MCP tools act on the machine they run on, so a *remote*
server cannot touch the user's files — the tools are the value and they belong
next to the files. A server returns only in this shape, and only if ever
needed: **the client hosts its box-bound tools as an MCP server; the server
orchestrates and owns the network/credential tools.** Code stays in-tree as a
reference; nothing is built or shipped from it. `grog.edn`'s `:server` block is
removed so no client tries to spawn/attach one.

**Swing client: DEPRECATED.** `src/grog/ui*` stays in-tree as the reference
client (it is the only one at feature parity today) but it is **not a build
target, not packaged, and must not drive design decisions**. Feature parity
(functions only, not layout) moves to the Electron client.

**The client seam lives in the Electron main process.** `grog.client`-shaped
behaviour (spawn ECA + the MCP jar, own the transports, fan events out) belongs
in `main.js`; the renderer stays pure UI over IPC. That keeps a future
different-transport or different-runtime swap to one file.

**Client scope (Group D, 2026-09-28).** One window with **tabs** (Swing-like).
**Tear-off tabs → new window** is a wanted, cheap follow-on: because Electron
*main* owns the session registry and the renderer is a pure view over it, a
torn-off tab is just another window subscribed to the same session (one set of
ECA/MCP children, not two) — `BrowserWindow` + an IPC re-home, no transport
work. **Terminal pane: v1.1** (node-pty is cross-platform but brings native
builds + conpty quirks; nothing depends on it). **Attention/pending UX:**
dialogs (questions *and* approvals) are raised **only when their tab is the
selected one** (and the window is focused); otherwise the tab itself carries the
indicator (amber badge) — never an ambush from a background tab. **Voice:
enable-if-installed** (whisper-cli + model), doctor reports what's missing and
what it unlocks — same policy as every other capability. **HTML export: any
session**, not just the active one (the transcript is data, so the renderer
serves history from `dialog/thread.edn` too).

**Config, install & release (Groups C / E, 2026-09-28).** **Model limits are
ECA's business** — grog does *not* set `:llm :max-tokens` (task 15: first verify
the client knob isn't a no-op, then fix ECA's `sane-output-limit` upstream, with
the models.dev catalog as the last local resort). **Config home stays
`~/.config/grog` on Windows too** (one code path; doctor prints the resolved
path). **Installs are per-user** (`%LOCALAPPDATA%\grog`, `~/.local/share/grog`);
the installer owns the jar; **ECA is user-provided, version-pinned and checked
by doctor**. **Secrets live in the OS secret store** (Secret Service /
Credential Manager) via the existing keyring path — tools-side only, the UI never
sees them. **Release = a single `build_dist` babashka script** producing the
collective: the jar **rides along inside** the Electron app. Everything grog
ships installs **as a collective, one version**; **all updates are manual** (no
auto-update for v1). Doctor is the guard rail: dependency probing, config
solvency with provenance, and version-mismatch reporting.

**Everything runs on the client.** The universe is: Electron app + uberjar(s) +
pinned ECA binary + probed external tools (bash, bb, soffice, tesseract,
poppler). No daemon, no sockets, no attach. Projects/team sharing = git.

---

Status: **plan, not yet applied.** The Swing GUI will not be the only client;
the server/endpoint split (Phases 1–3.5) is what makes more clients cheap.
This is the layout to rearrange into, so each future client is an *addition*,
never a fork.

## Target

```
grog-2/
  deps.edn            ; thin: aliases only, no heavy deps at top level
                      ;   :core  :server  :client.swing  :mcp  :endpoint

  core/               ; headless engine — no Swing, no tools
    src/grog/         ;   chat.clj        event stream + turn pipeline
                      ;   client.clj      THE client contract (all clients speak this)
                      ;   client/local.clj, client/remote.clj (adapters)
                      ;   eca.clj, eca_config.clj, config.clj, models.clj,
                      ;   projects.clj, secrets.clj, session.clj (locks), log.clj

  server/             ; the daemon: wiring + entrypoints only
    src/grog/         ;   server.clj      JSON-RPC over stdio + unix socket (many clients attach)
                      ;   mcp_http.clj    owns/supervises the MCP endpoint child

  clients/
    swing/            ; today's GUI, moved here (grog.ui.* + transcript, widgets,
                      ; footer, shell window, settings). Deps: flatlaf, jediterm,
                      ; pty4j, commonmark, cheshire. NO tess4j/poi/pdfbox/boofcv/
                      ; sqlite/keyring/jna.
    web/  tui/        ; future — each a grog.client adapter + its own skin

  mcps/               ; tool layer, one project per server + the bundle
    grog_mcp/         ;   bundle + vendor-src + http.clj (the Streamable-HTTP
                      ;   endpoint: one JVM, one localhost port per server key)
    grog-*/           ;   individual servers (still runnable standalone)

  doc/
```

## Rules that make it work

1. **`core/` has zero windowing and zero tool deps.** A client that adds a
   toolkit must not be able to drag the engine's dependency surface with it —
   the seam tests (`:import` form check in `chat.clj`, `client.clj`) stay the
   enforcement.
2. **`grog.client` is the only door.** Server implements it (local + remote
   adapters), every client consumes it. A new client = a new adapter + skin.
3. **Tools live with the tools.** The endpoint stays inside `mcps/grog_mcp`
   (it needs those deps); the *server* only supervises the child.
4. **Per-client deps are declared in the client's own project**, so
   `clj -M:client.swing` cannot see tess4j.
5. **No client ever spawns ECA/MCP.** Attach over socket to the daemon;
   spawning the server as a child is the transitional (stdio) mode only.

## Why now

- The client diet (drop `grog.chat`/`grog.core`/`grog.eca`/`grog.projects`/
  `grog.session` requires; locks move server-side) is the *same* edit surface as
  this move — doing them together avoids churning the same files twice.
- The systemd step needs the daemon to be a first-class thing, not "the GUI's
  child".
- Every future client should be born into `clients/<name>/`, not retrofitted.

## Sequencing

1. Verify the URL cutover live (task #4).
2. `core/` + `server/` split (pure moves + alias changes; no behaviour change).
3. Locks server-side; client severed from the local stack; deps split.
4. Unix-socket transport + attach mode → systemd unit.
   **[socket + attach DONE — `grog.server` binds a Unix-domain socket
   (`GROG_SERVER_SOCKET`, else `$XDG_RUNTIME_DIR/grog-$USER.sock`), broadcasts
   notifications to every attached connection, and `open` is idempotent per
   project; `clients/web` attaches (no spawn). Systemd unit still pending.]**
5. `clients/swing/` move; then the first non-Swing client proves the layout.