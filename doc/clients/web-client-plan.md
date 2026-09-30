# grog web/Electron client — plan

Derived from **client 1** (the Swing GUI, `src/grog/ui*` in grog-2) for function,
and from **`/d/cms-aero/cms-estimate`** for stack, look/feel and widgets.
Status: **plan only.** No implementation yet.

---

## 1. Stack (mirrors cms-estimate deliberately)

| Concern | Choice | Evidence |
|---|---|---|
| Language | ClojureScript | `cms-estimate/shadow-cljs.edn:3-5` |
| UI | **Reagent 1.2.0 + re-frame 1.4.3** | `shadow-cljs.edn:8-11` |
| Host | **Electron 31.7.7** (main + preload + renderer) | `package.json:32-39` |
| Styling | **Tailwind 3.4.17** + postcss/autoprefixer, **hand-rolled widgets** (no MUI/antd) | `package.json:42-45`, `tailwind.config.js:5` |
| Build | shadow-cljs (`:app :browser`, `:init-fn <ns>/init`), dev server 9632, ready-gate on `tcp:9632` | `shadow-cljs.edn:14-24`, `package.json:9-19` |
| CSS pipeline | `input.css` → postcss → `output.css` | `postcss.config.js:2-6` |

New client lives at **`clients/web/`** (see `doc/multi-client-layout.md`), one
shadow-cljs project + one `package.json`, depending on grog's **client-side
contract only**.

## 2. Look & feel (borrowed tokens)

From `cms-estimate/resources/public/css/input.css:5-16` — dark only, no theme
switcher:

```
bg      slate-950 #020617      text   slate-100 #f1f5f9
panels  slate-900 #0f172a / slate-800 #1e293b
border  slate-700 #334155      accent sky-500 #0ea5e9 (focus rings, active tabs)
ok      emerald-300 #6ee7b7    warn amber-500 #f59e0b   error rose-500 #f43f5e
pills   indigo-900 #312e81     scrollbar #334155 / hover #64748b (input.css:37-42)
font    ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, … (input.css:5-16)
radii rounded-md/lg/xl, shadows shadow-sm/xl/2xl
```

**Difference from client 1:** Swing paints a *role palette* from `grog.edn`
`:appearance :chat` (answer #ff99af, tool-call #ffa05a, thinking #69b478, user
#a58a19, snark #828692, bg #0d0e11 …). Plan: emit those triples as **CSS custom
properties** and reference them with Tailwind arbitrary values
(`text-[color:var(--grog-answer)]`), so grog.edn stays the single source of role
colours while the shell keeps cms-estimate's slate/Tailwind skin.

## 3. Functional spec (extracted from client 1)

### 3.1 Shell
- **Tabs = sessions**, one per project; **shared status bar** reflecting the *active* tab.
- Keys: `Ctrl+Tab` / `Ctrl+Shift+Tab` cycle, `Ctrl+1…9` jump, `Ctrl+T` new tab,
  `Ctrl+W` close (never zero tabs), `Ctrl+E` export active transcript.
- Opening an already-open project **focuses** it; a project held elsewhere →
  **collision chooser with live search**; unknown name → create-on-demand.
- Per-tab header: live status dot + project name + ✕.

### 3.2 Session pane
- **Transcript**: streamed, colour-coded by role (answer / thinking / tool-call /
  user / snark / status line), message cards with border + bubble bg, splash logo
  (subdued after first message), startup **snark banner**, follow-scroll toggle,
  copy-all / copy-selection / open-as-HTML menus, wheel/scroll keys.
  Assistant answer/thinking is rendered as **Markdown** via `grog-web.md`
  (GFM pipe tables → real `<table>`; code → monospace `<pre>`/`<code>`) — see §3.7.
- **Prompt**: multi-line; `Enter`/`Shift+Enter`/`Alt+Enter` insert newline,
  `Ctrl+Enter` submits; drag-and-drop text in/out (web: paste + file drop).
- **Toolbar**: Send · Stop · Mic (voice) · Terminal · Settings · Export ·
  Open-as-HTML · Clear · project button (project manager).
- **Status bar**: model · status (idle/streaming dot) · trust/YOLO · tokens+cost.

### 3.3 Behaviour
- **Turn model**: idle → queue prompt; running → **steer** (with resend-if-dropped);
  Stop cancels; echo suppression for ECA's own echo of the prompt.
- **Slash commands** routed by the server; their stdout is captured and shown as
  **status lines**; `/clear` wipes transcript and drops YOLO; `/quit` exits.
- **Trust (YOLO)**: auto-approve all tool calls; toggle from toolbar/dialog/slash.
- **Model**: change via settings or `/eca-model`; persists; footer + status bar update.
- **Usage**: per-turn and per-session tokens/cost folded from ECA `usage` content.

### 3.4 Dialogs (must match client 1's semantics)
- **Tool approval** — 4 choices: `Approve` · `Approve tool` (permanent allowlist) ·
  `Reject` · `YOLO`. Enter = approve (default), Esc/X/close = **reject**.
- **LLM question** — options list + freeform; cancel allowed; answered *during* the
  event fan-out (server waits, bounded).

#### 3.4.1 Question visibility rule (**new — not in client 1**)

**Rule:** a pending **question** dialog is presented **only when the app window is
focused AND the tab of the session that owns the question is the selected tab.**
Otherwise the question stays *pending* and is surfaced non-disruptively.

Rationale: a question is a blocking request — client 1 raises it wherever the user
happens to be, which is both disruptive and disorienting ("which session is asking
me this?"). Nothing is worse than an ambush dialog with no provenance.

**Indicator (must be distinct from the normal status dot):**

| Surface | Pending-question state |
|---|---|
| Tab (of the asking session) | amber pulsing dot **+ `?` badge**, tooltip "question pending" |
| Tab (selected/other) | unchanged otherwise — amber only where the question lives |
| Status bar | `⚠ question pending · <project>` chip, always visible (shared bar) |
| Window title / taskbar (Electron) | optional badge/count when unfocused |
| Transcript | a status line where the question will arrive (chronological anchor) |

**Presentation:** when the user focuses the window *and* selects that tab, the dialog
appears then (no re-dispatch needed — the pending state is rendered from db).

**Timeout interaction (load-bearing):** the server's question wait is **bounded**
(600 s, `grog.server/question-timeout-ms`). Deferring presentation therefore risks
silent expiry, so the rule requires: when a pending question is older than ~80 % of
the wait, **escalate** (red badge + title flash + transcript warning), and on expiry
record an explicit `question timed out — cancelled` status line so the user never
sees a turn that "just stopped".

**Focus detection:** Electron main tracks `browserWindow` focus/blur and forwards it;
renderer may also consult `document.hasFocus()` as a fallback.

### 3.5 Deferred for v1 of this client
embedded terminal (pty4j/JediTerm), native DnD from the OS, HTML export,
animated logos.

### 3.6 Voice input (client-side STT) — implemented
Push-to-talk **input** only (no voice output). Toolbar **🎤** button: click to
record, click again to stop (or **Ctrl+Shift+Space**); the transcript is inserted
into the composer at the end of the current text and the composer is refocused.
State-machine (idle → recording ⏺ → transcribing …) driven by `:recording?` /
`:transcribing?`; auto-stops at `maxSeconds`. Gated on `:voice :enabled` from the
local engine; disabled/loud-hint when unconfigured.

**Everything stays on the client machine — the server never sees audio.**
- **Capture (renderer, `grog_web/voice.cljs`)**: `getUserMedia` → `AudioContext`
  at 16 kHz → `ScriptProcessorNode` buffers Float32 → linear resample (no-op at
  16 kHz) → hand-rolled 16-bit mono **WAV** encoder. `stop!` resolves
  `{:wav Uint8Array :seconds n}`.
- **Transcription (Electron main, `main.js`)**: `grog:voice-transcribe` writes the
  bytes to a temp WAV and runs the local engine via `execFile` (whisper.cpp),
  then deletes it. Silence markers → `""` (mirrors `grog.voice/clean-transcript`).
- **Config**: `GROG_VOICE_COMMAND` (JSON array or shell string; `{wav}`
  substituted) — default is `whisper-cli -m <model> -f {wav} -nt`, model under
  `~/.config/grog/models` (or `GROG_VOICE_MODEL`), falling back to the common
  local build at `~/whisper.cpp/build/bin/whisper-cli`. `GROG_VOICE_ENABLED=0`
  disables; `GROG_VOICE_MAX_SECONDS` (default 60) caps recording.
- **preload**: `grogAPI.voiceStatus()` / `voiceTranscribe(bytes)`.
- **Mic permission**: `session.defaultSession.setPermissionRequestHandler` grants
  `media`; `getUserMedia` also needs a secure context (`localhost` dev and
  `file://` prod both qualify).

### 3.7 Markdown rendering (tables + code) — implemented
The transcript used to paint every segment as a plain `white-space: pre-wrap`
span in the **sans-serif body font** — so GFM tables came out as a ragged wall of
`|` and code had no fixed font. Assistant `:answer`/`:thinking` now render
through **`clients/web/src/renderer/grog_web/md.cljc`** (Markdown → hiccup):

- **Blocks**: fenced + indented code → `<pre><code>`, ATX headings, GFM pipe
  tables → `<table>` (with `:---`/`:--:`/`---:` alignment), bullet/ordered lists,
  blockquotes, horizontal rules, paragraphs (soft-wrapped lines joined).
- **Inline**: code spans, `**bold**`, `*italic*`, `~~strike~~`, `[links](url)`,
  backslash escapes. `_underscore_` emphasis respects word boundaries so
  `snake_case` identifiers are left alone.
- `<text/markdown>` … wrappers are stripped before parsing.
- Styled by a hand-rolled `.md{…}` block in `resources/public/css/input.css`
  (mono `--grog-mono` stack for code; bordered/padded table with header tint and
  `overflow-x` for wide tables).
- Portability: the ns is `.cljc` with no reader conditionals, so the *same* code
  is unit-tested on the JVM with **babashka** (`bb -cp src/renderer`) and shipped
  to the browser build — one implementation, one behaviour. Verified against the
  real "Option | Reality check" table that broke client 1.

This is intentionally not a full CommonMark parser; the long-term plan (renderer
ns docstring, risk 1) is still to normalize ECA content in `grog.chat` and share
one vocabulary/rendering across clients.

## 4. Architecture

```
Electron main (Node)            renderer (CLJS/Reagent/re-frame)
 ├─ owns transport:            ┌─ dispatches every server event into re-frame
 │   unix socket / spawn+stdio │  [:grog/event ev]  → sessions/{id}/transcript …
 ├─ preload → window.grogAPI   ├─ subs per session; active tab mirrored
 └─ request/response + stream  └─ commands → IPC → transport → server
```

- **Transport stays out of the renderer.** The main process holds the socket (or
  spawns the server in the transitional stdio mode) and re-emits to the renderer
  over IPC — exactly cms-estimate's `window.estimateAPI` + preload pattern
  (`events.cljs:2262-2268`). This is what makes the web client *thin*: no
  ECA/MCP/native deps, and it can attach to a systemd daemon later with no UI change.
- **API surface = `grog.client`** (open/close/sessions/session/connect/prompt/
  steer/stop/answer/set-model/set-trust/subscribe). Reuse of the protocol means
  the Swing client and this one cannot drift.
- **Event → re-frame**: one dispatch per event, coalesced for streaming text
  (append batching, ~60 fps flush) to avoid re-frame churn on token streams.
- **DB shape**: `{:sessions {id {:project :status :model :trust :usage :connected
  :running? :transcript [...] :pending-approval …}} :order [ids] :active id}`,
  seeded from the `open` snapshot, then merged from events.
- **Questions/approvals**: approval = fire-and-forget `answer!`; question = the
  transport's bounded wait (`answer-question`) — the UI must reply from the event
  handler path, not asynchronously.

## 5. Reuse from cms-estimate (lift wholesale)

| File | Use |
|---|---|
| `views/layout.cljs` | app shell skeleton + `vertical-resizer` (`layout.cljs:16-54,58-129`) |
| `views/sidebar.cljs` | `tab-button` / `panel-header` / tab strip (`:22-42,283-301`) |
| `views/toast.cljs` | notification container/items (`:28,76`) |
| `views/settings.cljs` | modal pattern (`:19-20`) |
| `views/login.cljs` | form/input styling pattern (`:96-112`) |
| `resources/public/css/input.css` | dark shell, scrollbars, grid texture (`:5-56`) |

**Not present in cms-estimate — must be built here:** virtualized transcript list,
markdown/code-block rendering, ANSI-or-role-coloured segments, Enter/Ctrl+Enter
key handling, and the entire streaming/event layer (cms-estimate has *no*
WebSocket/SSE/streaming code — verified by grep).

## 6. Phasing

| Phase | Deliverable | Acceptance |
|---|---|---|
| **P0** | Electron+shadow+Tailwind skeleton; preload IPC; transport (attach or spawn); open project; live transcript; prompt/stop | a real turn streams into the window |
| **P1** | Tabs + status bar + project manager + settings (model) + trust toggle | multi-tab parity with client 1 |
| **P2** | Approval + question dialogs with client-1 semantics | tool approval round-trips |
| **P3** | Usage/cost, snark banner, status lines from slash commands, export | feature parity minus §3.5 |
| **P4** | Role colours from grog.edn `:appearance` → CSS vars; resizers; toasts | styling matches cms-estimate skin + grog roles |

## 7. Risks

1. **Event vocabulary**: client 1 paints raw ECA `content`; the web client wants
   normalized `:text/:thinking/:tool/…`. Either normalize server-side (the
   `grog.chat` tally exists to inform this) or duplicate client-1's summarizer.
   *Recommend: normalize in `grog.chat` — one vocabulary, all clients.*
2. **Question timing**: the server waits for a synchronous answer during fan-out;
   IPC round-trips must be fast and must not be dispatched lazily.
3. **Streaming churn**: naive per-token dispatches will jank re-frame; batch.
4. **Transcript size**: long sessions need virtualization; client 1 already uses a
   virtualized list — plan for `react-window`-style windowing from P1, not later.
5. **Attach vs spawn**: **RESOLVED — attach is built.** `grog.server` hosts a
   Unix-domain socket (default `$XDG_RUNTIME_DIR/grog-$USER.sock`, override
   `GROG_SERVER_SOCKET`); the Electron main process attaches and never spawns
   (a second server would double-claim project locks). Stdio-spawn remains only
   for the Swing client, whose server also binds the socket best-effort so the
   web client can join the *same* registry. `open` is idempotent per project so
   attaching clients share one server-owned session.
6. **Two clients, one contract**: the moment this client exists, `grog.client` and
   the wire format become a public API; changes need both clients in mind.

## 8. First three moves

1. `clients/web/` scaffold (shadow-cljs + Reagent + re-frame + Tailwind + Electron),
   reusing §5 files, rendering a static shell.
2. Transport in Electron main + `[:grog/event]` pipeline; open a project and render
   a live transcript (P0 acceptance).
3. Normalize the event vocabulary in `grog.chat` (risk 1) before the UI hardens
   around raw content.