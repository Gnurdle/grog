# Grog

Terminal chat for **Ollama** with a real **tool loop**: the model calls tools, Grog runs them on your machine, and the turn ends when you get a plain-text answer (or an error). There is **no tool-round cap by default**; you can set `:cli :chat-tool-loop-limit` only if you want an explicit ceiling. Behavior is shaped by **`grog.edn`** and optional **SOUL.md**—no code changes required.

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
| **Local-first** | Workspace files, skills, and memory live on disk; remote calls are explicit (Brave, oracle, `with_api_key`, Babashka when enabled). |
| **Modest hardware** | Useful with smaller models (e.g. Qwen3.5-class on ~8 GB VRAM); tool use still buys you a lot. |
| **Oracle** | Optional stronger remote model (`:oracle` + keyring); the stack is wired so the agent can escalate when stuck—see SOUL for policy. |
| **Projects** | `/project` ties **`memory_*`** tools and dialog logging into per-project trees—iterable, restartable workstreams. |
| **Jobs** | With **`:edn-store`**, **`/jobs`** enqueues goals per project; Grog runs the full tool loop with **SOUL + project dialog** loaded, writes **findings** under `grog-jobs/` in the store, and appends to **`thread.edn`**. |
| **Chron** | **`:chron`** runs scheduled **instruction** strings on a timer **while chat is running** (stderr banner, same Ollama+tools stack); respects **active project** and thread context when set. |
| **Skills** | Packaged `skill.edn` + `SKILL.md` directories; the model can list, read, create, and update skills. |
| **Babashka** | Optional **`run_babashka`** for short scripted side effects (`bb` on `PATH`). |

Symlinks **inside** the workspace are followed for tools; `..` cannot escape the configured root.

---

## What you get

### Core runtime

- **Ollama `/api/chat`** with **tool calling** (use a model that supports tools).
- **Multi-step rounds** — **unlimited by default** (runs until the model returns text without `tool_calls`). Set `:cli :chat-tool-loop-limit` to a **positive integer** only if you want a hard stop. With thinking enabled: banner is **`── thinking k ──`** when unlimited, **`── thinking k/n ──`** when a limit is set.
- **Session history** — `:cli :chat-history-turns` or **`/clear`** / **`/fresh`**.
- **Streaming** — optional live thinking; answer tokens stream in cyan only when **`:format-markdown` is false**. With default Markdown rendering, the reply is buffered for the round so GFM tables and layout render correctly. Set **`:cli :chat-stream-live-content false`** to buffer plain text too until the round completes.
- **Markdown** — optional ANSI rendering (tables, code fences, etc.); replies are buffered for the round when Markdown is on so GFM pipe tables draw as box tables.
- **One-shot** — `clojure -M:run "…"` uses the same tool stack, then exits.

### Workspace

**`:workspace {:default-root "…"}`** — root for relative tool paths and SOUL resolution.

---

## Tools

Active set depends on `grog.edn`. Use **`/tools`** in chat for the live list and descriptions.

<details>
<summary><strong>Tool reference (click to expand)</strong></summary>

| Area | Tools |
| --- | --- |
| **Files** | `read_workspace_file`, `write_workspace_file`, `read_workspace_dir`, `write_workspace_png`, `crop_workspace_image` |
| **Documents** | `read_office_document`, `read_pdf_document`, `ocr_pdf_document`, `analyze_pdf_line_drawings` |
| **Web** | `brave_web_search` — Brave Search API key in OS keyring |
| **Stronger model** | `oracle` — OpenAI-style chat completions; `:oracle` + **`ORACLE_API_KEY`** |
| **HTTP + secrets** | `with_api_key` — allowlisted keyring names + optional URL prefixes |
| **Skills** | `list_skills`, `read_skill`, `save_skill`, `delete_skill` — needs `:skills {:roots […]}` |
| **Memory** | `memory_save`, `memory_load`, `memory_list_keys`, `memory_namespaces`, `memory_create_namespace`, `memory_delete` — needs `:edn-store {:root "…"}` |
| **Scripts** | `run_babashka` — needs `:babashka {:enabled true}` and **`bb`** on `PATH` |
| **MCP** | **`:edn-store`** + **`/mcp`** or **`mcp_*`** tools; persisted **`grog-mcp/servers.edn`** (project-scoped); after **`mcp_reload`**, tools **`<id>_<tool>`** |

</details>

---

## Chat commands

These are **user** commands, not model tools.

| Command | What it does |
| --- | --- |
| `/help` | Full in-app help |
| `/clear`, `/fresh` | Clear session history |
| `/tools`, `/skills` | Inspect tools / skill packs |
| `/project`, `/project <name>` | Projects: memory namespaces + `Projects/<name>/dialog/thread.edn` |
| `/jobs` | **`add` \| `list` \| `next` \| `status`** — project job queue in edn-store (`grog-jobs/`); needs **active project** + **`:edn-store`** |
| `/chron` | Show whether the **`:chron`** scheduler is running |
| `/secret` | Keyring **`grog`** — list/set keys (values never printed) |
| `/shell` | `sh -lc` under workspace cwd, or interactive subshell |
| `/mcp` | MCP server list in edn-store: **help** \| **status** \| **show** \| **load** \| **save** \| **reload** \| **set** *edn* |
| `/soul` | Path, append, reload |
| `@path` | Inline files into the prompt (whitespace-separated tokens) |

---

## Configuration

Config merges in order:

1. `resources/grog.edn` — defaults + comments  
2. `~/.config/grog/grog.edn` — user overrides  
3. `./grog.edn` — project overrides  

**Required:** `:ollama {:url … :model …}`.

**Optional:** workspace, `:soul`, `:skills`, `:edn-store`, `:oracle`, Brave / `:with-api-key`, `:babashka`, **`:chron`**, **`:jobs`**, `:cli` (history, thinking, streaming, markdown, optional **`chat-tool-loop-limit`** only).

### MCP servers

MCP is **not** configured in `grog.edn`. With **`:edn-store`**, the server list lives under the store as **`grog-memory/grog-mcp/servers.edn`**, or **`grog-memory/Projects/<project>/grog-mcp/servers.edn`** when **`/project`** is active (same scoping idea as **`memory_*`**). Use **`/mcp`** in chat or the Ollama tools **`mcp_config_load`**, **`mcp_config_save`**, **`mcp_servers_set`**, **`mcp_reload`**. Subprocesses **do not** start when chat opens; call **`mcp_reload`** (or **`/mcp reload`**) after you have a valid declaration. Ollama then sees remote tools as **`<id>_<tool>`** (longest `:id` prefix wins).

- **Filesystem (Node):** `@modelcontextprotocol/server-filesystem` via **`npx`** — example entry: **`{:id "fs" :command ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/abs/path"]}`**.
- **DataScript (Clojure):** [xlisp/datascript-mcp-server](https://github.com/xlisp/datascript-mcp-server) — clone the repo, set **`:cwd`** to that root, and run **`clojure -M -m datascript-mcp.core`** (needs **`clojure`** on `PATH` and a first-run dependency download). Tools include **`init_db`**, **`query`**, **`load_db`**, **`add_data`**, etc.

### Persistent text

- **SOUL.md** (`:soul {:path …}`) — prepended as a **system** message every request.  
- **Skills** — `<root>/<id>/skill.edn` + `SKILL.md`; preview with **`/skills <id>`**.

---

## Jobs and chron

Both use the **same agent stack** as normal chat (`run-tool-loop-on-messages`) and the **edn-store** tree under your workspace.

### Jobs (`/jobs`)

- **Requires:** **`:edn-store`** and **`/project <name>`** (active project).
- **Queue:** `grog-memory/Projects/<project>/grog-jobs/queue.edn`.
- **Findings:** `grog-memory/Projects/<project>/grog-jobs/findings-<job-id>.edn`.
- **Commands:** `/jobs add <goal>`, `/jobs list`, `/jobs next`, `/jobs status` (see **`/help`**).

Each run loads **SOUL, skills, oracle hints, and recent project dialog** into the message list before the job prompt.

### Chron (`:chron`)

- **Requires:** **`:chron {:enabled true :tasks […]}`** in `grog.edn`.
- **Runs only during** **`clojure -M:chat`** / **`clojure -M:run chat`** (started after the banner, stopped when you leave chat).
- Each task: **`:id`**, **`:instruction`** (or **`:prompt`**), plus **`:every-minutes`** or **`:interval-seconds`** (minimum **15** seconds if using seconds).
- Output goes to **stderr** with a visible banner (it can interleave with typing). If a **project** is active, chron may append **`[chron] …`** turns to **`thread.edn`**. Last run summaries can live under **`grog-chron/last-run/…`** in the store.

### `:jobs` config

- **`:jobs {:max-thread-turns N}`** — how many prior dialog turns to inject for **jobs** and **chron** (default **40**).

---

## Example `grog.edn`

Save as **`./grog.edn`** next to your project or under **`~/.config/grog/grog.edn`**. Adjust model names and paths; merge order is `resources/` → user config → this file.

**Secrets** (Brave, oracle, `with_api_key`) live in the OS keyring — set with **`/secret <ACCOUNT> <value>`** in chat, never in this file.

```clojure
{:workspace {:default-root "."}

 ;; Required: local Ollama (tool-capable model)
 :ollama {:url "http://localhost:11434"
          :model "qwen3.5:4b"}

 :soul {:path "SOUL.md"}
 :skills {:roots ["skills"]}

 ;; Optional: structured memory + project dialog trees (path under workspace)
 :edn-store {:root "edn-store"}

 ;; Optional: periodic checks while chat is running (stderr banner + Ollama + tools)
 ;; :chron {:enabled true
 ;;         :tasks [{:id "heartbeat" :every-minutes 60 :instruction "Short status check; use memory_* if useful."}]}

 ;; Optional: dialog turns loaded for /jobs and chron (default 40)
 ;; :jobs {:max-thread-turns 40}

 ;; Optional: xAI Grok (or any OpenAI-style chat/completions URL) for the `oracle` tool
 ;; Keyring: ORACLE_API_KEY — /secret ORACLE_API_KEY <token>
 :oracle {:url "https://api.x.ai/v1/chat/completions"
          :model "grok-3"
          :max-tokens 4096
          :temperature 0.5}

 ;; Optional: Babashka scripts (`bb` on PATH)
 :babashka {:enabled true}

 ;; Optional: MCP — not in this file; needs :edn-store, then /mcp or mcp_* tools (see README "MCP servers")

 ;; Optional: Brave web search — keyring BRAVE_SEARCH_API; uncomment:
 ;; :with-api-key {:allowed-secrets ["BRAVE_SEARCH_API"]
 ;;                :allowed-url-prefixes ["https://api.search.brave.com/"]}

 :cli {:chat-history-turns 96
       :chat-show-thinking true
       :chat-stream-live-thinking true
       :chat-stream-live-content true
       :format-markdown true
       ;; :chat-tool-loop-limit 500  ;; optional safety cap only; omit for unlimited tool rounds
       }}
```

---

## Quick start

1. Run **Ollama**; pull a **tool-capable** model and name it in `grog.edn`.  
2. **JDK 21+** (see `deps.edn` / `:run` `:jvm-opts` if needed).  
3. Copy or edit **`grog.edn`** — `resources/grog.edn` has annotated examples.

```bash
cd grog
clojure -M:chat
```

(`clojure -M:run chat` is equivalent; `:chat` is a shorter alias in `deps.edn`.)

At the prompt:

```text
chat> /help
```

### Optional: Brave Search

1. [Brave Search API](https://brave.com/search/api/) subscription.  
2. Store token: service **`grog`**, account **`BRAVE_SEARCH_API`** — e.g. **`/secret BRAVE_SEARCH_API <token>`** in chat, or your OS secret UI (e.g. GNOME Seahorse).  
3. Grog uses [java-keyring](https://github.com/javakeyring/java-keyring).

### Optional: Oracle

Set `:oracle` with `:url` (e.g. `…/v1/chat/completions`) and `:model`; put the API key in the keyring as **`ORACLE_API_KEY`** (`/secret` in chat).

---

## CLI usage

| Command | Effect |
| --- | --- |
| `clojure -M:chat` | Interactive chat (shorthand alias) |
| `clojure -M:run chat` | Same as `-M:chat` |
| `clojure -M:run "your message"` | One-shot reply, then exit |
| `clojure -M:run help` | Print help |

---

*Built with Cursor-assisted Clojure; if you haven’t tried the pairing on a real project, it’s worth a spin.*
