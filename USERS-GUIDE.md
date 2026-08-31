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
| 1 | bundled defaults (`resources/grog.edn.example`) | built-in defaults / template |
| 2 | **user `grog.edn`** (see table below) | your personal setup |
| 3 | `./grog.edn` in the run directory | project/override config |

### Where is the user `grog.edn`?

The location is **platform-aware** and can be overridden with **`GROG_CONFIG_HOME`**:

| OS | Default user config path |
|---|---|
| Linux / macOS | `${XDG_CONFIG_HOME:-~/.config}/grog/grog.edn` → usually `~/.config/grog/grog.edn` |
| Windows | `%APPDATA%\grog\grog.edn` → usually `C:\Users\you\AppData\Roaming\grog\grog.edn` |
| Any (override) | `$GROG_CONFIG_HOME/grog.edn` |

Secrets and generated files (ECA config, IMAP/Odoo metadata, approved-tools,
`secrets.edn`) live **in the same config home directory**, so moving to a new
machine is "copy one folder + set one env var".

> Legacy Linux users: `~/.config/grog/grog.edn` is still honored even when
> `$GROG_CONFIG_HOME` points elsewhere.

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
   clojure -M:gui          # Swing GUI (or ./grog-ui)
   ```

4. **Talk to it.** If your provider needs an API key, store it (section 4) before
   chatting.

---

## 3. Full annotated config

Copy `resources/grog.edn.example` to your user config path, then edit. Everything
is optional except `:llm :url` and `:llm :model`.

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
   (default `~/.config/grog/secrets.edn` on Linux, `%APPDATA%\grog\secrets.edn`
   on Windows).

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
/secret BRAVE_SEARCH_API ...     # legacy form — same effect
/secret rm LLM_API_KEY           # remove from keyring and file store
/secret file                     # show the fallback file path
/secret backend                  # show which backend is active
```

**In the GUI**: Settings → Models → **Set default API key…** (or Clear API key).

> Detecting which backend is in use is easy: `Startup banner` or `/secret`.
> If it shows "file fallback" and you expected the keyring, make sure a Secret
> Service (e.g. `gnome-keyring-daemon`) is running / unlocked in your session.

### 4.4 Custom secret accounts

You can teach grog about additional accounts (for the `with_api_key` tool) by
adding them to `:secrets :accounts` in `grog.edn`:

```clojure
{:secrets {:accounts [{:account "GITHUB_TOKEN"      :description "GitHub PAT"}
                      {:account "PHANTOM_X"         :description "Another API token"}]}
 :with-api-key {:allowed-secrets ["GITHUB_TOKEN"]}}
```

Then store them with `/secret set GITHUB_TOKEN <value>`. `with_api_key` will
only accept account names listed in `:with-api-key :allowed-secrets`.

### 4.5 Configuring an API-keyed provider

Use `/secret set LLM_API_KEY <key>` then leave `:api-key` **unset** in
`grog.edn`. grog reads `LLM_API_KEY` automatically.

That's all you need for any OpenAI-compatible cloud provider.

---

## 5. Launching on Windows vs Linux

### Windows

- **Config home**: `%APPDATA%\grog\grog.edn` (or `$GROG_CONFIG_HOME`).
- **Launch the GUI**: double-click `grog-ui.bat` (or `clojure -M:gui`), which
  captures debug output to `grog-ui.log`.
- **Secret backend**: Windows Credential Manager; falls back to
  `%APPDATA%\grog\secrets.edn` automatically if needed.
- **Tip**: set `GROG_CONFIG_HOME` once in the user environment if you'd rather
  keep config in a single folder you copy around.

### Linux

- **Config home**: `~/.config/grog/grog.edn` (or `$XDG_CONFIG_HOME/grog/grog.edn`).
- **Launch**: `./grog-ui` (or `clojure -M:gui`).
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
| Windows: no config found | Your user `grog.edn` should be under `%APPDATA%\grog\`. |
| Where's my `secrets.edn`? | `/secret file` prints its absolute path. |

---

## 8. Reference: chat commands for config/secrets

| Command | Effect |
|---|---|
| `/secret` | list known accounts + set/unset status (values never printed) |
| `/secret set <KEY> <value>` | store a secret (keyring or file fallback) |
| `/secret <KEY> <value>` | legacy alias for the above |
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

In your config home (Linux `~/.config/grog`, Windows `%APPDATA%\grog`):

| File | Purpose |
|---|---|
| `grog.edn` | your config (user-level) |
| `secrets.edn` | fallback secret store (owner-only perms) |
| `eca-config.generated.json` | merged ECA config (JSON — consumed by the ECA binary, which parses JSON) |
| `odoo-instances.edn` / `imap-accounts.edn` | MCP metadata for Odoo/IMAP servers (EDN; legacy `.json` still read) |
| `approved-tools.edn` | permanently-allowed tool names |

---

*See also:* the `README.md` (full feature listing, quick start, MCP) and
`resources/grog.edn.example` (annotated template with every option).