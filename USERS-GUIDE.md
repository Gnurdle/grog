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

Secrets and generated files (ECA config, IMAP/Odoo metadata, approved-tools,
`secrets.edn`) live **in the same config home directory**, so moving to a new
machine is "copy one folder + set one env var".

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

---

## 3. Full annotated config

Copy `resources/config.examples/grog.edn.example` to your user config path, then
edit. Everything is optional except `:llm :url` and `:llm :model`. For a complete
starter bundle (imaging tessdata, gitlab, odoo, imap, secrets prototypes), copy
the whole `resources/config.examples/` directory into your config home — see its
`README.md`.

```clojure
{:llm {:url "http://localhost:11434/v1"      ; OpenAI-compatible /v1 endpoint
       :model "qwen3.5:9b"                    ; model id
       ;; optional: inline key or ${ENV} reference (prefer keyring / file store)
       ;; :api-key "${LLM_API_KEY}"
       ;; optional: connection/read timeouts (seconds)
       ;; :conn-timeout-sec 60
       ;; :socket-timeout-sec 300
       ;; optional: token budget / tool-result cap
       ;; :max-context-tokens 200000
       ;; :max-tool-result-chars 50000
       ;; optional: temperature, max output tokens
       ;; :temperature 0.7
       ;; :max-tokens 4096
       ;; optional: named presets for `/model`
       ;; :profiles {:local  {:url "http://localhost:11434/v1" :model "qwen2.5-coder:7b-instruct"}
       ;;            :remote {:url "https://openrouter.ai/api/v1" :model "moonshotai/kimi-k2.7-code"}}
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

> The **GUI Settings** dialog (Models / Appearance / Terminal / General tabs) can
> edit most of this for you and writes it back to the same file.

---

## 4. Secrets

grog stores secrets in a **secret store** — the OS keyring when available, with
an automatic **file fallback** for headless/remote systems.

> **Rule of thumb:** never paste a real API key/token/password into `grog.edn`
> or a committed file. Use `/secret`.

### 4.1 How it works

1. **OS keyring** (preferred):
   - **Linux**: Secret Service (GNOME Keyring / KWallet) via D-Bus.
   - **Windows**: Credential Manager.
   - **macOS**: Keychain.
2. **File fallback** (automatic): when the keyring is unsupported or
   unreachable — headless Linux, SSH/WSL sessions, containers — grog reads and
   writes `<config-home>/secrets.edn`. The file is created with owner-only
   permissions where the OS supports it and lives **outside the repo**
   (default `~/.config/grog/secrets.edn` on every OS; Windows uses the same
   `.config` path).

You never choose which backend — grog tries the keyring first and falls back
automatically if it can't answer in ~4s.

### 4.2 Built-in accounts

| Account | Purpose |
|---|---|
| `BRAVE_SEARCH_API` | Brave Search API subscription token (used by `brave_web_search`) |
| `LLM_API_KEY` | API key for OpenAI-compatible providers (OpenRouter, OpenAI, Groq, …) used automatically by `:llm` requests |

### 4.3 Setting / listing / removing secrets

In **chat** (GUI or terminal):

```text
/secret                       # list accounts + set/unset status (values never printed)
/secret set LLM_API_KEY sk-...   # store a key (both backends)
/secret BRAVE_SEARCH_API ...     # alternative form — same effect
/secret rm LLM_API_KEY           # remove from keyring and file store
/secret file                     # show the fallback file path
/secret backend                  # show which backend is active
```

**In the GUI**: Settings → Models → **Set default API key…** (or Clear API key).

> Detecting which backend is in use is easy: `Startup banner` or `/secret`.
> If it shows "file fallback" and you expected the keyring, make sure a Secret
> Service (e.g. `gnome-keyring-daemon`) is running / unlocked in your session.

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
- **Secret backend**: Windows Credential Manager; falls back to
  `~/.config/grog/secrets.edn` automatically if needed.
- **Tip**: set `GROG_CONFIG_HOME` once in the user environment if you'd rather
  keep config in a single folder you copy around.

### Linux

- **Config home**: `~/.config/grog/grog.edn` (or `$XDG_CONFIG_HOME/grog/grog.edn`).
- **Launch**: `scripts/grog-client` (add `--console` to stream the log here).
- **Log files**: same rule as Windows — `<base>.<pid>.log`, where `<base>` is
  `$GROG_LOG` else `~/grog`.
- **Secret backend**: Secret Service if a desktop session with a keyring is
  running; otherwise falls back to `~/.config/grog/secrets.edn`.

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
| 401 Unauthorized | Missing/incorrect key. `/secret set LLM_API_KEY …` or set `:api-key`, then restart. |
| "No API key found" in failure hint | Same as above. |
| "OS keyring did not respond within 4s" | On Linux: ensure a Secret Service is running. The file store takes over automatically. |
| `with_api_key` says "secret not set in store" | Store it with `/secret set <ACCOUNT> …` and confirm the account is in `:with-api-key :allowed-secrets`. |
| Config changes "not applied" | grog reads config at startup. After editing `grog.edn`, restart (or use `/soul reload` where applicable). |
| Windows: no config found | Your user `grog.edn` should be under `C:\Users\you\.config\grog\` (not AppData). |
| Where's my `secrets.edn`? | `/secret file` prints its absolute path. |
| Where's my client log? | `<base>.<pid>.log` — base is `$GROG_LOG` else `~/grog` (`%USERPROFILE%\grog`). The newest file is the running instance; the client prints its path at startup (`[grog-client] log file: …`). |

---

## 8. Reference: chat commands for config/secrets

| Command | Effect |
|---|---|
| `/secret` | list accounts + set/unset status (values never printed) |
| `/secret set <KEY> <value>` | store a secret (keyring or file fallback) |
| `/secret <KEY> <value>` | short alias for the above |
| `/secret rm <KEY>` | delete a secret |
| `/secret file` | show the fallback store path |
| `/secret backend` | show active backend |
| `/soul show/path/add/reload` | manage the persistent instructions (SOUL.md) |
| `/model` | show current model / URL / profiles |
| `/model reset` | revert session override to config file values |
| `/model <name>` / `/model <profile>` | switch provider/model/profile for the session |
| `/project`, `/project <name>` | switch per-project context home |

---

## 9. Related files generated per machine

In your config home (every OS: `~/.config/grog` — Windows is the same):

| File | Purpose |
|---|---|
| `grog.edn` | your config (user-level) |
| `secrets.edn` | fallback secret store (owner-only perms) |
| `eca-config.generated.json` | merged ECA config (JSON — consumed by the ECA binary, which parses JSON) |
| `odoo-instances.edn` / `imap-accounts.edn` | MCP metadata for Odoo/IMAP servers (EDN; `.json` also read) |
| `approved-tools.edn` | permanently-allowed tool names |

Per-instance **client logs** live outside the config home: `<base>.<pid>.log` in
`~/grog` by default (`%USERPROFILE%\grog` on Windows), or wherever `$GROG_LOG`
points — see §5.

---

*See also:* the `README.md` (full feature listing, quick start, MCP) and
`resources/config.examples/grog.edn.example` (annotated template with every
option).