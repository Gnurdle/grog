# Grog

Terminal chat for **Ollama** with a real **tool loop**: the model calls tools, Grog runs them on your machine, and the turn ends when you get a plain-text answer or hit the round limit. Behavior is shaped by **`grog.edn`** and optional **SOUL.md**—no code changes required.

---

## Contents

- [Overview](#overview)
- [What you get](#what-you-get)
- [Tools](#tools)
- [Chat commands](#chat-commands)
- [Configuration](#configuration)
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
| **Skills** | Packaged `skill.edn` + **SKILL.md** dirs; the model can list, read, create, and update skills. |
| **Babashka** | Optional **`run_babashka`** for short scripted side effects (`bb` on `PATH`). |

Symlinks **inside** the workspace are followed for tools; `..` cannot escape the configured root.

---

## What you get

### Core runtime

- **Ollama `/api/chat`** with **tool calling** (use a model that supports tools).
- **Multi-step rounds** — `:cli :chat-tool-loop-limit` (default **32**, max 1000).
- **Session history** — `:cli :chat-history-turns` or **`/clear`** / **`/fresh`**.
- **Streaming** — optional live thinking + streamed answer; **Esc** cancels mid-stream (JLine TTY).
- **Markdown** — optional ANSI rendering (tables, code fences, etc.).
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
| **Memory** | `memory_save`, `memory_load`, `memory_list_keys`, `memory_create_namespace`, `memory_delete` — needs `:edn-store {:root "…"}` |
| **Scripts** | `run_babashka` — needs `:babashka {:enabled true}` and **`bb`** on `PATH` |

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
| `/secret` | Keyring **`grog`** — list/set keys (values never printed) |
| `/shell` | `sh -lc` under workspace cwd, or interactive subshell |
| `/soul` | Path, append, reload |
| `@path` | Inline files into the prompt (whitespace-separated tokens) |

---

## Configuration

Config merges in order:

1. `resources/grog.edn` — defaults + comments  
2. `~/.config/grog/grog.edn` — user overrides  
3. `./grog.edn` — project overrides  

**Required:** `:ollama {:url … :model …}`.

**Optional:** workspace, `:soul`, `:skills`, `:edn-store`, `:oracle`, Brave / `:with-api-key`, `:babashka`, `:cli` (history, thinking, streaming, markdown, tool limit).

### Persistent text

- **SOUL.md** (`:soul {:path …}`) — prepended as a **system** message every request.  
- **Skills** — `<root>/<id>/skill.edn` + **SKILL.md**; preview with **`/skills <id>`**.

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

 ;; Optional: xAI Grok (or any OpenAI-style chat/completions URL) for the `oracle` tool
 ;; Keyring: ORACLE_API_KEY — /secret ORACLE_API_KEY <token>
 :oracle {:url "https://api.x.ai/v1/chat/completions"
          :model "grok-3"
          :max-tokens 4096
          :temperature 0.5}

 ;; Optional: Babashka scripts (`bb` on PATH)
 :babashka {:enabled true}

 ;; Optional: Brave web search — keyring BRAVE_SEARCH_API; uncomment:
 ;; :with-api-key {:allowed-secrets ["BRAVE_SEARCH_API"]
 ;;                :allowed-url-prefixes ["https://api.search.brave.com/"]}

 :cli {:chat-history-turns 96
       :chat-show-thinking true
       :chat-stream-live-thinking true
       :chat-stream-live-content true
       :format-markdown true
       ;; :chat-tool-loop-limit 32  ;; default 32; raise for longer tool loops
       }}
```

---

## Quick start

1. Run **Ollama**; pull a **tool-capable** model and name it in `grog.edn`.  
2. **JDK 21+** (see `deps.edn` / `:run` `:jvm-opts` if needed).  
3. Copy or edit **`grog.edn`** — `resources/grog.edn` has annotated examples.

```bash
cd grog
clojure -M:run chat
```

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
| `clojure -M:run chat` | Interactive chat |
| `clojure -M:run "your message"` | One-shot reply, then exit |
| `clojure -M:run help` | Print help |

---

*Built with Cursor-assisted Clojure; if you haven’t tried the pairing on a real project, it’s worth a spin.*
