# Grog Configuration & Secrets — Users Guide

This guide explains how **grog** finds its configuration, how to set up an LLM
provider (Ollama, OpenRouter, OpenAI, Groq, …), and how to store secrets in a
way that works on **both Linux and Windows** — including headless/remote Linux
where your desktop's OS keyring is not reachable.

> If you're new: jump to the [Quick Start](#quick-start), then come back to the
> detailed sections when you need them.

---

## 1. Where grog looks for config

grog merges several sources. **Later sources win.**

| Pick | File | Purpose |
|---|---|---|
| 1 | bundled defaults (`resources/config.examples/grog.edn.example`) | built-in defaults / template |
| 2 | **user `grog.edn`** (see table below) | your personal setup |

There is deliberately no `./grog.edn` (working-directory) layer: it used to
override the user config, which made saved settings (model, appearance) revert
on reload.

### Where is the user `grog.edn`?

The location is **platform-aware** and can be overridden with **`GROG_CONFIG_HOME`**:

| OS | Default user config path |
|---|---|
| All (Linux / macOS / Windows) | `${XDG_CONFIG_HOME:-~/.config}/grog/grog.edn` → usually `~/.config/grog/grog.edn` (Windows: `C:\Users\you\.config\grog\grog.edn`) |
| Any (override) | `$GROG_CONFIG_HOME/grog.edn` |

> **Windows:** grog uses `~/.config/grog` (same as ECA's own `~/.config/eca`).

Generated files (ECA config, IMAP/Odoo metadata, approved-tools) live **in the
same config home directory**, so moving to a new machine is "copy one folder +
set one env var". Secret VALUES are not there — they live in the OS keyring.

> `~/.config/grog/grog.edn` is honored even when `$GROG_CONFIG_HOME` points
> elsewhere.

---

## 2. Quick start

1. **Install prerequisites** — JDK 21+, the Clojure CLI, and (for local serving)
   an OpenAI-compatible server such as Ollama. See the main `README.md`.
2. **Create a user `grog.edn`** in your platform config home (above).
   Simplest possible file for a **local Ollama** setup:

   ```clojure
   {:llm {:url "http://localhost:11434/v1"
          :model "qwen3.5:9b"}}
   ```

3. **Run grog**:

   ```bash
   cd <repo>
   scripts/grog-client --console   # desktop app (Linux; scripts\grog-client.bat on Windows)
   ```

4. **Talk to it.** If your provider needs an API key, store it (section 4) before
   chatting.

### First run — the getting-started project (`ouroboros`)

A brand-new grog has no provider, model or key, so it cannot think yet. Two
things happen on that first launch, both fully offline:

1. **The bootstrap page.** Instead of a transcript you get *Job 1 — get your LLM
   online*: it names the three steps (set `:llm :url` and `:llm :model`, store the
   key with `/secret set LLM_API_KEY <value>`, restart). The composer stays usable,
   because `/secret` is handled locally and needs no model.
2. **`ouroboros`.** grog seeds ONE project, named after the snake that eats its
   tail — the getting-started project. Its `SOUL.md` makes the agent your
   onboarding guide, and grog's **own documentation is mounted into it** as a
   workspace folder named `grog-docs/`. The agent therefore reads the *shipped*
   docs (they travel inside the application) and walks you through setup,
   `grog doctor`, and a first smoke test.

The docs are **referenced, never copied** — the app bundle is the single source of
truth, so upgrading grog upgrades the docs the agent reads. `ouroboros` is
(re)created when you have **no projects at all**, or when grog's config home has
just been seeded or reset — so deleting `~/.config/grog` and relaunching brings the
welcome back. It never displaces your own work.

The bootstrap page shows again on any fresh config home, and it reports *grog's*
readiness only: the "three steps" retire once **grog** has its own key (or points
at a local endpoint). A key that lives only in ECA's own config does **not** count —
onboarding exists to set up *grog's* secret store, so a fresh grog still walks you
through it even if an inherited ECA key could reach a model.

> Guided setup is much easier with a capable model. On a small local model, expect
> to lean on `grog doctor` and the docs rather than the model's reasoning. The
> choice of model is yours.

---

## 3. Full annotated config

On first run — when there is no `grog.edn` — the client **writes one for you**
from the bundled example, names the exact path in its log, and leaves the
optional examples beside it:

```text
[grog-client] no grog.edn yet - seeded the config home from the example:
  ~/.config/grog/grog.edn
[grog-client] edit ~/.config/grog/grog.edn - at least :llm :url, :llm :model and
  an API key - then restart grog; until then it cannot reach a model.
```

Nothing is overwritten, and the starter is still missing a model, a provider and
an API key, so it cannot work until you edit it — edit the file, then restart
grog. The optional examples (odoo, imap, gitlab, imaging, secrets) are left
alongside it as `*.example`; copy the ones you need.

Everything is optional except `:llm :url` and `:llm :model`.

*(From a source tree the same files live at `resources/config.examples/` and the
client seeds from there too, so a dev checkout gets a starter `grog.edn` just
like a packaged install.)*

```clojure
{:llm {:url "http://localhost:11434/v1"      ; OpenAI-compatible /v1 endpoint
       :model "qwen3.5:9b"                    ; model id
       ;; no :api-key — keys live in the OS keyring:  /secret set LLM_API_KEY
       ;; optional: connection/read timeouts (seconds)
       ;; :conn-timeout-sec 60
       ;; :socket-timeout-sec 300
       ;; optional: cap individual tool results
       ;; :max-tool-result-chars 50000
       ;; optional: LOWER the context budget (it is derived from the model's window
       ;; automatically; a prompt that does not fit is distilled and retried, never fatal)
       ;; :max-context-tokens 60000
       ;; optional: temperature, max output tokens
       ;; :temperature 0.7
       ;; :max-tokens 4096
       ;; optional: named presets for `/model`
       ;; :profiles {:local  {:url "http://localhost:11434/v1" :model "qwen2.5-coder:7b-instruct"}
       ;;            :remote {:url "https://openrouter.ai/api/v1" :model "moonshotai/kimi-k2.7-code"}}
       ;; LOCAL servers (Strata, vLLM, ollama…) run on whatever port you started
       ;; them on, and you may run two of a kind at once — those differ ONLY by
       ;; port, so the port goes IN THE URL (or `/provider strata 8081`, or the
       ;; URL field of a model in the Models dialog). See §4.6.
       ;; :provider "strata"   ; the kind, kept alongside a non-default url so the
       ;;                      ; picker/status line can name the server you chose
       }

 :soul {:path "SOUL.md"}         ; persistent instructions (system prompt)
 :skills {:roots ["skills"]}     ; skill pack roots (optional)
 :babashka {:command "bb"}       ; always-on run_babashka tool (optional tweak)
 :edn-store {:root "edn-store"}  ; memory_* tools + MCP persistence + /jobs (optional)
 :projects {:dir "~/grog-projects"} ; per-project context home (GUI/projects)
 :appearance {:chat {:font-family "Monospaced" :font-size 18}
              :terminal {:font-family "Monospaced" :font-size 18}}

 :cli {:chat-history-turns 12
       :chat-show-thinking true
       :chat-stream-live-thinking true
       :format-markdown true}}
```

> The **GUI** dialogs write most of this back to the same file: **⚙ Settings** is
> appearance and voice (it shows the model in force, with one button that opens
> the model editor), and **Models** (`Ctrl+M`) is where models live — the named
> list, the URL of each one, and its model id (§4.7).

---

## 4. Secrets

grog stores secrets in the **OS keyring** — macOS Keychain, Windows Credential
Manager, Linux Secret Service / KWallet. A secret value is never written to a
file.

> **Rule of thumb:** never paste a real API key/token/password into `grog.edn`
> or a committed file. Use `/secret`.

### 4.1 How it works

1. **OS keyring** (preferred):
   - **Linux**: Secret Service (GNOME Keyring / KWallet) via D-Bus.
   - **Windows**: Credential Manager.
   - **macOS**: Keychain.
A secret VALUE is never written to a file — a key sitting in a config file is a
leak waiting to be committed or synced. If no keyring is reachable — headless
Linux, SSH/WSL, containers — a `/secret set` **fails with a clear error**; fix
the keyring and retry. grog gives the keyring ~4s before declaring it
unreachable.

Only secret **names** ever appear in `.edn`: `secret-ledger.edn` (names grog
created, so `/reset` can remove exactly those) and `:secrets {:accounts […]}` in
`grog.edn`.

### 4.2 Built-in accounts

| Account | Purpose |
|---|---|
| `BRAVE_SEARCH_API` | Brave Search API subscription token (used by `brave_web_search`) |
| `LLM_API_KEY` | API key for OpenAI-compatible providers (OpenRouter, OpenAI, Groq, …) used automatically by `:llm` requests |

### 4.3 Setting / listing / removing secrets

In **chat** (GUI or terminal):

```text
/secret                       # list accounts + set/unset status (values never printed)
/secret set LLM_API_KEY sk-...   # store a key (OS keyring)
/secret BRAVE_SEARCH_API ...     # alternative form — same effect
/secret rm LLM_API_KEY           # remove from the keyring
/secret backend                  # show the keyring status
```

**In the GUI**: there is no key field in the GUI — use `/secret …` in the composer
(the GUI's composer is a chat input, so the command works there too).

> `Startup banner` or `/secret` show the keyring status. If it says the keyring
> is unreachable, make sure a Secret Service (e.g. `gnome-keyring-daemon`) is
> running and unlocked in your session.

### 4.4 Custom secret accounts

**The secret store is yours — any name you invent works.** `/secret set`,
`/secret rm`, and any config field that names an account (e.g. an Odoo
instance's `:password-secret "ODOO_PROD_PASSWORD"`) accept whatever name you
type; nothing has to be declared first.

```text
/secret set ODOO_PROD_PASSWORD <value>     # works with no config change at all
```

Declaring an account under `:secrets :accounts` in `grog.edn` is **optional**
and purely cosmetic — it only adds a description to the `/secret` listing:

```clojure
{:secrets {:accounts [{:account "GITHUB_TOKEN"      :description "GitHub PAT"}
                      {:account "PHANTOM_X"         :description "Another API token"}]}}
```

`with_api_key` has its own explicit allowlist (`:with-api-key
:allowed-secrets`) — *that* is the real gate for what the model may pass by
name, and it too accepts names you never declared:

```clojure
{:with-api-key {:allowed-secrets ["ODOO_PROD_PASSWORD"]}}
```

### 4.5 Configuring an API-keyed provider

Use `/secret set LLM_API_KEY <key>` then leave `:api-key` **unset** in
`grog.edn`. grog reads `LLM_API_KEY` automatically.

That's all you need for any OpenAI-compatible cloud provider.

### 4.6 Local servers — and running more than one of a kind

A local entry (`ollama`, `lmstudio`, `strata`, `vllm`, `litellm`, …) is a server
**you** run. The catalogue ships only that kind's **default** port, so a server
running anywhere else needs its port supplied. Two Strata launches at once is the
case this exists for: they differ by nothing but the port.

Three ways to set one, all writing the same `:llm :url`:

| How | Example |
|---|---|
| Console | `/provider strata 8081` — the id plus the port |
| Explicit base URL | `/provider url http://localhost:8081/v1`, or the **url** field of a model in the Models dialog |
| Config file | `:llm {:url "http://localhost:8081/v1" :model "…"}` |

There is deliberately **no separate port control** in the GUI. The port is part of
the URL, so the URL is one text field and the port is typed inside it. An earlier
design kept the port in its own box next to a provider combo, and that split is
what produced "I changed the port and Apply didn't stick": two fields, two
writers, and a button wired to only one of them.

Once a local server is on a non-default port, grog keeps the **provider id** with
the url (`:llm :provider "strata"`) so the picker can show your actual choice
rather than re-guessing it — a bare `localhost` cannot say which kind of server is
there. The status line then labels it **`strata:8081 · <model>`**; on the default
port the port is left off, so an ordinary 8080 Strata still reads `strata ·
<model>`. The id is only trusted when it points at the same **host** as `:llm
:url`, so a stale one can never mislabel a transport you have since switched to.

Local servers advertise whatever model their launch loaded, so a transport pick
asks the running endpoint for the real id (`/v1/models`) instead of writing a
placeholder. If nothing answers, the model is left **blank** — honest, and you
name it — rather than paired with an id that cannot work.

### 4.7 Models: one name, three fields, one dialog

Open it with **`Ctrl+M`**, the **model** line in the status bar, or the toolbar's
**model** button. It is a dialog of its own — not a tab inside Settings — because
a model is a decision of its own.

**A model is a NAME.** `workstation` means "that qwen on port 8081", and the name
is the unit of every operation: you pick by name, edit by name, delete by name.
Under the name sit exactly three fields, written in one step:

| Field | What it is |
|---|---|
| **name** | how you pick it — here, or with `/model <name>` |
| **url** | the endpoint, **port included** (`http://localhost:8081/v1`) |
| **model** | the id that endpoint answers to |

What the dialog does:

- **The list is the front door.** It opens on your named models, one row each,
  with a dot for whether the endpoint has actually been checked (grey = never
  asked, green = it answers, red = nothing there), the reported context window,
  and **check / edit / ✕** on every row.
- **Pick is one click.** Clicking the name resolves it to its `[url model]` pair
  and writes both — a pick never changes the id while leaving the endpoint alone.
- **Add your own first.** `+ add a model (your own URL)` opens a blank form: name
  it, type its URL, and press **list ↻** to see what it serves. The shipped
  endpoints sit below it as a shortcut, not as the only door — a gateway, a relay
  or a server on an odd port has to be typeable.
- **The baked-in endpoints are a TEMPLATE, not a menu.** "Start from a known
  endpoint" pre-fills the URL (and, for a running server, the id it answers to).
  Every pre-filled value is an editable field; you then rename it to something
  you will remember.
- **The model field shows what that URL really serves.** Press **list ↻** (or
  Enter in the URL field) and the endpoint's own ids appear under the field, to
  click. A click fills the field; **Save** is what writes.
- **Errors appear in the form**, in the spine's own words, beside the field at
  fault — and a rejected save keeps your half-typed text instead of wiping it.

From the console, the same list:

```text
/model                    current model + the numbered recents + the named list
/model <n>                switch to entry n of that list
/model <name>             a NAMED model — its url AND model, together
/model <id>               a raw model id on the configured transport
/model new strata [name]  create a named model from a baked-in endpoint
/model rm workstation     delete a named model
```

A **recents** shortlist sits under the named list: models you ran but never named,
each carrying its own endpoint. Picking one switches the transport with it; save
it as a name to keep it.

> A named model lives in `grog.edn` under `:llm :choices`, so the console, the
> GUI and a `project.edn` `:model` pin all read the same record. A project that
> pins its own model says so in the dialog — picking a name there changes the
> global default, not that project's pin.

---

## 5. Launching on Windows vs Linux

### Windows

- **Config home**: `~/.config/grog/grog.edn` (or `$XDG_CONFIG_HOME/grog/grog.edn`,
  or `$GROG_CONFIG_HOME`).
- **Log files**: the client writes one log per running instance —
  `<base>.<pid>.log` — so concurrent instances never share a file. `<base>` is
  `$GROG_LOG` (a trailing `.log` is stripped) else `~/grog`
  (`%USERPROFILE%\grog` on Windows). The client captures its own messages **and**
  the backend's stdout/stderr into the file, so `tail -f ~/grog.<pid>.log` shows
  live output. On startup the oldest instance logs are pruned, keeping
  `$GROG_UI_LOG_KEEP` (default 5). Set `GROG_LOG_WIRE=1` to also record the raw
  driver traffic (NDJSON — off by default; high volume).
- **Profile & cache**: Chromium's private store (HTTP/GPU/Code caches, Local
  Storage) is kept in `%LOCALAPPDATA%\grog` — **Local** AppData, not Roaming, so
  it never bloats a roaming/domain profile. This is not grog's config; that
  stays in `~/.config/grog`.
- **If the chat shows `ECA connect failed`**: grog resolves the `eca` binary
  **at every launch** — `:eca :binary` (if set), else PATH, else
  `~/.vscode/extensions` (`editor-code-assistant.eca-*`, the VS Code extension),
  else scoop shims, npm global, and last the installer's own copy in
  `%LOCALAPPDATA%\eca`. An ECA you install later is therefore picked up on the
  next launch, and the installer never downloads a second copy when one is
  already present. If it's somewhere else, set `:eca :binary` to the full path
  (e.g. `C:\Users\you\scoop\shims\eca.exe`).
- **Secret backend**: Windows Credential Manager.
- **Tip**: set `GROG_CONFIG_HOME` once in the user environment if you'd rather
  keep config in a single folder you copy around.

### Linux

- **Config home**: `~/.config/grog/grog.edn` (or `$XDG_CONFIG_HOME/grog/grog.edn`).
- **Launch**: `scripts/grog-client` (add `--console` to stream the log here).
- **Log files**: same rule as Windows — `<base>.<pid>.log`, where `<base>` is
  `$GROG_LOG` else `~/grog`.
- **Secret backend**: Secret Service if a desktop session with a keyring is
  running; without one, `/secret` reports the keyring unreachable.

---

## 6. Advanced: env-var interpolation

You can reference environment variables inside `grog.edn` values with
`${NAME}` or `${NAME:-default}`:

```clojure
{:llm {:url "https://openrouter.ai/api/v1"
       :model "openrouter/deepseek/deepseek-v4-flash-0731"
       :api-key "${OPENROUTER_API_KEY}"}}
```

This is a convenient alternative to `/secret`, especially for scripts/CI. It
works for the `:llm` block and MCP/Odoo/IMAP environment config.

> Prefer `/secret` for interactive machines — it avoids the key sitting in yet
> another file and never prints it to logs.

---

## 7. Troubleshooting

| Symptom | What to check |
|---|---|
| "grog: LLM request failed: connection refused" | Is your local server running? Is `:llm :url` correct (`http://localhost:11434/v1`)? |
| A local server on a non-default port is not reached | Put the port in its URL — `/provider strata 8081`, or edit the model's **url** field in the Models dialog (§4.6). The catalogue only knows each kind's default port. |
| Two local servers of the same kind (two Strata launches) | Give each one its own URL/port; the status line then reads `strata:8081` so you can tell which one you are on. |
| 401 Unauthorized | Missing/incorrect key. `/secret set LLM_API_KEY …` or set `:api-key`, then restart. |
| "No API key found" in failure hint | Same as above. |
| "OS keyring did not respond within 4s" | On Linux: ensure a Secret Service is running and unlocked; `/secret` reports the keyring unreachable until it is. |
| `with_api_key` says "secret not set in store" | Store it with `/secret set <ACCOUNT> …` and confirm the account is in `:with-api-key :allowed-secrets`. |
| Config changes "not applied" | grog reads config at startup. After editing `grog.edn`, restart (or use `/soul reload` where applicable). |
| Windows: no config found | Your user `grog.edn` should be under `C:\Users\you\.config\grog\` (not AppData). |
| Where are my secrets? | Only in the OS keyring — `/secret backend` reports it; values are never written to a file. |
| Where's my client log? | `<base>.<pid>.log` — base is `$GROG_LOG` else `~/grog` (`%USERPROFILE%\grog`). The newest file is the running instance; the client prints its path at startup (`[grog-client] log file: …`). |

---

## 8. Reference: chat commands for config/secrets

| Command | Effect |
|---|---|
| `/secret` | list accounts + set/unset status (values never printed) |
| `/secret set <KEY> <value>` | store a secret (OS keyring) |
| `/secret <KEY> <value>` | short alias for the above |
| `/secret rm <KEY>` | delete a secret |
| `/secret backend` | show keyring status |
| `/soul show/path/add/reload` | manage the persistent instructions (SOUL.md) |
| `/model` | show current model / URL / profiles |
| `/model reset` | revert session override to config file values |
| `/model <name>` / `/model <profile>` | switch provider/model/profile for the session |
| `/provider` | list known providers, marking the current transport |
| `/provider <id>` | set the transport (strata, vllm, ollama, openrouter, …) |
| `/provider <id> <port>` | set it AND repoint it at `<port>` — a second local server (e.g. a Strata launch on 8081) |
| `/provider url <base>` | set an explicit base URL |
| `/project`, `/project <name>` | switch per-project context home |
| `/reset` / `/reset --yes` | factory reset — plan first, then perform (see §10) |

---

## 9. Related files generated per machine

In your config home (every OS: `~/.config/grog` — Windows is the same):

| File | Purpose |
|---|---|
| `grog.edn` | your config (user-level) |
| `secret-ledger.edn` | names of secrets **grog itself wrote** (never values) — lets `/reset` remove exactly those |
| `eca-config.generated.json` | merged ECA config (JSON — consumed by the ECA binary, which parses JSON) |
| `odoo-instances.edn` / `imap-accounts.edn` | MCP metadata for Odoo/IMAP servers (EDN; `.json` also read) |
| `approved-tools.edn` | permanently-allowed tool names |

Per-instance **client logs** live outside the config home: `<base>.<pid>.log` in
`~/grog` by default (`%USERPROFILE%\grog` on Windows), or wherever `$GROG_LOG`
points — see §5.

---

## 10. Factory reset (the airbag)

If the state gets too wonky, reset grog to a fresh install:

- in chat: `/reset` prints a **plan** and changes nothing; `/reset --yes` performs it;
- from a shell: `clojure -M -m grog.reset [--yes]`.

It removes **only what grog made**:

| Removed | Note |
|---|---|
| the getting-started project (`ouroboros`) | copied into the backup dir first |
| the config home (`~/.config/grog`) | MOVED into the backup — `grog.edn` survives there |
| saved session configs | they live under the config home |
| credentials **grog created** | read from `secret-ledger.edn` (§9) and deleted from the keyring |

Nothing is destroyed outright: everything is moved into
`~/.config/grog.reset-<timestamp>/`. **Secret values are never exported** — the
keyring entries grog wrote are deleted, so re-enter them with `/secret`. Your other
projects (including a developer's own `grog` project) are never touched.
**Restart grog afterwards** — it comes up on defaults, re-seeds `ouroboros`, and
the client writes a fresh starter `grog.edn` into the (new) config home.

---

*See also:* the `README.md` (full feature listing, quick start, MCP) and
`grog.edn.example` (annotated template with every option) — placed in your
config home on first run, and at `resources/config.examples/` in a source tree.