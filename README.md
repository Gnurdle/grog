# GROG - Gnurdle Reasoning Orchestration Gateway (shameless forced acronym)

# Why
I started this project about the time Openclaw came out, out of curiosity as to 
discovering what was possible in that era.

Specifically, it was a discovery platform to see:
- learn a bit about how this ecosystem works
- how well models worked with the clojure/babashka ecosystem
- learn how MCPs work, and build a few
- come up with a paradigm for daily work, which for me is fragmented delerious
  demand-driven multitasking
- was tired of seeing everything agent generated doing things with python
  and/or javascript.

The code in this repo is about 99.9% untouched by human hands.  It was prompted 
into existance from absolutely nothing, and is maintained and developed similarly

# ECA (https://github.com/editor-code-assistant)
This was something I was using as my daily driver in VS-code for AI assisted coding.

Since I noticed that I was spending too much time trying to ape its behavior,
it eventually occured to me to simply co-opt it rather then try to clone it.

This proved to be a satisfying and fruitful path, and how it works as of
now.  GROG is a wrapper that communicates with ECA - it starts the ECA
server with a generated configuration file and uses it for LLM traffic.


A **GUI chat that wraps ECA** (a real agent) with **OpenAI-compatible LLMs** and a
**real tool loop**: the model calls tools, grog runs them on your machine (via local
MCP servers for babashka, search, web fetch, RSS, project memory, Odoo, IMAP, …),
and a turn ends when you get a plain-text answer (or an error). There is **no
tool-round cap by default** in the grog/ECA loop; the legacy jobs/chron loop stays
uncapped unless you set `:cli :chat-tool-loop-limit`. Behavior is shaped by
**`grog.edn`**, optional **SOUL.md**, and a generated ECA config — no code changes
required.

---

## Contents

- [Overview](#overview)
- [What you get](#what-you-get)
- [Tools](#tools)
- [Chat commands](#chat-commands)
- [Configuration](#configuration)
  - [MCP servers](#mcp-servers)
- [Users guide (config + secrets)](#users-guide-config--secrets)
- [Jobs and chron](#jobs-and-chron)
- [Example `grog.edn`](#example-grogedn)
- [Quick start](#quick-start)
- [CLI usage](#cli-usage)

---

## Overview

| Topic | Detail |
| --- | --- |
| **Local-first** | Project files, skills, and memory live on disk; remote calls are explicit (Brave, `with_api_key`). |
| **Modest hardware** | Useful with smaller models (e.g. Qwen3.5-class on ~8 GB VRAM); tool use still buys you a lot. |
| **GUI** | Swing desktop app (`clojure -M:gui` or `./grog-ui`): streaming transcript, model picker, **project picker**, appearance settings, integrated terminal, export. |
| **Project-centric** | Grog is **always in a project**. Active project chosen via the GUI picker or `/project`; its context loads (notes/dialog/state), its directory is the agent's workspace (ECA `workspaceFolders` = project dir) and the shell cwd — the source tree is no longer special. |
| **Jobs** | With **`:edn-store`**, **`/jobs`** enqueues goals per project; Grog runs the full tool loop with **SOUL + project dialog** loaded, writes **findings** under `grog-jobs/` in the store, and appends to **`thread.edn`**. |
| **Chron** | **`:chron`** runs scheduled **instruction** strings on a timer **while chat is running** (stderr banner, same LLM+tools stack); respects **active project** and thread context when set. |
| **Skills** | Packaged `skill.edn` + `SKILL.md` directories; the model can list, read, create, and update skills. |
| **Babashka** | Always-on **`run_babashka`** for short scripted Clojure transforms (`bb` on `PATH`). |

Tool paths are taken as given — absolute, or relative to the repo/conversation root. There is no workspace-root containment check.

---

## What you get

### Core runtime

- **OpenAI-compatible `/v1/chat/completions`** with **tool calling** (use a model that supports tools) — either local (Ollama) or remote (OpenRouter etc.).
- **Multi-step rounds** — **unlimited by default** in grog/ECA (the agent loops until it returns text without `tool_calls`). The legacy jobs/chron loop is also uncapped unless you opt into `:cli :chat-tool-loop-limit`.
- **Rich GUI transcript** — streaming assistant/thinking/tool cards, markdown, GFM tables, collapsible thinking, drag-to-select copy, and HTML preview/export.
- **Session history** — `:cli :chat-history-turns` plus **`/clear`** / **`/fresh`**.
- **Thinking streamed live** into collapsible sections; answer renders as markdown as it completes. (The old console ANSI streaming lives in the one-shot/background loop only.)
- **On-the-fly model switching** — via the GUI model picker / footer, **`/eca-model <name>`**, or `:eca :model` in `grog.edn` (the active ECA model). The console `:llm :profiles` presets still exist for the one-shot/background loop. If grog can't find the `eca` server binary on Windows, set **`:eca :binary`** in `grog.edn` to its full path (PATH / scoop shims / npm global / `~/.vscode/extensions` are auto-searched).
- **One-shot** — `clojure -M:run "…"` uses the same tool stack, then exits (prints to stdout/stderr).
- **GUI** — `clojure -M:gui` (or `./grog-ui`) opens a Swing desktop app with streaming transcript, Settings, integrated terminal, and export. This is the primary chat surface (the old console chat was removed).

### Repo root

Paths in tool calls are absolute or relative to the repo root. ECA's `workspaceFolders` (declared at connect) points at the repo root, so ECA's own file tools operate there too. The old `:workspace {:default-root …}` containment layer has been removed.

---

## Tools

Active set depends on `grog.edn`. Use **`/tools`** in chat for the live list and descriptions.

<details>
<summary><strong>Tool reference (click to expand)</strong></summary>

| Area | Tools |
| --- | --- |
| **Files** | `read_office_document`, `read_pdf_document`, `ocr_pdf_document`, `analyze_pdf_line_drawings` |
| **Web** | `brave_web_search` — Brave Search API key in OS keyring |
| **HTTP + secrets** | `with_api_key` — allowlisted keyring names + optional URL prefixes |
| **Skills** | `list_skills`, `read_skill`, `save_skill`, `delete_skill` — needs `:skills {:roots […]}` |
| **Memory** | `assoc_store/get/keys/delete/search` — SQLite kv-store, **per active project** (`~/grog-projects/<proj>/state/mem.db`); named stores too (`<name>.db` beside it) |
| **Scripts** | `run_babashka` — always enabled; needs **`bb`** on `PATH` |
| **MCP** | **`/mcp`** or **`mcp_*`** tools; persisted **`grog-mcp/servers.edn`** (project-scoped); after **`mcp_reload`**, tools **`<id>_<tool>`** |

</details>

---

## Chat commands

These are **user** commands, not model tools.

| Command | What it does |
| --- | --- |
| `/help` | Full in-app help |
| `/clear`, `/fresh` | Clear session history |
| `/tools`, `/skills` | Inspect tools / skill packs |
| `/eca-model <name>` | Switch the running ECA model (GUI chat) |
| `/project`, `/project <name>` | Projects: context from the project home `~/grog-projects/<name>/` (notes/dialog/state); `. = *` marks the active project. The active project's dir is also the agent workspace + shell cwd. |
| `/job`, `/jobs` | Project job queue in the project home (`~/grog-projects/<proj>/jobs/`): **`add` \| `list` \| `next` \| `status`** |
| `/tasks` | **Per-project tasks** (`~/grog-projects/<proj>/tasks.edn`), always user-instigated: **`add <title>`** (`|due +Nh` / `HH:mm` / `@epochms`, `|every Nh` for recurring), **`done <id>`**, **`rm <id>`**, **`due`**. Ask "what needs doing?" for reminders. |
| `/chron` | Show whether the **`:chron`** scheduler is running |
| `/secret` | Keyring **`grog`** (or file fallback) — `set <KEY> <value>` / `rm <KEY>` / `file` / `backend`; values never printed |
| `/shell` | `sh -lc` under the active project cwd (fallback: repo root), or interactive subshell |
| `/mcp` | MCP server list: **help** \| **status** \| **show** \| **load** \| **save** \| **reload** \| **set** *edn* |
| `/soul` | **show** \| **path** \| **add** *text* \| **reload** — SOUL.md management |
| `@path` | Inline files into the prompt (whitespace-separated tokens) |

> In the GUI, multi-line input is handled with **Ctrl+Enter** (Enter sends; Shift+Enter
> inserts a newline) — `/paste` is a console-loop carryover and not needed there.

---

## Configuration

Config merges in order:

1. Code defaults — see `resources/grog.edn.example` for a full annotated template  
2. `grog.edn` in your **user config home** (platform-aware):
   - Every OS: `${XDG_CONFIG_HOME:-~/.config}/grog/grog.edn` (Windows: `C:\Users\you\.config\grog\grog.edn`)
   - Override: `$GROG_CONFIG_HOME/grog.edn`
   - Legacy `~/.config/grog/grog.edn` is still honored if present.
3. `./grog.edn` — project overrides (in the run directory)  

**Required:** `:llm {:url "…/v1" :model "…"}`. For local Ollama use `:url "http://localhost:11434/v1"`.

**Optional:** `:llm` also accepts `:max-context-tokens` (drop oldest non-system messages before each request; default 200000, set `nil` to disable), `:max-tool-result-chars` (truncate oversized tool outputs; default 50000, set `nil` to disable), `:temperature`, `:max-tokens`, `:api-key` (inline or `${LLM_API_KEY}` env-var interpolation; prefer OS keyring `LLM_API_KEY`), `:conn-timeout-sec` (default 60), `:socket-timeout-sec` (default 300), `:debug-payload` / `:debug-response` (print to stderr), `:provider-name` (human-readable label), and `:extra-payload` (provider-specific fields merged into every request — e.g. OpenRouter `{:transforms ["middle-out"]}` for context compression).

**Optional:** `:soul`, `:skills`, `:edn-store`, **`:projects`** (project home, default `~/grog-projects`), Brave / `:with-api-key`, `:babashka`, **`:chron`**, **`:jobs`**, `:appearance`, `:cli` (history, thinking, streaming, markdown, optional **`chat-tool-loop-limit`** only).

### MCP servers

MCP is **not** configured in `grog.edn`. With **`:edn-store`**, the server list lives under the store as **`grog-memory/grog-mcp/servers.edn`**, or **`grog-memory/Projects/<project>/grog-mcp/servers.edn`** when **`/project`** is active (same scoping idea as **`memory_*`**). Use **`/mcp`** in chat or the tools **`mcp_config_load`**, **`mcp_config_save`**, **`mcp_servers_set`**, **`mcp_reload`**. On load/reload, each server is started briefly, `tools/list` is fetched and cached to `tools-cache.edn`, and processes stop. A server process starts lazily on the first matching `tools/call` and stays up until `stop-all!` (chat exit or config reload). The LLM sees remote tools as **`<id>_<tool>`** (longest `:id` prefix wins).

- **Filesystem (Node):** `@modelcontextprotocol/server-filesystem` via **`npx`** — example entry: **`{:id "fs" :command ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/abs/path"]}`**.
- **DataScript (Clojure):** [xlisp/datascript-mcp-server](https://github.com/xlisp/datascript-mcp-server) — clone the repo, set **`:cwd`** to that root, and run **`clojure -M -m datascript-mcp.core`** (needs **`clojure`** on `PATH` and a first-run dependency download). Tools include **`init_db`**, **`query`**, **`load_db`**, **`add_data`**, etc.

### Persistent text

- **SOUL.md** (`:soul {:path …}`) — prepended as a **system** message every request.  
- **Skills** — `<root>/<id>/skill.edn` + `SKILL.md`; preview with **`/skills <id>`**.

### Appearance

Fonts and colors for chat and terminal are configurable via **`:appearance`** in `grog.edn` or the GUI Settings panel. Example:

```clojure
:appearance {:chat {:font-family "Monospaced"
                    :font-size 18
                    :user {:rgb [165 138 25]}
                    :thinking {:rgb [55 165 95]}
                    :answer {:rgb [100 220 255]}
                    :tool-call {:rgb [255 0 255]}}
             :terminal {:font-family "Monospaced"
                        :font-size 18}}
```

The GUI also supports zoom (Ctrl+Shift+Plus/Minus) and transcript export (Ctrl+E).

---

## Users guide (config + secrets)

For a step-by-step walkthrough covering where `grog.edn` lives on Linux vs
Windows, how to store secrets (OS keyring + automatic file fallback for
headless/remote setups), and how to configure any OpenAI-compatible provider,
see **[`USERS-GUIDE.md`](USERS-GUIDE.md)**.

---

## Jobs and chron

Both use the **same agent stack** as normal chat (`run-tool-loop-on-messages`) and the **edn-store** tree in your repo.

### Jobs (`/jobs`)

- **Requires:** **`:edn-store`** and **`/project <name>`** (active project).
- **Queue:** `grog-memory/Projects/<project>/grog-jobs/queue.edn`.
- **Findings:** `grog-memory/Projects/<project>/grog-jobs/findings-<job-id>.edn`.
- **Commands:** `/jobs add <goal>`, `/jobs list`, `/jobs next`, `/jobs status` (see **`/help`**).

Each run loads **SOUL, skills, and recent project dialog** into the message list before the job prompt.

### Chron (`:chron`)

- **Requires:** **`:chron {:enabled true :tasks […]}`** in `grog.edn`.
- **Runs while the app is running** — started with the GUI (`clojure -M:gui` / `./grog-ui`) or the one-shot program; stopped when you quit. (The old `clojure -M:run chat` console loop was removed.)
- Each task: **`:id`**, **`:instruction`** (or **`:prompt`**), plus **`:every-minutes`** or **`:interval-seconds`** (minimum **15** seconds if using seconds).
- Output goes to **stderr** with a visible banner (it can interleave with typing). If a **project** is active, chron may append **`[chron] …`** turns to **`thread.edn`**. Last run summaries can live under **`grog-chron/last-run/…`** in the store.

### `:jobs` config

- **`:jobs {:max-thread-turns N}`** — how many prior dialog turns to inject for **jobs** and **chron** (default **40**).

---

## Example `grog.edn`

Save as **`./grog.edn`** next to your project or under your user config home (every OS: `~/.config/grog/grog.edn` — Windows uses the same `.config` path — or `$GROG_CONFIG_HOME`). Adjust model names and paths; merge order is `resources/` → user config → this file.

**Secrets** (Brave, `with_api_key`, LLM key) normally live in the **OS keyring** — set with **`/secret set <ACCOUNT> <value>`** in chat, never in this file. If no OS secret backend is available (headless Linux, SSH/WSL, containers), grog automatically falls back to **`secrets.edn`** in your config home (created with owner-only permissions, outside the repo). `with_api_key` is gated by **`:with-api-key {:allowed-secrets […]}`**; additional named secrets that can be set with `/secret` are declared under **`:secrets {:accounts […]}`**.

```clojure
{:soul {:path "SOUL.md"}

 ;; Required: OpenAI-compatible chat/completions endpoint (Ollama, OpenRouter, OpenAI, etc.)
 :llm {:url "http://localhost:11434/v1"
       :model "qwen3.5:4b"
       ;; Optional: inline API key (supports ${ENV} interpolation). Prefer OS keyring LLM_API_KEY via /secret.
       ;; :api-key "${LLM_API_KEY}"
       ;; Optional: token budget. Oldest non-system messages are dropped before each request.
       ;; Rough estimate (~4 chars/token). Set below your provider's context limit.
       ;; :max-context-tokens 200000
       ;; Optional: cap individual tool result length. Longer results are truncated with a note.
       ;; :max-tool-result-chars 50000
       ;; Optional: temperature (provider default when omitted).
       ;; :temperature 0.7
       ;; Optional: max output tokens (provider default when omitted).
       ;; :max-tokens 4096
       ;; Optional: connection/read timeouts in seconds (defaults: conn 60, socket 300).
       ;; :conn-timeout-sec 60
       ;; :socket-timeout-sec 300
       ;; Optional: print full request/response to stderr for debugging.
       ;; :debug-payload true
       ;; :debug-response true
       ;; Optional: human-readable label for status lines.
       ;; :provider-name "My Ollama"
       ;; Optional: named presets for /model. Each profile overrides :llm keys for the session.
       ;; :profiles {:local    {:url "http://localhost:11434/v1" :model "qwen2.5-coder:7b-instruct" :api-key nil}
       ;;            :big      {:model "qwen3.5:32b"}
       ;;            :openai   {:url "https://api.openai.com/v1" :model "gpt-4o" :api-key "${OPENAI_API_KEY}"}}
       }

 :skills {:roots ["skills"]}

 ;; Optional: structured memory + project dialog trees (absolute or repo-root-relative path)
 :edn-store {:root "edn-store"}

 ;; Optional: periodic checks while chat is running (stderr banner + LLM + tools)
 ;; :chron {:enabled true
 ;;         :tasks [{:id "heartbeat" :every-minutes 60 :instruction "Short status check; use memory_* if useful."}]}

 ;; Optional: dialog turns loaded for /jobs and chron (default 40)
  ;; :jobs {:max-thread-turns 40}
 
 
  ;; Babashka is always enabled — only the command is configurable (default `bb`)
  :babashka {;; :command "bb"
             }

 ;; Optional: MCP — not in this file; needs :edn-store, then /mcp or mcp_* tools (see README "MCP servers")

 ;; Optional: Brave web search — keyring BRAVE_SEARCH_API; uncomment:
 ;; :with-api-key {:allowed-secrets ["BRAVE_SEARCH_API"]
 ;;                :allowed-url-prefixes ["https://api.search.brave.com/"]}

 ;; Optional: Appearance theming (fonts, colors). Edit via the GUI Settings panel or directly.
 ;; :appearance {:chat {:font-family "Monospaced" :font-size 18
 ;;                     :user {:rgb [165 138 25]} :thinking {:rgb [55 165 95]}
 ;;                     :answer {:rgb [100 220 255]} :tool-call {:rgb [255 0 255]}}
 ;;              :terminal {:font-family "Monospaced" :font-size 18}}

 :cli {:chat-history-turns 96
       :chat-show-thinking true
       :chat-stream-live-thinking true
       :chat-stream-live-content true
       :chat-stream-live-markdown false
       :format-markdown true
       ;; :chat-tool-loop-limit 500  ;; optional safety cap only; omit for unlimited tool rounds
       }}
```

---

## Quick start

1. Run an **OpenAI-compatible server** (e.g., Ollama at `/v1`); pull a **tool-capable** model and name it in `grog.edn`.  
2. **JDK 21+** (see `deps.edn` / `:run` `:jvm-opts` if needed).  
3. Copy `resources/grog.edn.example` to `grog.edn` and edit — it has annotated examples of every option.

```bash
cd grog
clojure -M:gui          # or ./grog-ui
```

(`clojure -M:gui` / `./grog-ui` opens the Swing GUI; `clojure -M:run "your message"` is a one-shot reply. The old `clojure -M:run chat` console loop was removed in favor of the GUI.)

At the prompt in the GUI:

```text
/help
```

### Optional: Brave Search

1. [Brave Search API](https://brave.com/search/api/) subscription.  
2. Store token: service **`grog`**, account **`BRAVE_SEARCH_API`** — e.g. **`/secret set BRAVE_SEARCH_API <token>`** in chat, or your OS secret UI (e.g. GNOME Seahorse). On headless/remote setups it is stored in the config-home file store automatically.  
3. Grog uses [java-keyring](https://github.com/javakeyring/java-keyring).

### Optional: LLM API key

For cloud providers (OpenAI, OpenRouter, Groq, etc.), store the key as **`LLM_API_KEY`** (or use `:api-key` with `${LLM_API_KEY}` env-var interpolation in `grog.edn`):

- **GUI:** Settings → Models → *Set default API key…* (and *Clear API key*).
- **Chat:** `/secret set LLM_API_KEY <key>`.

---

## CLI usage

| Command | Effect |
| --- | --- |
| `clojure -M:run "your message"` | One-shot reply, then exit |
| `clojure -M:run help` | Print help |
| `clojure -M:gui` | Swing GUI (or `./grog-ui` / `grog-ui.bat`) — the primary chat surface |
| `clojure -M:run chat` | Removed — run the GUI instead (prints a pointer and exits) |

---

*Built with Cursor-assisted Clojure; if you haven’t tried the pairing on a real project, it’s worth a spin.*
