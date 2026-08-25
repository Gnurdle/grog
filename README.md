# Grog

Terminal chat for **OpenAI-compatible LLMs** with a real **tool loop**: the model calls tools, Grog runs them on your machine, and the turn ends when you get a plain-text answer (or an error). There is **no tool-round cap by default**; you can set `:cli :chat-tool-loop-limit` only if you want an explicit ceiling. Behavior is shaped by **`grog.edn`** and optional **SOUL.md**—no code changes required.

---

## Contents

- [Overview](#overview)
- [What you get](#what-you-get)
- [Tools](#tools)
- [Chat commands](#chat-commands)
- [Configuration](#configuration)
  - [MCP servers](#mcp-servers)
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

- **OpenAI-compatible `/v1/chat/completions`** with **tool calling** (use a model that supports tools).
- **Multi-step rounds** — **unlimited by default** (runs until the model returns text without `tool_calls`). Set `:cli :chat-tool-loop-limit` to a **positive integer** only if you want a hard stop. With thinking enabled: banner is **`── thinking k ──`** when unlimited, **`── thinking k/n ──`** when a limit is set.
- **Session history** — `:cli :chat-history-turns` or **`/clear`** / **`/fresh`**.
- **Streaming** — optional live thinking; answer tokens stream in cyan only when **`:format-markdown` is false**. With default Markdown rendering, the reply is buffered for the round so GFM tables and layout render correctly, but you can set **`:cli :chat-stream-live-markdown true`** to render blocks as they close (paragraphs and fenced code stream; tables still wait for a blank line). Set **`:cli :chat-stream-live-content false`** to buffer plain text too until the round completes.
- **Markdown** — optional ANSI rendering (tables, code fences, etc.); replies are buffered for the round when Markdown is on so GFM pipe tables draw as box tables. `<image-png>path.png</image-png>` tags open images in a Swing viewer.
- **On-the-fly model switching** — `/model` shows the current model/URL and any `:llm :profiles`; `/model <profile>` activates a named profile; `/model <model-name>` switches to a model for the session (e.g. `qwen2.5-coder:7b-instruct`); `/model reset` reverts to the config file values.
- **One-shot** — `clojure -M:run "…"` uses the same tool stack, then exits.
- **GUI** — `clojure -M:gui` (or `./grog-ui`) opens a Swing desktop app with streaming transcript, Settings, export, and an integrated terminal.

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
| `/paste` | Multi-line input mode (blank line submits; Ctrl-D cancels) |
| `/project`, `/project <name>` | Projects: context from the project home `~/grog-projects/<name>/` (notes/dialog/state); `. = *` marks the active project. The active project's dir is also the agent workspace + shell cwd. |
| `/job`, `/jobs` | Project job queue in the project home (`~/grog-projects/<proj>/jobs/`): **`add` \| `list` \| `next` \| `status`** |
| `/chron` | Show whether the **`:chron`** scheduler is running |
| `/secret` | Keyring **`grog`** — list/set keys (values never printed) |
| `/shell` | `sh -lc` under the active project cwd (fallback: repo root), or interactive subshell |
| `/mcp` | MCP server list: **help** \| **status** \| **show** \| **load** \| **save** \| **reload** \| **set** *edn* |
| `/soul` | **show** \| **path** \| **add** *text* \| **reload** — SOUL.md management |
| `@path` | Inline files into the prompt (whitespace-separated tokens) |

---

## Configuration

Config merges in order:

1. Code defaults — see `resources/grog.edn.example` for a full annotated template  
2. `~/.config/grog/grog.edn` — user overrides  
3. `./grog.edn` — project overrides  

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
- **Runs only during** **`clojure -M:run chat`** / **`clojure -M:gui`** (started after the banner, stopped when you leave chat).
- Each task: **`:id`**, **`:instruction`** (or **`:prompt`**), plus **`:every-minutes`** or **`:interval-seconds`** (minimum **15** seconds if using seconds).
- Output goes to **stderr** with a visible banner (it can interleave with typing). If a **project** is active, chron may append **`[chron] …`** turns to **`thread.edn`**. Last run summaries can live under **`grog-chron/last-run/…`** in the store.

### `:jobs` config

- **`:jobs {:max-thread-turns N}`** — how many prior dialog turns to inject for **jobs** and **chron** (default **40**).

---

## Example `grog.edn`

Save as **`./grog.edn`** next to your project or under **`~/.config/grog/grog.edn`**. Adjust model names and paths; merge order is `resources/` → user config → this file.

**Secrets** (Brave, `with_api_key`) live in the OS keyring — set with **`/secret <ACCOUNT> <value>`** in chat, never in this file.

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
clojure -M:run chat
```

(`clojure -M:run chat` starts interactive chat; `clojure -M:run "message"` is one-shot; `clojure -M:gui` opens the Swing GUI.)

At the prompt:

```text
chat> /help
```

### Optional: Brave Search

1. [Brave Search API](https://brave.com/search/api/) subscription.  
2. Store token: service **`grog`**, account **`BRAVE_SEARCH_API`** — e.g. **`/secret BRAVE_SEARCH_API <token>`** in chat, or your OS secret UI (e.g. GNOME Seahorse).  
3. Grog uses [java-keyring](https://github.com/javakeyring/java-keyring).

### Optional: LLM API key

For cloud providers (OpenAI, OpenRouter, Groq, etc.), store the key as **`LLM_API_KEY`** in the OS keyring (or use `:api-key` with `${LLM_API_KEY}` env-var interpolation in `grog.edn`).

---

## CLI usage

| Command | Effect |
| --- | --- |
| `clojure -M:run chat` | Interactive chat |
| `clojure -M:run "your message"` | One-shot reply, then exit |
| `clojure -M:run help` | Print help |
| `clojure -M:gui` | Swing GUI (or `./grog-ui` / `grog-ui.bat`) |

---

*Built with Cursor-assisted Clojure; if you haven’t tried the pairing on a real project, it’s worth a spin.*
