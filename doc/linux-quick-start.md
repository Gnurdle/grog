# grog on Linux — install & verify

grog is an AI coding assistant that runs its tools **on your machine**: files,
shell, office documents, OCR, web search. It is a desktop app — there is no
server to install and nothing to connect to.

## What runs

```
grog                                  the desktop app
 └─ java -cp <root>/target/grog-spine.jar            the backend ("the spine")
     ├─ eca                                           the agent loop
     └─ bash -lc "cd <root>/grog_mcp && java -cp \
                   <root>/grog_mcp/target/grog-mcp-<v>.jar \
                   clojure.main -m grog_mcp.main --server <id>"    ×N   the tool servers
```

The app starts the spine as its child; the spine starts the agent loop, which
starts the tool servers. Closing the window stops all of them.

## 1. Prerequisites

| Need | Check | Notes |
|---|---|---|
| **Java 17+** | `java -version` | **required, on PATH** — runs the spine and the tool servers |
| **bash** | `bash --version` | **required, on PATH** — the tool servers are launched through it |
| **ECA** | `eca --version` | **required** — the agent loop; set `:eca :binary` to it in `grog.edn` |
| **Node.js** | `node --version` | to build and run the app from source |
| Babashka | `bb --version` | `run_babashka`, and `bb dist` |
| LibreOffice | `soffice --version` | office render / convert / present |
| Tesseract | `tesseract --version` | OCR |
| Poppler | `pdftoppm -v` | PDF → PNG |
| ripgrep, jq | `rg --version`, `jq --version` | project search; shell one-liners |

List everything, with versions and install hints:

```sh
bb doctor
clojure -M -m grog.doctor --json
```

Optional tools are just that — a missing one disables the features that need it
and `grog doctor` says which.

## 2. Install

```sh
git clone <remote> ~/grog && cd ~/grog

bb dist                   # build everything: both jars, the app bundle, dist/
bb dist:fast              # same, reusing already-built jars

cd clients/web && npm install   # first time only
```

`bb dist` writes two jars:

| Jar | Location |
|---|---|
| `grog-spine.jar` | `target/` |
| `grog-mcp-<version>.jar` | `grog_mcp/target/` |

To build them alone:

```sh
clojure -T:build spine :version '"0.1.0"'
(cd grog_mcp && clojure -T:build uber :version '"0.1.0"')
```

Both locations matter: the app looks for the spine jar in `target/`, and the
config generator looks for the tool jar in `grog_mcp/target/` (newest match
wins).

## 3. Run

```sh
scripts/grog-client --console     # stream the log to this terminal
scripts/grog-client               # log to $XDG_STATE_HOME/grog/client.log
```

To add grog to the application menu, the dock and the taskbar with its icon:

```sh
./install-desktop.sh
./install-desktop.sh --uninstall
```

Icons, the Windows side, and how to verify an install: `doc/desktop-kit.md`.

## 4. Verify

Open the run log and check, in order:

1. `[grog-client] transport up: child pid <N>` — the backend is running.
2. `[renderer] loaded ok` — the interface loaded.
3. `[grog-debug] ECA started ok` — the agent loop is up.
4. `[MCP] Started MCP server grog-…` ×N — every configured tool server is up.
5. `[LLM-API] Default LLM model '<model>' decision ':config-default-model'` —
   a model is available. `:no-available-model` means no credential is set; add
   one with `/secret set LLM_API_KEY <key>`.
6. Ask for something that needs a tool. The log shows the call **with its
   arguments**: `[TOOLS] Calling tool 'grog-fetch__fetch_url' with args '{…}'`.

## 5. When something is wrong

| Symptom | Check |
|---|---|
| The backend never starts | `java -version` — it must be on PATH. |
| Every tool server reports a spawn failure | `bash --version` — it must be on PATH. |
| Config errors mentioning `clojure.lang.Symbol` | `grog.edn` starts with a byte-order mark. Save it as UTF-8 **without** a BOM. |
| The tool jar is missing | Run `bb dist` (or copy `grog-mcp-<version>.jar` into `grog_mcp/target/`). |
| Leftover `java` processes after quitting | The app stops its whole process tree on exit; if any survive, close them manually. |
| `/doctor` shows a tool as missing | Install it, or ignore it if you don't need that feature. |
| OCR or office tools fail | Install Tesseract / LibreOffice, or set `:tessdata` in `imaging.edn` / `:bin` in `office.edn`. |
| Model or provider errors | Check `:llm {:model … :url …}` in `grog.edn`. |

## 6. Where things live

| Thing | Path |
|---|---|
| settings | `~/.config/grog/grog.edn` |
| other config | `~/.config/grog/{odoo-instances.edn, imap-accounts.edn, imaging.edn, office.edn, secrets.edn}` |
| secrets | the OS secret store (Secret Service); `secrets.edn` is the fallback |
| projects | `~/grog-projects/<project>/{notes,dialog,state}` |
| run log | `$XDG_STATE_HOME/grog/client.log` (default `~/.local/state/grog/client.log`) |
| version | `VERSION` at the tree root; also inside the jars as `grog-version.edn` |

## 7. Two things worth knowing

**Output limits are the agent loop's business.** grog does not set
`:llm :max-tokens`; on this path it has no effect. If a model rejects a request
over its output reservation, that is an ECA-side setting.

**The tool schemas are verified by a test.** `cd grog_mcp && clojure -M:test`
asserts that every tool advertises its parameters as a JSON-Schema object. If a
tool ever reports a *missing* argument it should have received, run that test.
