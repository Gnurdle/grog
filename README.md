# GROG - Gnurdle Reasoning Orchestration Gateway (shameless forced acronym)

> **One-line pitch:** because the job isn't a chat session — it's a working relationship
> that persists. Grog turns an LLM into a companion that lives inside your projects,
> keeps your tools and rules, remembers what you taught it across reboots, and does real
> work on your machine — recurring, on demand. It's modular (projects depend on reusable
> skills), so a teammate inherits the capability without inheriting your secrets. You
> stop re-teaching an agent every morning and start watching a system compound what it
> knows. Your assistant, your rules, your state — portable, persistent, genuinely yours.

> **New here?** Start with the [documentation map](#documentation-map), and read
> [`operating-model.md`](operating-model.md) for what this whole thing is *for*
> (the friend, projects, skills, and how a teammate inherits the capability).

# ECA (https://github.com/editor-code-assistant)
GROG is a wrapper that communicates with ECA - it starts the ECA
server with a generated configuration file and uses it for LLM traffic.


A **GUI chat that wraps ECA** (a real agent) with **OpenAI-compatible LLMs** and a
**real tool loop**: the model calls tools, grog runs them on your machine (via local
MCP servers for babashka, search, web fetch, RSS, project memory, Odoo, IMAP, …),
and a turn ends when you get a plain-text answer (or an error). There is **no
tool-round cap by default** in the grog/ECA loop; the jobs/chron loop stays
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
- [Documentation map](#documentation-map)

---

## Documentation map

If you're trying to answer a question, start here. The repo intentionally splits docs by
audience so nobody has to read everything.

| I want to… | Read this |
|---|---|
| Understand the *point* — friend / projects / skills / transfer | [`operating-model.md`](operating-model.md) |
| See every built-in MCP server + its tools (the full surface area) | [`mcp-servers.md`](mcp-servers.md) |
| Configure grog (config, secrets, providers) | [`USERS-GUIDE.md`](USERS-GUIDE.md) |
| Enable the grog MCP servers inside ECA on a teammate's box | [`eca-users-guide.md`](eca-users-guide.md) |
| Build a reusable skill | [`skills/SKILL-TEMPLATE.md`](skills/SKILL-TEMPLATE.md) |
| Know the agent's rules of behavior | [`SOUL.md`](SOUL.md) |
| Understand the internals | [`state.md`](state.md) |

The rest of this README is the mechanics reference (commands, config, examples).

## Overview

| Topic | Detail |
| --- | --- |
| **Local-first** | Project files, skills, and memory live on disk; remote calls are explicit (Brave, `with_api_key`). |
| **Modest hardware** | Useful with smaller models (e.g. Qwen3.5-class on ~8 GB VRAM); tool use still buys you a lot. |
| **Desktop app** | The desktop app in `clients/web` (`scripts/grog-client` / `scripts/grog-client.bat`): streaming transcript, model picker, **project picker / tabs**, integrated view, markdown rendering, trust (YOLO) toggle, settings. |
| **Project-centric** | Grog is **always in a project**. Active project chosen via the GUI picker or `/project`; its context loads (notes/dialog/state), and its directory is the agent's workspace (ECA `workspaceFolders` = project dir) and the shell cwd. |
| **Jobs** | With **`:edn-store`**, **`/jobs`** enqueues goals per project; Grog runs the full tool loop with **SOUL + project dialog** loaded, writes **findings** under `grog-jobs/` in the store, and appends to **`thread.edn`**. |
| **Chron** | **`:chron`** runs scheduled **instruction** strings on a timer **while chat is running** (stderr banner, same LLM+tools stack); respects **active project** and thread context when set. |
| **Skills** | Packaged `skill.edn` + `SKILL.md` directories; the model can list, read, create, and update skills. |
| **Babashka** | Always-on **`run_babashka`** for short scripted Clojure transforms (`bb` on `PATH`). |

Tool paths are taken as given — absolute, or relative to the repo/conversation root. There is no workspace-root containment check.

---

## What you get

### Core runtime

- **OpenAI-compatible `/v1/chat/completions`** with **tool calling** (use a model that supports tools) — either local (Ollama) or remote (OpenRouter etc.).
- **Multi-step rounds** — **unlimited by default** in grog/ECA (the agent loops until it returns text without `tool_calls`). The jobs/chron loop is also uncapped unless you opt into `:cli :chat-tool-loop-limit`.
- **Rich GUI transcript** — streaming assistant/thinking/tool cards, markdown, GFM tables, collapsible thinking, drag-to-select copy, and HTML preview/export.
- **Session history** — `:cli :chat-history-turns` plus **`/clear`** / **`/fresh`**.
- **Thinking streamed live** into collapsible sections; answer renders as markdown as it completes. (Console ANSI streaming applies to the one-shot/background loop only.)
- **On-the-fly model switching** — via the GUI model picker, **`/eca-model <name>`**, or `:eca :model` in `grog.edn` (the active ECA model). The console `:llm :profiles` presets apply to the one-shot/background loop. If grog can't find the `eca` server binary on Windows, set **`:eca :binary`** in `grog.edn` to its full path (PATH / scoop shims / npm global / `~/.vscode/extensions` are auto-searched).
- **One-shot** — `clojure -M:run "…"` uses the same tool stack, then exits (prints to stdout/stderr).
- **Desktop app** — the desktop app in `clients/web`, launched with `scripts/grog-client` (Linux) or `scripts/grog-client.bat` (Windows), opens the streaming transcript, model picker, project tabs, settings, and integrated view. This is the primary chat surface. See `doc/linux-quick-start.md` / `doc/windows-quick-start.md`.

### Repo root

Paths in tool calls are absolute or relative to the repo root. ECA's `workspaceFolders` (declared at connect) points at the repo root, so ECA's own file tools operate there too.

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
| `/tasks` | **Per-project tasks** (`~/grog-projects/<proj>/tasks.edn`), always user-instigated: **`add <title>`** (`|due +Nh` / `HH:mm` / `@epochms`, `|every Nh` for recurrin