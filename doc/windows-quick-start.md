# grog — Windows quick start

Goal: a working grog on Windows in one sitting, with the MCP tool suite served
from a single jar (correct tool schemas — see "Why the jar" below).

```
grog-ui.bat  →  jvm (Swing GUI, current client; the Electron client is next)
                 ├─ eca.exe            (pinned; spawned as a child, JSON-RPC over stdio)
                 └─ java -cp grog-mcp.jar clojure.main -m grog_mcp.main --server <id>   ×13
                        └─ optional: tesseract.exe (OCR), soffice.exe (office render)
```

## 0. Prerequisites

| Need | Check | Notes |
|---|---|---|
| **Clojure CLI** | `clojure -Sdescribe` | required — the client runs from source today |
| **JDK/JRE 17+** | `java -version` | required **for the MCP jar** (21 preferred) |
| **Git for Windows** (bash) | `bash --version` | required — MCP entries run as `bash -lc …` |
| **ECA** | `eca --version` | required — the assistant grog drives; **pin the version** |
| *optional* Babashka | `bb --version` | unlocks `run_babashka` and `.bb` scripts |
| *optional* Tesseract | `where tesseract` | unlocks OCR (tessdata probed at `%ProgramFiles%\Tesseract-OCR\tessdata`) |
| *optional* LibreOffice | `where soffice` | unlocks office render/convert |
| *optional* Poppler | `where pdftoppm` | unlocks PDF→PNG rendering |

Missing optional tools are not errors: the corresponding tools simply aren't
useful. (`grog doctor` — planned — will probe and report this.)

## 1. Get the code and the jar

```cmd
:: the tree (either a clone, or merged from a transfer bundle)
git clone <your grog-2 remote> C:\Users\%USERNAME%\grog
```

The MCP jar is built once on any machine (it is platform-neutral — only the
keyring loads OS-native code, at runtime):

```cmd
cd C:\Users\%USERNAME%\grog\grog_mcp
clojure -T:build uber      :: → target\grog-mcp-0.1.0.jar   (~74 MB)
clojure -M:test            :: the wire test: asserts tool schemas are OBJECTS
```

…or copy `grog_mcp\target\grog-mcp-0.1.0.jar` from a machine that already built
it. **Wherever it came from, the jar must sit in `grog_mcp\target\`** — that's
where the config generator looks.

## 2. First run

```cmd
cd C:\Users\%USERNAME%\grog
grog-ui.bat
```

Double-clicking also works. The client writes its own log to
`%USERPROFILE%\grog-ui.<pid>.log` (base overridable via `GROG_LOG`).

Config lives at **`C:\Users\%USERNAME%\.config\grog\grog.edn`** (deliberately the
same path as Linux — one code path), and projects under
`C:\Users\%USERNAME%\grog-projects\`.

## 3. Verify it is healthy

Open the newest `grog-ui.<pid>.log` and look for:

1. **MCP entries use the jar** —
   `java … -cp 'C:\Users\<you>\grog\grog_mcp\target\grog-mcp-0.1.0.jar' clojure.main -m grog_mcp.main --server grog-…`
2. **No schema rejection** — none of:
   - `400 … This endpoint's maximum context length is …` (a failover-chain mislabel)
   - `… function.parameters must be a JSON Schema object`
   - `Context overflow detected, pruning tool results and auto-compacting`
3. **A tool call carries its arguments** — ask something that requires one, e.g.
   `odoo what database am I looking at`, or "list the files in this project".
   A tool that reports a *missing argument* ("Missing required :sql",
   "path is required") means the schema bug is back — check the jar.

## 4. Where things live

| Thing | Path |
|---|---|
| config | `C:\Users\<you>\.config\grog\grog.edn` (+ `.config\grog\odoo-instances.edn`, `imap-accounts.edn`, `imaging.edn`) |
| per-instance log | `%USERPROFILE%\grog-ui.<pid>.log` |
| projects (data) | `C:\Users\<you>\grog-projects\<project>\{notes,dialog,state}` |
| MCP jar | `<grog-root>\grog_mcp\target\grog-mcp-0.1.0.jar` |
| MCP sources (fallback) | `<grog-root>\grog_mcp\{src,vendor-src}` |

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Entries read `clojure -M:mcp` instead of `java -cp …` | the jar is missing from `grog_mcp\target\` → the generator falls back to sources. **Restore the jar** (sources also carry the schema fix, but the jar is what removes the Clojure-CLI dependency and the 13 trees) |
| `java` not found | install a JRE 17+ and put it on PATH |
| MCP server fails to start | check `bash --version`; the entries run `bash -lc` |
| "Missing required X" from any tool | schema regression — verify the jar, then `cd grog_mcp && clojure -M:test` |
| OCR tools error about tessdata | install Tesseract, or set `{:tessdata "<dir>"}` in `~/.config/grog/imaging.edn` |
| Office `render` fails | install LibreOffice, or set `{:bin "<soffice path>"}` in `office.edn` |
| Model/provider 400s | check `:llm {:model … :url …}`; **do not** add `:max-tokens` (see below) |

### Why the jar (and why not `:max-tokens`)

MCP tool descriptors were being sent with `function.parameters` as a JSON
*string*; strict providers reject the **entire request** (400) and the model
cannot see parameter names — which is why tools arrived with empty arguments.
The jar's wrapper builds a real JSON-Schema object. `:llm :max-tokens` in
`grog.edn` is **not** the fix and is a no-op on the OpenAI-compatible path —
output limits are ECA's business (`doc/multi-client-layout.md`, Group C1).

## 6. Not in this version

- **Electron client** — planned next (see `doc/clients/web-client-plan.md`); the
  Swing client is functional but **deprecated** (source kept as reference).
- **Per-project config resolution** for memory/odoo/gitlab/imap (tools may see
  the wrong project's store).
- **Multi-user / remote serving** — parked by decision
  (`doc/multi-client-layout.md`, "Decisions 2026-09-28").
