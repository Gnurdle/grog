# grog on Windows — install & verify

grog is an AI coding assistant that runs its tools **on your machine**: files,
shell, office documents, OCR, web search. It is a desktop app — there is no
server to install and nothing to connect to.

## What runs

```
grog                                  the desktop app
 └─ java -cp <root>\target\grog-spine.jar            the backend ("the spine")
     ├─ eca.exe                                       the agent loop
     └─ bash -lc "cd <root>\grog_mcp && java -cp \
                   <root>\grog_mcp\target\grog-mcp-<v>.jar \
                   clojure.main -m grog_mcp.main --server <id>"    ×N   the tool servers
```

The app starts the spine as its child; the spine starts the agent loop, which
starts the tool servers. Closing the window stops all of them.

## 1. Install the prerequisites

grog needs a Java runtime, a bash shell and ECA. Node.js is needed to run the
app from a bundle; the remaining tools are optional.

Everything except ECA comes from [scoop](https://scoop.sh). In PowerShell:

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser   # once, if it is not already
irm get.scoop.sh | iex
```

Scoop clones buckets with **git**, so install that first. It is also the bash
shell grog needs, so it is doing double duty:

```powershell
scoop install git                    # bash - required, and needed for buckets
```

Now add the buckets that hold the rest. `main` is the only one scoop starts
with; the last of these is third-party and needs its URL:

```powershell
scoop bucket add java
scoop bucket add extras
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
```

Then install everything else:

```powershell
scoop install java/temurin-lts-jdk   # Java - required
scoop install nodejs-lts             # Node.js
scoop install babashka
scoop install ripgrep jq
scoop install poppler tesseract tesseract-languages
scoop install extras/libreoffice
```

`tesseract-languages` is separate from `tesseract` on purpose — the package
ships the OCR engine but no recognition data on its own, and without the
language files OCR fails. (They can also be fetched by hand from
<https://github.com/tesseract-ocr/tessdata_fast>.)

**Close and reopen your terminal** afterwards — PATH changes only apply to new
shells.

**ECA** is not in scoop. Download `eca-native-windows-amd64.zip` from
<https://github.com/editor-code-assistant/eca/releases>, unzip it to a permanent
place (for example `%LOCALAPPDATA%\eca`), and add that folder to PATH. Pin the
version you test with — grog drives it directly.

Check what you have:

```cmd
java -version & bash --version & node --version & eca --version
```

| Need | Required? | Why |
|---|---|---|
| Java 17+ | **yes** | runs the backend and the tool servers |
| bash (Git for Windows) | **yes** | the tool servers are launched through it |
| ECA | **yes** | the agent loop |
| Node.js | for a bundle | runs the app runtime |
| Babashka | no | `run_babashka`, and the build scripts |
| LibreOffice, Tesseract, Poppler, ripgrep, jq | no | one feature each |

A missing optional tool only disables the features that need it. After grog is
installed, `grog doctor` lists every tool, its version, and what it unlocks.

## 2. Install grog

Extract the bundle where you want it to live. Windows ships `tar` from libarchive
(bsdtar), so it reads the `.zip` as happily as a `.tar.gz`:

```cmd
mkdir %LOCALAPPDATA%\grog
tar -xf grog-0.1.0-windows-x64.zip -C %LOCALAPPDATA%\grog
```

Right-click → *Extract All…* does the same if you prefer the GUI. Prefer `tar`
over `Expand-Archive` here — PowerShell's cmdlet is slow with a few hundred
files. (On Linux, `tar` is GNU tar and cannot read a zip; use `unzip` there.)

Then fetch the app runtime once:

```cmd
cd %LOCALAPPDATA%\grog\clients\web
npm install
```

The bundle already contains the two jars and the built interface, so nothing
else is needed to run.

| Part | Location |
|---|---|
| backend jar | `target\grog-spine.jar` |
| tool jar | `grog_mcp\target\grog-mcp-<version>.jar` |
| interface (built) | `clients\web\resources\public\` |

Building grog from source is a separate, developer-only path — see
[Building from source](#8-building-from-source).

## 3. Run

```cmd
scripts\grog-client.bat
```

To put grog on the Desktop and in the Start Menu, with its icon:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1
powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1 -Uninstall
```

Icons, the Linux side, and how to verify an install: `doc/desktop-kit.md`.

## 4. Verify

Open the run log (`%TEMP%\grog-client-<n>.log`) and check, in order:

1. `[grog-client] transport up: child pid <N>` — the backend is running.
2. `[renderer] loaded ok` — the interface loaded.
3. `[grog-debug] ECA started ok` — the agent loop is up.
4. `[MCP] Started MCP server grog-…` ×N — every configured tool server is up.
5. `[LLM-API] Default LLM model '<model>' decision ':config-default-model'` —
   a model is available. `:no-available-model` means no credential is set; add
   one with `/secret set LLM_API_KEY <key>`.
6. Ask for something that needs a tool. The log shows the call **with its
   arguments**: `[TOOLS] Calling tool 'grog-fetch__fetch_url' with args '{…}'`.

To list every dependency grog can see — its path, version, and the feature each
one enables — run the doctor:

```cmd
java --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED ^
     -cp target\grog-spine.jar clojure.main -m grog.doctor
```

Add `--json` for the machine-readable form. With Babashka installed, `bb doctor`
does the same thing.

## 5. When something is wrong

| Symptom | Check |
|---|---|
| The window closes immediately | Run `scripts\grog-client.bat` from an open console so you can read the log. A process holding a stale `%TEMP%\grog-client-*.log` open can stop the launcher; close stray `java.exe`/`bash.exe` and retry. |
| The backend never starts | `java -version` — it must be on PATH. |
| Every tool server reports `CreateProcess error=2` | `bash --version` — it must be on PATH (Git for Windows). |
| Config errors mentioning `clojure.lang.Symbol` | `grog.edn` starts with a byte-order mark. Save it as UTF-8 **without** a BOM. |
| The tool jar is missing | Re-extract the bundle, or copy `grog-mcp-<version>.jar` into `grog_mcp\target\`. |
| `/doctor` shows a tool as missing | Install it, or ignore it if you don't need that feature. |
| OCR fails, or reports missing language data | Install `tesseract-languages` (the `tesseract` package ships no recognition data), or set `:tessdata` in `imaging.edn`. |
| Office tools fail | Install LibreOffice, or set `:bin` in `office.edn`. |
| Model or provider errors | Check `:llm {:model … :url …}` in `grog.edn`. |

## 6. Where things live

| Thing | Path |
|---|---|
| settings | `%USERPROFILE%\.config\grog\grog.edn` |
| other config | `%USERPROFILE%\.config\grog\{odoo-instances.edn, imap-accounts.edn, imaging.edn, office.edn, secrets.edn}` |
| secrets | the Windows credential store; `secrets.edn` is the fallback |
| projects | `%USERPROFILE%\grog-projects\<project>\{notes,dialog,state}` |
| run log | `%TEMP%\grog-client-<n>.log` |
| version | `VERSION` at the tree root; also inside the jars as `grog-version.edn` |

## 7. Two things worth knowing

**Output limits are the agent loop's business.** grog does not set
`:llm :max-tokens`; on this path it has no effect. If a model rejects a request
over its output reservation, that is an ECA-side setting.

**The tool schemas are verified by a test.** `cd grog_mcp && clojure -M:test`
asserts that every tool advertises its parameters as a JSON-Schema object. If a
tool ever reports a *missing* argument it should have received, run that test.

## 8. Building from source

Only for working on grog itself. This path also needs the
[Clojure CLI](https://clojure.org/guides/install_clojure) and Babashka; running
grog does **not**.

```cmd
git clone <remote> %USERPROFILE%\grog
cd %USERPROFILE%\grog
bb dist                   :: both jars + the interface bundle + dist/ + tarball
```

`bb dist` writes the two jars the app expects:

| Jar | Location |
|---|---|
| `grog-spine.jar` | `target\` |
| `grog-mcp-<version>.jar` | `grog_mcp\target\` |

Both locations matter: the app looks for the spine jar in `target\`, and the
config generator looks for the tool jar in `grog_mcp\target\` (newest match
wins). To build them alone:

```cmd
clojure -T:build spine :version '"0.1.0"'
cd grog_mcp && clojure -T:build uber :version '"0.1.0"'
```

### Making an installer

The bundle above can be shipped as-is, or turned into a Windows installer (a
per-user NSIS setup that creates Desktop and Start Menu shortcuts):

```cmd
cd clients\web
npm install                                   :: brings electron-builder
npx electron-builder --win nsis --publish never -c.extraMetadata.version=0.1.0
```

The result is `clients\web\dist\grog Setup <version>.exe`.

Requirements: both jars in their expected places (above), the built interface,
and internet — electron-builder downloads the app runtime and its packaging
tools on first run. From Linux the same command works but needs Wine.

The installer embeds both jars under the app's `resources\jars\`, which is where
the app looks for them once installed. On a machine that has grog installed,
nothing else is needed beyond Java, bash and ECA.
