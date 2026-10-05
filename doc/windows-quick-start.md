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

**Installing with the installer? Skip this section** — it runs all of it for you
(see §2). Do it by hand only for an unpacked copy, or if you want to control the
toolchain yourself.

grog needs a Java runtime, a bash shell and ECA. Node.js is needed to run the
app from a bundle; the remaining tools are optional.

Everything except ECA comes from [scoop](https://scoop.sh). In PowerShell:

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser   # once, if it is not already
irm get.scoop.sh | iex
```

Scoop clones buckets with **git**, so install that first. Git for Windows also
ships `bash.exe`, which grog needs — but scoop's manifest shims only
`sh`/`git`/`git-bash`, **not** `bash`, so the command doesn't exist until you
add it (otherwise every tool server fails with `CreateProcess error=2`):

```powershell
scoop install git                        # git - required, and needed for buckets
scoop shim add bash "$(scoop prefix git)\bin\bash.exe"
                                          # exposes Git for Windows' bash.exe as `bash`
```

(`scripts\prereqs.ps1` does both for you — this section is the manual version.)

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

Run `grog-<version>-setup.exe` and follow the prompts. It is a **per-user**
install — no administrator prompt — it puts **grog** on the Desktop and in the
Start Menu with its icon, and it carries the backend, the tool bundle and the
interface with it. Nothing else to fetch.

Then start grog from the Start Menu.

### Portable, instead of installed

The bundle is also self-contained apart from the app runtime, so it can simply be
unpacked and run where it lands:

```cmd
mkdir %LOCALAPPDATA%\grog
tar -xf "%USERPROFILE%\Downloads\grog-0.1.0-windows-x64.zip" -C %LOCALAPPDATA%\grog

cd %LOCALAPPDATA%\grog\clients\web
npm install                          :: once; fetches the app runtime
cd %LOCALAPPDATA%\grog
scripts\grog-client.bat
```

Windows ships `tar` from libarchive (bsdtar), so it reads the `.zip` as happily as
a `.tar.gz`; right-click → *Extract All…* works too, and is friendlier than
`Expand-Archive`, which is slow with a few hundred files. (On Linux `tar` is GNU
tar and cannot read a zip — use `unzip`.)

Add a menu entry for the unpacked copy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1
```

What a bundle contains:

| Part | Location |
|---|---|
| backend jar | `target\grog-spine.jar` |
| tool jar | `grog_mcp\target\grog-mcp-<version>.jar` |
| interface (built) | `clients\web\resources\public\` |

Building grog from source is a separate, developer-only path — see
[Building from source](#8-building-from-source).

## 3. Run

**Installed:** start **grog** from the Start Menu, or double-click the Desktop
icon.

**Portable:** `scripts\grog-client.bat`, or the menu entry created above.

Icons, the Linux side, and how to verify an install: `doc/desktop-kit.md`.

## 4. Verify

Open the run log and check, in order. The client writes one file per instance —
`%USERPROFILE%\grog.<pid>.log`, newest match (`$GROG_LOG` overrides the
`%USERPROFILE%\grog` base). Launching via `scripts\grog-client.bat` additionally
keeps a stream copy at `%TEMP%\grog-client-<n>.log`:

1. `[grog-client] transport up: child pid <N>` — the backend is running.
2. `[renderer] loaded ok` — the interface loaded.
3. `[grog-debug] ECA started ok` — the agent loop is up.
4. `[MCP] Started MCP server grog-…` ×N — every configured tool server is up.
5. `[LLM-API] Default LLM model '<model>' decision ':config-default-model'` —
   a model is available. `:no-available-model` means no credential is set; add
   one with `/secret set LLM_API_KEY <key>`.
6. Ask for something that needs a tool. The log shows the call **with its
   arguments**: `[TOOLS] Calling tool 'grog-mcp__fetch_url' with args '{…}'`.

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
| The window closes immediately | Read the newest `%USERPROFILE%\grog.<pid>.log` — it holds both the client's and the backend's output, even when a spawned process fails. Running `scripts\grog-client.bat` from an open console also prints a stream copy. A leftover process holding a stale log open can stop the launcher; close stray `java.exe`/`bash.exe` and retry. |
| The backend never starts | `java -version` — it must be on PATH. |
| Every tool server reports `CreateProcess error=2` | `bash --version` — it must resolve to Git for Windows, not WSL's stub. Missing? `scoop shim add bash "$(scoop prefix git)\bin\bash.exe"` (scoop doesn't shim bash itself). |
| Config errors mentioning `clojure.lang.Symbol` | `grog.edn` starts with a byte-order mark. Save it as UTF-8 **without** a BOM. |
| The tool jar is missing | Re-extract the bundle, or copy `grog-mcp-<version>.jar` into `grog_mcp\target\`. |
| Packaging dies with `Cannot create symbolic link … A required privilege is not held by the client` | electron-builder is unpacking `winCodeSign` (it contains macOS symlinks). Enable **Developer Mode** (Settings → System → For developers), or run the build elevated. |
| The installed app uses the default Electron icon | `clients/web/build/icon.ico` is missing from the checkout — it was once gitignored. Re-pull, or copy the icons in. |
| `/doctor` shows a tool as missing | Install it, or ignore it if you don't need that feature. |
| OCR fails, or reports missing language data | Install `tesseract-languages` (the `tesseract` package ships no recognition data), or set `:tessdata` in `imaging.edn`. |
| Office tools fail | Install LibreOffice, or set `:bin` in `office.edn`. |
| Model or provider errors | Check `:llm {:model … :url …}` in `grog.edn`. |

## 6. Where things live

| Thing | Path |
|---|---|
| settings | `%USERPROFILE%\.config\grog\grog.edn` |
| first run | If `grog.edn` is missing the client **offers** to create it there (a native dialog naming the path), then opens the folder. Edit it — model, provider, API key — and restart; until then grog runs on defaults and cannot reach a model. The optional examples (odoo, imap, gitlab, imaging, secrets) are written alongside as `*.example`. |
| other config | `%USERPROFILE%\.config\grog\{odoo-instances.edn, imap-accounts.edn, imaging.edn, office.edn, secrets.edn}` |
| secrets | the Windows credential store; `secrets.edn` is the fallback |
| projects | `%USERPROFILE%\grog-projects\<project>\{notes,dialog,state}` |
| profile / cache | `%LOCALAPPDATA%\grog` — Chromium's private store (cache, Local Storage). Deliberately **Local**, not Roaming |
| run log | `%USERPROFILE%\grog.<pid>.log` — client-written, newest match (`$GROG_LOG` overrides the base, `$GROG_UI_LOG_KEEP` the count). `scripts\grog-client.bat` also saves a stream copy to `%TEMP%\grog-client-<n>.log` |
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
[Clojure CLI](https://clojure.org/guides/install_clojure), Babashka, and
Node.js; running grog does **not**.

```cmd
git clone <remote> %USERPROFILE%\grog
cd %USERPROFILE%\grog
cd clients\web && npm install && cd ..\..
bb dist                   :: both jars + the interface bundle + dist/ + tarball
```

`npm install` is once per clone: `node_modules/` is deliberately not in git.
Skip it and `bb dist` runs it for you before the renderer build — the jar side
self-provisions Maven deps the same way, so the build is one command either way.

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

### Making an installer for other people

An installer is a Windows build artifact, so build it **on Windows** — NSIS runs
natively there and there is nothing to cross-compile. One machine does this;
everybody else just runs the file it produces.

On the build machine, once:

```powershell
scoop install git
scoop bucket add java
scoop bucket add extras
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop install java/temurin-lts-jdk nodejs-lts babashka clj-deps
```

`clj-deps` matters: the bucket's `clojure` package installs only a **PowerShell
module** (no `clojure.exe`/`.cmd` anywhere on `PATH`), so PowerShell can run
`clojure` but no other program can — `bb dist` fails with `Cannot run program
"clojure"`. `clj-deps` shims the real `deps.exe` as `clojure`/`clj`, which every
process can execute. (Already installed the old one? `scoop uninstall clojure`
first.)

then refine from the repo, and run one command:

```cmd
cd %USERPROFILE%\grog
bb dist --target windows
```

That is the whole build. It installs the interface's npm packages if they are
missing, builds both jars and the renderer, handles electron-builder's
`winCodeSign` prerequisites itself (no Developer Mode or elevation required),
and finishes by printing the one artifact to ship:

```
ARTIFACT: C:\Users\<you>\grog\dist\grog-0.1.0-setup.exe
```

Copy that single `.exe` to the target machine and run it — the installer lays
grog down and installs its prerequisites (Java, Git/bash, ECA) in one step.
Nothing else to copy, nothing to set up by hand.

`bb dist --bundle` also writes the portable `.tar.gz`/`.zip` under `dist/`; that
is a developer convenience, not part of the shipping path.

The three build targets (the tool jar, the spine jar and the renderer) are
independent, so `bb dist` runs them **in parallel** — on an 8-core box that is
roughly the time of the slowest one rather than the sum. Use `--serial` if the
machine is short on RAM (three JVMs at once). If a build feels slow on Windows
despite low CPU, the cost is I/O, not compute: `tools.build`'s uberjar step
merges the whole classpath with one sequential zip stream, and antivirus
scanning of the source tree and `~\.m2` is the dominant extra cost — excluding
those two paths is the fix.

The installer is per-user (no administrator prompt), installs to
`%LOCALAPPDATA%\Programs\grog`, adds Desktop and Start Menu shortcuts, embeds
both jars, and **installs the prerequisites for the user** (§1) as part of the
install. Set `GROG_SKIP_PREREQS=1` to skip that step for an unattended install.

Doing this from Linux also works, but electron-builder reaches for Wine just to
generate the uninstaller — an extra host dependency for a Windows-shaped
artifact.
