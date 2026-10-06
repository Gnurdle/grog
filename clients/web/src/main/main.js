// grog web client — Electron main: the CLIENT HOST.
//
// TWO TRANSPORT MODES, one code path downstream:
//   EMBEDDED (default) — spawn the grog spine as OUR CHILD and speak NDJSON over
//     its stdio. The client owns that process: no daemon, no systemd, no socket,
//     and closing the app ends it cleanly (its stdin hits EOF and the spine tears
//     down sessions + ECA + MCP children). This is the shipping shape per
//     doc/multi-client-layout.md (decisions A1/A3): everything on the client.
//   ATTACH (GROG_SERVER_HOST set) — dial a running server over TCP, for
//     remote/multi-client use.
//
// It also forwards window focus, which the question-visibility rule needs
// (doc/clients/web-client-plan.md §3.4.1).
const { app, BrowserWindow, dialog, ipcMain, Menu, screen, session, shell } = require("electron");
const net = require("net");
const os = require("os");
const path = require("path");
const fs = require("fs");
const { execFile, spawn, spawnSync } = require("child_process");

// Logging first, so everything after it (including a failed spawn) is captured.
// One file per client instance: $GROG_LOG -> else ~/grog, as <base>.<pid>.log.
const log = require("./log");
const configSeed = require("./config-seed");
const desktopInstall = require("./desktop-install");
const LOG = log.install();
LOG.tee();
console.log(`[grog-client] log file: ${LOG.path}`);

// Same rendezvous the server computes (grog.server/default-socket-path):
// GROG_SERVER_SOCKET wins; else $XDG_RUNTIME_DIR/grog-$USER.sock; else tmp.
const SOCKET_PATH =
  process.env.GROG_SERVER_SOCKET ||
  path.join(process.env.XDG_RUNTIME_DIR || os.tmpdir(),
            `grog-${process.env.USER || "user"}.sock`);

// --- transport configuration (see the header for the two modes) -------------
const EMBEDDED = !process.env.GROG_SERVER_HOST;
const ATTACH_HOST = process.env.GROG_SERVER_HOST || "127.0.0.1";
const ATTACH_PORT = Number(process.env.GROG_SERVER_PORT || 9640);
// The client lives INSIDE the tree it drives (clients/web/src/main), so the
// spine's working directory must be the TREE ROOT — the one with deps.edn and
// src/grog. Do not trust path arithmetic alone: verify each candidate — a wrong
// root makes the spine die instantly with "Could not locate grog/server.clj on
// classpath" and the UI report "server unavailable".
function looksLikeGrogRoot(dir) {
  try {
    return !!dir
      && fs.existsSync(path.join(dir, "deps.edn"))
      && fs.existsSync(path.join(dir, "src", "grog"));
  } catch { return false; }
}

// A packaged app has no source tree: the jars travel beside it, under
// resources/jars (electron-builder's extraResources — see package.json).
const PACKAGED = app.isPackaged;
const JARS_DIR = PACKAGED ? path.join(process.resourcesPath, "jars") : null;

function packagedJar(prefix) {
  try {
    return fs.readdirSync(JARS_DIR)
      .filter((f) => f.startsWith(prefix) && f.endsWith(".jar"))
      .sort()
      .map((f) => path.join(JARS_DIR, f))[0] || null;
  } catch { return null; }
}

function findGrogHome() {
  // Packaged: there is no tree to find. The backend runs from an absolute jar
  // path, and this is only its working directory.
  if (PACKAGED) return process.resourcesPath;
  const up4 = path.resolve(__dirname, "..", "..", "..", "..");
  const candidates = [process.env.GROG_HOME, up4, process.cwd()].filter(Boolean);
  for (const c of candidates) if (looksLikeGrogRoot(c)) return c;
  return process.env.GROG_HOME || up4;
}

const GROG_HOME = findGrogHome();
if (!PACKAGED && !looksLikeGrogRoot(GROG_HOME)) {
  console.warn(`[grog-client] GROG_HOME=${GROG_HOME} does not look like a grog tree ` +
               `(no deps.edn + src/grog) — set GROG_HOME explicitly`);
}

// The tool bundle the backend hands to the agent loop. A packaged install ships
// it beside the app; from a source tree the backend finds it itself.
const MCP_JAR = PACKAGED ? packagedJar("grog-mcp-") : null;

// grog's OWN documentation travels in the bundle (electron-builder
// extraResources -> resources/docs). A packaged AppImage mounts it read-only
// inside the squashfs, so the spine cannot find it by searching — the client
// tells it where the bundle put it. From a source tree there is no bundle, so
// the tree root IS the docs root. Nothing is copied: the bootstrap project
// REFERENCES this directory (grog.docs / grog.bootstrap), so a rebuild flows
// straight through. GROG_DOCS_DIR is what grog.docs/docs-dir reads.
const DOCS_DIR = PACKAGED ? path.join(process.resourcesPath, "docs") : GROG_HOME;

// The starter config examples. A packaged install ships them beside the app
// (electron-builder extraResources -> resources/config.examples); from a source
// tree they live in the repo's own resources/config.examples. Either way the
// client seeds the user's config home from them on first run, so a fresh
// install OR a factory reset is never left with an empty config home and no
// file to edit. `config-seed.js` appends "config.examples", so this is the
// directory that CONTAINS it.
const CONFIG_EXAMPLES_ROOT = PACKAGED ? process.resourcesPath : path.join(GROG_HOME, "resources");

// A client-owned spine: no TCP socket, no MCP HTTP endpoint (tools reach ECA
// through the generated config instead), and its own MCP port range.
const SPINE_ENV_EXTRA = Object.assign(
  {
    GROG_SERVER_NO_SOCKET: "1",
    GROG_MCP_HTTP: "0",
    GROG_MCP_BASE_PORT: process.env.GROG_MCP_BASE_PORT || "9800",
    GROG_DOCS_DIR: DOCS_DIR,
  },
  MCP_JAR ? { GROG_MCP_JAR: MCP_JAR } : {},
);

// Spine launch: prefer the self-contained spine jar when it exists (no Clojure
// CLI, no source tree on the machine — the installable shape); fall back to the
// CLI + tree for development checkouts.
const SPINE_JAR = PACKAGED
  ? (packagedJar("grog-spine") || path.join(JARS_DIR, "grog-spine.jar"))
  : path.join(GROG_HOME, "target", "grog-spine.jar");
const SPINE_JAVA_FLAGS = [
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--enable-native-access=ALL-UNNAMED",
];
const SPINE_HAS_JAR = fs.existsSync(SPINE_JAR);
const SPINE_CMD = process.env.GROG_SPINE_CMD
  || (SPINE_HAS_JAR ? "java" : "clojure");
const SPINE_ARGS = process.env.GROG_SPINE_ARGS
  ? process.env.GROG_SPINE_ARGS.split(/\s+/).filter(Boolean)
  : (SPINE_HAS_JAR
      ? [...SPINE_JAVA_FLAGS, "-cp", SPINE_JAR, "clojure.main", "-m", "grog.server"]
      : ["-M", "-m", "grog.server"]);
let spine = null;

let win = null;
let sock = null;
let connected = false;
let connecting = false;
let buf = "";
let nextId = 1;
const pending = new Map();     // jsonrpc id -> {resolve, reject}
let retryTimer = null;
// reconnect backoff: quick at first (a restart is normally seconds away), then
// slowing to a cap — so a server that's down for a while isn't hammered every
// 2s forever (e.g. `systemctl --user stop grog-server`).
const RETRY_MIN_MS = 1000;
const RETRY_MAX_MS = 30000;
let retryDelay = RETRY_MIN_MS;
let retryAttempts = 0;
let nextRetryAt = 0;

function send(method, params) {
  if (win && !win.isDestroyed()) win.webContents.send("grog:notify", { method, params: params || {} });
}

function notifyRenderer() {
  send("server-status", {
    connected,
    path: transportLabel(),
    mode: EMBEDDED ? "embedded" : "attach",
    retrying: !connected && retryTimer != null,
    attempts: retryAttempts,
    nextRetryAt,
  });
}

function rejectAll(reason) {
  for (const { reject } of pending.values()) reject(new Error(reason));
  pending.clear();
}

function connect() {
  if (sock || connecting) return;
  // A backoff retry is already pending: don't let renderer calls respawn now.
  // (scheduleRetry clears retryTimer before calling connect(), so the retry
  // itself gets through.)
  if (retryTimer) return;
  if (EMBEDDED) spawnSpine();
  else attach();
}

// Resolves once a transport is up, so a renderer call that arrives during
// start-up WAITS instead of failing silently (a prompt that goes nowhere and
// says nothing is the failure mode this prevents).
let readyResolve = null;
const ready = new Promise((r) => { readyResolve = r; });

// A tool approval or an LLM question BLOCKS the agent until the user answers.
// The renderer only paints those dialogs while the window is focused, so when
// the window sits behind another app the request is invisible — the "the dialog
// pops up behind instead of in front" bug. Bring the window forward; if the OS
// refuses focus (Windows' foreground lock), flash the taskbar as the fallback.
function needsAttention(msg) {
  if (msg.method === "question") return true;
  if (msg.method === "event") {
    const t = msg.params && msg.params.type;
    return t === "approval" || t === "question";
  }
  return false;
}

function requestAttention() {
  if (!win || win.isDestroyed()) return;
  try {
    if (win.isMinimized()) win.restore();
    if (!win.isVisible()) win.show();
    if (!win.isFocused()) {
      win.focus();      // best effort
      win.moveTop();    // raise above other windows (no-op on some Linux WMs)
      // If focus() was refused, the taskbar flash is the only signal left.
      setTimeout(() => {
        try {
          if (win && !win.isDestroyed() && !win.isFocused()) win.flashFrame(true);
        } catch { /* best effort */ }
      }, 250);
    }
  } catch (e) {
    console.warn("[grog-client] could not raise window for attention:", e.message);
  }
}

function onLine(line) {
  if (!line.trim()) return;
  let msg; try { msg = JSON.parse(line); } catch (e) { return; }
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) reject(new Error(msg.error.message || "server error"));
    else resolve(msg.result);
  } else if (msg.method) {
    send(msg.method, msg.params);
    if (needsAttention(msg)) requestAttention();
  }
}

function pump(stream) {
  stream.on("data", (b) => {
    buf += b.toString("utf8");
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      if (LOG.wire) LOG.write(`<- ${line}`);   // GROG_LOG_WIRE=1
      onLine(line);
    }
  });
}

function transportLabel() {
  return EMBEDDED ? `${SPINE_CMD} ${SPINE_ARGS.join(" ")}` : `${ATTACH_HOST}:${ATTACH_PORT}`;
}

function onReady(label) {
  connecting = false;
  connected = true;
  retryDelay = RETRY_MIN_MS;
  retryAttempts = 0;
  nextRetryAt = 0;
  console.log(`[grog-client] transport up: ${label}`);
  if (readyResolve) { readyResolve(true); readyResolve = null; }
  notifyRenderer();
}

function down(why) {
  connecting = false;
  connected = false;
  sock = null;
  buf = "";
  // Log it: without this, a failing spawn (e.g. `java` not on PATH) is silent
  // in the terminal while the renderer span and the UI just say "server
  // unavailable".
  console.error(`[grog-client] transport down: ${why}`);
  rejectAll(why);
  notifyRenderer();
  send("server-down", { text: `[grog] ${why}` });
}

// EMBEDDED: the spine is our CHILD. Its stdin is the write target, its stdout is
// the NDJSON stream, its stderr is echoed with a prefix. GROG_SERVER_NO_SOCKET
// stops it from also binding a TCP port.
function spawnSpine() {
  connecting = true;
  console.log(`[grog-client] spawning spine (${GROG_HOME}): ${SPINE_CMD} ${SPINE_ARGS.join(" ")}`);
  spine = spawn(SPINE_CMD, SPINE_ARGS, {
    cwd: GROG_HOME,
    env: Object.assign({}, process.env, SPINE_ENV_EXTRA),
    windowsHide: true,
    // POSIX only: make the spine lead its own process group so killSpineTree can
    // signal the WHOLE tree. Not on Windows (detached there would pop a console
    // despite windowsHide); Windows gets an explicit `taskkill /T` instead.
    detached: process.platform !== "win32",
  });
  pump(spine.stdout);
  spine.stderr.on("data", (b) => {
    const line = `[grog-spine] ${b}`;
    process.stderr.write(line);
    LOG.write(line);          // the spine's diagnostics belong in the log file
  });
  // A spawn error (ENOENT: `java`/`clojure` missing from PATH) fires here with
  // NO 'spawn' event and NO 'exit' event. Left unhandled, nothing is logged or
  // rescheduled, so every renderer call re-enters connect() and respawns
  // instantly — a 1000/sec storm that looks like a hang. Report the reason and
  // back off like any other lost transport.
  spine.on("error", (e) => {
    connecting = false;
    console.error(`[grog-client] spine SPAWN FAILED (${SPINE_CMD}): ${e.message}`);
    spine = null;
    down(`spine failed to start: ${e.message}`);
    scheduleRetry();
  });
  spine.on("spawn", () => { sock = spine.stdin; onReady(`child pid ${spine.pid}`); });
  spine.on("exit", (code, sig) => {
    spine = null;
    if (!connected && !sock) return;   // start-up failure already reported
    down(`spine exited (code ${code}${sig ? ", " + sig : ""})`);
    scheduleRetry();                   // client-owned spine died: bring it back
  });
}

// ATTACH: dial an already-running server (a daemon, or a remote host).
function attach() {
  connecting = true;
  const s = net.connect(ATTACH_PORT, ATTACH_HOST);
  sock = s;
  s.on("connect", () => onReady(`${ATTACH_HOST}:${ATTACH_PORT}`));
  pump(s);
  const lost = (err) => {
    if (connected || err) console.log(`[grog-client] disconnected: ${err ? err.message : "closed"}`);
    down(`grog-server unreachable at ${ATTACH_HOST}:${ATTACH_PORT}`);
    scheduleRetry();
  };
  s.on("error", lost);
  s.on("close", () => lost(null));
}

function scheduleRetry() {
  if (retryTimer || connected || connecting) return;
  retryAttempts += 1;
  const delay = retryDelay;
  nextRetryAt = Date.now() + delay;
  retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);   // grow for next time
  console.log(`[grog-client] reconnect attempt #${retryAttempts} in ${delay}ms`);
  notifyRenderer();
  retryTimer = setTimeout(() => { retryTimer = null; connect(); }, delay);
}

const call = async (method, params) => {
  if (!connected || !sock) {
    connect();
    const ok = await Promise.race([
      ready,
      new Promise((r) => setTimeout(() => r(false), 30000)),
    ]);
    if (!ok || !connected || !sock) {
      throw new Error(`grog spine not reachable (${transportLabel()})`);
    }
  }
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    sock.write(JSON.stringify({ jsonrpc: "2.0", id, method, params: params || {} }) + "\n");
  });
};

// --- voice (client-side speech-to-text) -------------------------------------
// All voice processing stays on THIS machine: the renderer captures + encodes a
// 16 kHz mono WAV and hands the bytes here; we run the local engine
// (whisper.cpp) and return text. Nothing voice-related touches the grog-server
// socket — the server never sees a byte of audio.
const VOICE_ENABLED = process.env.GROG_VOICE_ENABLED !== "0";
const VOICE_MAX_SECONDS = Number(process.env.GROG_VOICE_MAX_SECONDS || 60);

// The whisper model nothing provides by default. A PACKAGED install has no
// ~/.config/grog/models/ggml-base.en.bin, and nothing seeds one — so voice was
// silently "enabled" and only failed on first use. Look for a model, in order:
//   $GROG_VOICE_MODEL           explicit override
//   <config home>/models/…      what the docs / grog.edn examples name
//   <resourcesPath>/models/…    a model shipped INSIDE a packaged app
//   <repo>/resources/models/…   the same, running from a source tree
// The last two let a distro build ship a model (drop one in resources/models);
// if none is found we still return the conventional path so the error NAMES it.
function voiceModelCandidates() {
  const names = ["ggml-base.en.bin", "ggml-tiny.en.bin", "ggml-small.en.bin", "ggml-base.bin"];
  const dirs = [
    // honour GROG_CONFIG_HOME / XDG_CONFIG_HOME, exactly like the rest of grog
    path.join(configSeed.configHome(), "models"),
    path.join(process.resourcesPath || "", "models"),
    path.join(__dirname, "..", "..", "..", "..", "resources", "models"),
  ];
  const cands = [];
  if (process.env.GROG_VOICE_MODEL) cands.push(process.env.GROG_VOICE_MODEL);
  for (const d of dirs) for (const n of names) cands.push(path.join(d, n));
  return cands;
}

function voiceModelPath() {
  const found = voiceModelCandidates().find((p) => {
    try { return fs.existsSync(p); } catch { return false; }
  });
  return found || path.join(configSeed.configHome(), "models", "ggml-base.en.bin");
}

// Resolve an executable: an explicit path is checked directly, a bare name is
// looked up on PATH. Returns the path or null — used to say WHICH is missing.
//
// Windows does NOT append PATHEXT here (nor does CreateProcess): a bare
// `whisper-cli` never resolves to `whisper-cli.exe`, which is exactly why voice
// was dead on Windows. Try the executable extensions explicitly.
const EXE_EXTS = process.platform === "win32"
  ? [".exe", ".cmd", ".bat", ".com", ""]
  : [""];

function whichBin(bin) {
  if (!bin) return null;
  if (bin.includes("/") || bin.includes("\\")) {
    try { return fs.existsSync(bin) ? bin : null; } catch { return null; }
  }
  for (const d of (process.env.PATH || "").split(path.delimiter)) {
    if (!d) continue;
    for (const ext of EXE_EXTS) {
      try { if (fs.existsSync(path.join(d, bin + ext))) return path.join(d, bin + ext); }
      catch { /* ignore */ }
    }
  }
  return null;
}

function voiceCommand() {
  // GROG_VOICE_COMMAND: a JSON array (["whisper-cli","-m",…,"{wav}"]) or a plain
  // string; "{wav}" is replaced with the recorded file. Default: whisper-cli on
  // PATH, else the common local build, with a model located by voiceModelPath.
  const raw = process.env.GROG_VOICE_COMMAND;
  if (raw) {
    try {
      return (/^\s*\[/.test(raw) ? JSON.parse(raw) : raw.split(/\s+/))
        .map(String).filter(Boolean);
    } catch { return raw.split(/\s+/).map(String).filter(Boolean); }
  }
  const model = voiceModelPath();
  // The binary name differs by platform (`.exe`), and an MSVC build lands under
  // build/bin/Release — both were missed, so a Windows whisper.cpp install was
  // never found even when present.
  const name = process.platform === "win32" ? "whisper-cli.exe" : "whisper-cli";
  const built = [
    path.join(os.homedir(), "whisper.cpp", "build", "bin", name),
    path.join(os.homedir(), "whisper.cpp", "build", "bin", "Release", name),
    path.join(os.homedir(), "whisper.cpp", "build", "bin", "whisper-cli"),
  ].find((p) => { try { return fs.existsSync(p); } catch { return false; } });
  const bin = built || "whisper-cli";
  return [bin, "-m", model, "-f", "{wav}", "-nt"];
}

function voiceModelIn(argv) {
  const i = argv.indexOf("-m");
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null;
}

function voiceStatus() {
  const argv = voiceCommand();
  const model = voiceModelIn(argv);
  let reason = null;
  if (!VOICE_ENABLED) reason = "voice disabled (GROG_VOICE_ENABLED=0)";
  else if (!argv.length) reason = "no transcription command configured";
  else if (!whichBin(argv[0]))
    reason = `engine not found: ${argv[0]} — install whisper.cpp, or set GROG_VOICE_COMMAND`;
  else if (model && !fs.existsSync(model))
    reason = `speech model not found: ${model} — put a ggml model there or set GROG_VOICE_MODEL`;
  return {
    enabled: VOICE_ENABLED && argv.length > 0 && !reason,
    reason,
    maxSeconds: VOICE_MAX_SECONDS,
    engine: argv.length ? path.basename(argv[0]) : null,
    model: model || null,
  };
}

// whisper prints these when it hears no speech — treat as an empty transcript so
// nothing junk gets pasted into the prompt (mirrors grog.voice/clean-transcript).
function cleanTranscript(s) {
  const t = String(s || "").trim();
  if (!t) return "";
  if (/^[\s\[(]*(?:blank[ _]?audio|silence|music)[\s\])]*\.?$/i.test(t)) return "";
  return t;
}

function transcribe(bytes) {
  return new Promise((resolve, reject) => {
    const tpl = voiceCommand();
    if (!tpl.length) return reject(new Error("voice: no transcription command configured"));
    const tmp = path.join(os.tmpdir(), `grog-voice-${process.pid}-${Date.now()}.wav`);
    fs.writeFile(tmp, Buffer.from(bytes), (werr) => {
      if (werr) return reject(werr);
      const argv = tpl.map((a) => a.replace("{wav}", tmp));
      execFile(argv[0], argv.slice(1), { maxBuffer: 16 * 1024 * 1024 },
        (e, stdout, stderr) => {
          fs.unlink(tmp, () => {});
          if (e) return reject(new Error((stderr || e.message || "transcription failed").trim()));
          resolve({ text: cleanTranscript(stdout) });
        });
    });
  });
}

// Window / taskbar / Alt-Tab icon. Windows wants a multi-resolution `.ico`:
// handing it a plain PNG made the taskbar and Alt-Tab render a small,
// non-square bitmap INSTEAD of the crisp 256px icon baked into the exe. This
// `.ico` (256/128/64/48/32/16) ships inside the app under resources/public and
// is used on Windows; other platforms take the PNG.
const PUBLIC_DIR = path.join(__dirname, "..", "..", "resources", "public");
const WINDOW_ICON = (() => {
  const ico = path.join(PUBLIC_DIR, "icon.ico");
  if (process.platform === "win32" && fs.existsSync(ico)) return ico;
  return path.join(PUBLIC_DIR, "icon.png");
})();

function createWindow() {
  // Taller by default. 900px was cramped: the title bar and Windows display
  // scaling come off it, and the composer + status bar got squeezed. win32 asks
  // for more, and neither dimension may exceed the work area — otherwise the
  // window opens partly off-screen on a small display.
  const workArea = screen.getPrimaryDisplay().workAreaSize;
  const wantH = process.platform === "win32" ? 1200 : 1000;
  win = new BrowserWindow({
    width: Math.min(1280, workArea.width),
    height: Math.min(wantH, workArea.height),
    backgroundColor: "#020617",
    icon: WINDOW_ICON,
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true },
  });
  // surface renderer console + load failures in the terminal — a blank window
  // is otherwise indistinguishable from a failed build or a thrown init
  let loadFailed = false;
  win.webContents.on("console-message", (_e, level, message, line, source) =>
    console.log(`[renderer:${level}] ${message} (${source}:${line})`));
  win.webContents.on("did-fail-load", (_e, code, desc, url) => {
    loadFailed = true;
    console.log(`[renderer] did-fail-load ${code} ${desc} ${url}`);
  });
  win.webContents.on("did-finish-load", () => {
    // did-finish-load also fires for an ERROR page, so don't claim success then
    // — printing "loaded ok" after a failure is exactly how a black window hid.
    console.log(loadFailed ? "[renderer] load FAILED (see did-fail-load above)"
                           : "[renderer] loaded ok");
    notifyRenderer();   // let the renderer paint the current attach state
  });
  // A BLACK/BLANK window has two very different causes and this is the only way
  // to tell them apart from the outside: the renderer process dying (logged
  // here) versus a live renderer that simply isn't painting (a GPU/compositor
  // problem — see GROG_DISABLE_GPU / --disable-gpu).
  win.webContents.on("render-process-gone", (_e, details) =>
    console.log(`[renderer] PROCESS GONE reason=${details && details.reason} exitCode=${details && details.exitCode}`));
  win.webContents.on("unresponsive", () => console.log("[renderer] unresponsive"));
  // Never let the renderer navigate away from the app. An <a href> (or a
  // markdown link) in a transcript would otherwise replace the whole window —
  // which looks exactly like a crash. Open http(s) in the OS browser instead,
  // and block everything else (file:, javascript:, unknown schemes).
  win.webContents.on("will-navigate", (e, url) => {
    if (win.isDestroyed()) return;
    if (url !== win.webContents.getURL()) {
      e.preventDefault();
      if (/^https?:\/\//i.test(url)) shell.openExternal(url).catch(() => {});
    }
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url).catch(() => {});
    return { action: "deny" };
  });
  // So a blank window is inspectable on a machine with no terminal attached
  // (e.g. launched from the Start Menu): GROG_OPEN_DEVTOOLS=1 opens a detached
  // DevTools window whose Console tab shows whatever threw.
  if (process.env.GROG_OPEN_DEVTOOLS === "1") {
    win.webContents.openDevTools({ mode: "detach" });
  }
  // Packaged installs ALWAYS load the bundled page. Nothing in a shipped app may
  // depend on an env var being set: launching grog.exe directly (or from a Start
  // Menu shortcut) has no NODE_ENV at all, and the old test below pointed such a
  // window at the shadow-cljs dev server -> ERR_CONNECTION_REFUSED -> a black
  // window painted with only the BrowserWindow background colour.
  // Dev URL is for an UNPACKAGED tree only, and only when explicitly asked for.
  const devUrl = process.env.GROG_WEB_DEV_URL ||
    ((!PACKAGED && process.env.NODE_ENV !== "production") ? "http://localhost:9633" : null);
  if (devUrl) win.loadURL(devUrl);
  else win.loadFile(path.join(__dirname, "..", "..", "resources", "public", "index.html"));
  // guard against "Object has been destroyed" when the window is closing
  win.on("focus", () => {
    if (win.isDestroyed()) return;
    try { win.flashFrame(false); } catch { /* not flashing / unsupported */ }
    win.webContents.send("grog:focus", true);
  });
  win.on("blur",  () => { if (!win.isDestroyed()) win.webContents.send("grog:focus", false); });
}

// --- application-menu integration (AppImage) ---------------------------------
//
// An AppImage is a single file: nothing installs it, so nothing puts it in the
// menu. Rather than depend on AppImageLauncher/appimaged or a hand-written
// .desktop, the app writes its own entry — it knows the one thing a helper is
// needed for, its own absolute path ($APPIMAGE).
//
//   install -> no entry yet: ask once, then write entry + icon
//   update  -> an entry exists (we moved, or an OLDER grog installed it):
//              re-point it silently. The user already said yes once.
function maybeOfferDesktopIntegration() {
  let plan;
  try {
    plan = desktopInstall.plan({ resourcesPath: process.resourcesPath });
  } catch (e) {
    console.warn("[grog-client] desktop integration check failed:", e && e.message);
    return;
  }
  if (plan.state === "n/a" || plan.state === "ok" || plan.state === "foreign") return;

  const parent = win && !win.isDestroyed() ? win : undefined;

  if (plan.state === "update") {
    try {
      desktopInstall.install(plan);
      const refreshed = desktopInstall.refresh();
      console.log(`[grog-client] menu entry re-pointed: ${plan.previousProgram || "?"} -> ${plan.appImage}`
        + (refreshed.length ? ` (refreshed: ${refreshed.join(", ")})` : ""));
    } catch (e) {
      console.warn("[grog-client] could not re-point the menu entry:", e && e.message);
    }
    return;
  }

  // state === "install"
  if (plan.declined || process.env.GROG_DESKTOP_INSTALL === "0") return;
  const forced = process.env.GROG_DESKTOP_INSTALL === "1";
  if (!forced) {
    const answer = dialog.showMessageBoxSync(parent, {
      type: "question",
      buttons: ["Add to menu", "Not now"],
      defaultId: 0,
      cancelId: 1,
      title: "grog — application menu",
      message: "Add grog to your application menu?",
      detail: `grog is running from an AppImage:\n\n    ${plan.appImage}\n\n`
        + `This writes an entry and an icon:\n    ${plan.desktopFile}\n    ${plan.iconDest}\n\n`
        + "so grog shows up in your launcher. The entry follows the file if you move it later,\n"
        + "and it also takes over an entry written by an older copy of grog.",
    });
    if (answer !== 0) {
      try {
        desktopInstall.decline(plan);
      } catch (_e) { /* best effort */ }
      return;
    }
  }
  try {
    const written = desktopInstall.install(plan);
    const refreshed = desktopInstall.refresh();
    console.log(`[grog-client] menu entry installed: ${written.join(", ")}`
      + (refreshed.length ? ` (refreshed: ${refreshed.join(", ")})` : ""));
  } catch (e) {
    console.error("[grog-client] could not install the menu entry:", e && e.message);
  }
}

// --- first-run config --------------------------------------------------------
//
// grog needs `<config home>/grog.edn`, and the annotated examples that make it
// easy ship INSIDE the app bundle (electron-builder extraResources ->
// resources/config.examples) — for an AppImage, a read-only squashfs mount no
// user can browse. From a source tree they live in resources/config.examples.
//
// So the client SEEDS the config home on first run: it writes grog.edn (renamed
// from the example) plus the optional files as `*.example`, and names the exact
// path in the log. Nothing is asked and nothing is overwritten, so a fresh
// install — OR a factory reset — is left with a real file to edit instead of an
// empty directory. grog still runs on built-in defaults until you edit it, so
// expect model/provider errors until :llm :url, :llm :model and an API key are
// set; restart grog afterwards.
function offerConfigCreation() {
  let plan;
  try {
    plan = configSeed.planOffer({ resourcesPath: CONFIG_EXAMPLES_ROOT });
  } catch (e) {
    console.warn("[grog-client] could not inspect the config home:", e && e.message);
    return;
  }
  if (!plan.needsMain) return;   // already configured: say nothing, change nothing

  try {
    const written = configSeed.createConfig(plan);
    console.log("[grog-client] no grog.edn yet - seeded the config home from the example:");
    for (const f of written) console.log(`  ${f}`);
    console.log("[grog-client] fresh grog: requested getting-started onboarding (marker written)");
    console.log(`[grog-client] edit ${plan.grogEdn} - at least :llm :url, :llm :model and an`
      + ` API key - then restart grog; until then it cannot reach a model.`);
    if (plan.optional.length) {
      console.log(`[grog-client] optional examples (odoo, imap, gitlab, imaging, secrets) are in`
        + ` ${plan.home} as *.example - copy the ones you need.`);
    }
  } catch (e) {
    console.error("[grog-client] could not seed the config:", e && e.message);
  }
}

ipcMain.handle("grog:call", (_e, method, params) => call(method, params));
ipcMain.handle("grog:focused", () => !!(win && win.isFocused()));
ipcMain.handle("grog:socket-path", () => SOCKET_PATH);
// Open a documentation file that ships INSIDE the app bundle (the onboarding
// page and, later, the desktop entry point at these). `rel` is docs-relative and
// is CONFINED to DOCS_DIR, so the renderer cannot ask us to open arbitrary paths.
// An empty/absent `rel` opens the docs folder itself.
ipcMain.handle("grog:open-doc", (_e, rel) => {
  const base = path.resolve(DOCS_DIR);
  const target = rel ? path.resolve(base, String(rel)) : base;
  if (target !== base && !target.startsWith(base + path.sep)) {
    throw new Error("refusing to open outside the docs directory");
  }
  return shell.openPath(target);
});
// Native file picker for the composer's attach button. Returns absolute paths;
// the renderer turns each into an ECA FileContext ({type:"file", path}). Multiple
// selection allowed. Cancelled → [].
ipcMain.handle("grog:pick-files", async () => {
  const parent = win && !win.isDestroyed() ? win : undefined;
  const res = await dialog.showOpenDialog(parent, {
    title: "Attach files",
    properties: ["openFile", "multiSelections", "dontAddToRecent"],
  });
  return res.canceled ? [] : res.filePaths;
});
ipcMain.handle("grog:voice-status", () => voiceStatus());
ipcMain.handle("grog:voice-transcribe", (_e, bytes) => transcribe(bytes));

// Assistant images arrive as base64 in the transcript event, with no path. To
// let a user see one FULL SIZE (the in-app <img> is capped) or keep it, we write
// the bytes to disk: open → a temp file handed to the OS default handler
// (browser/image viewer → real pan+zoom); save → the Downloads folder, revealed.
const IMAGE_EXT = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif" };
function writeImageTo(dir, mediaType, base64) {
  const ext = IMAGE_EXT[mediaType] || "png";
  const file = path.join(dir, `grog-image-${Date.now()}.${ext}`);
  fs.writeFileSync(file, Buffer.from(String(base64), "base64"));
  return file;
}
ipcMain.handle("grog:open-image", async (_e, mediaType, base64) => {
  const file = writeImageTo(app.getPath("temp"), mediaType, base64);
  const err = await shell.openPath(file);
  if (err) throw new Error(err);
  return file;
});
ipcMain.handle("grog:save-image", (_e, mediaType, base64) => {
  const file = writeImageTo(app.getPath("downloads"), mediaType, base64);
  shell.showItemInFolder(file);
  return file;
});
// Open an absolute path the renderer already knows about — an assistant image
// that the SPINE wrote into the project's images/ dir (grog:call "save-image").
// Deliberately simple (the renderer is our own code), but it refuses to open
// something that is not there, so a stale path reports an error instead of
// silently doing nothing.
ipcMain.handle("grog:open-path", async (_e, p) => {
  const target = String(p || "");
  if (!target || !fs.existsSync(target)) throw new Error(`no such file: ${target}`);
  const err = await shell.openPath(target);
  if (err) throw new Error(err);
  return target;
});
// Dump the whole session to a standalone .html and open it in the browser — the
// "hand it to somebody" artifact. The renderer BUILDS the HTML (pure,
// grog-web.export); main owns the filesystem and the browser. No dialog:
// ~/grog-sessions/<project>-<yyyyMMdd-HHmm>.html.
function htmlStamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}
ipcMain.handle("grog:export-html", async (_e, payload) => {
  const { project, html } = payload || {};
  const dir = path.join(os.homedir(), "grog-sessions");
  const safe = String(project || "session").replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "") || "session";
  const file = path.join(dir, `${safe}-${htmlStamp()}.html`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, String(html || ""), "utf8");
  const err = await shell.openPath(file);   // .html -> the default browser
  if (err) throw new Error(err);
  return file;
});

// Chromium's PRIVATE profile — HTTP cache, GPU cache, Code Cache, Local
// Storage (the font-size pref), cookies, network state — must NOT go to
// Electron's default `%APPDATA%\grog` (ROAMING). It is machine-specific and
// cache-heavy, so on a domain machine a roaming profile would sync megabytes of
// it at every logon. grog's own config deliberately lives elsewhere
// (`%USERPROFILE%\.config\grog`); this is not that. Local AppData is the right
// home. Must run before the app is ready (the path is read when the first
// session is created).
if (process.platform === "win32") {
  try {
    const localData = path.join(app.getPath("localAppData"), "grog");
    app.setPath("userData", localData);
    console.log(`[grog-client] profile dir: ${localData}`);
  } catch (e) {
    console.warn("[grog-client] could not relocate userData (staying in Roaming):", e.message);
  }
  // Give the process the same AppUserModelID the installer's shortcut uses
  // (package.json `appId`). Without it the taskbar groups/identifies the window
  // by electron.exe, which muddies the pinned icon and jump list.
  try {
    app.setAppUserModelId("dev.grog.client");
  } catch (e) {
    console.warn("[grog-client] could not set AppUserModelId:", e.message);
  }
}

// A VM (or any box without working GPU acceleration) can hard-fail Electron's
// compositor before a window ever appears; GROG_DISABLE_GPU=1 forces the safe
// software path. Must be called before the app is ready.
if (process.env.GROG_DISABLE_GPU === "1") {
  try {
    app.disableHardwareAcceleration();
    console.log("[grog-client] GPU acceleration disabled (GROG_DISABLE_GPU=1)");
  } catch (e) {
    console.warn("[grog-client] disableHardwareAcceleration failed:", e.message);
  }
} else {
  console.log("[grog-client] GPU acceleration enabled (if the window is black,"
    + " relaunch with GROG_DISABLE_GPU=1 or --disable-gpu)");
}

app.whenReady().then(() => {
  // No default menu: its accelerators (Ctrl+W close-window, Ctrl+± page zoom,
  // Ctrl+0) would shadow the client's own shortcuts (close-tab, font-zoom).
  Menu.setApplicationMenu(null);
  // Allow microphone capture for the renderer. Chromium asks; Electron grants
  // by default, but be explicit so getUserMedia can't silently fail.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) =>
    cb(permission === "media"));
  // Seed the config home FIRST: it writes grog.edn and — on a fresh home — the
  // onboarding marker the spine reads at `startup`. It must land before the
  // spine is spawned and the renderer asks which project to open.
  offerConfigCreation();
  connect();
  createWindow();
  // The desktop-integration prompt is parented to the window.
  maybeOfferDesktopIntegration();
});
// Kill the spine AND everything it spawned. Necessary because the MCP servers
// are GRANDCHILDREN: spine (java) -> eca -> bash -> java. Killing only the spine
// pid leaves those running, and being orphaned they also keep whatever handles
// they inherited — including our log file, which then makes the NEXT launch's
// stdout redirect fail (cmd will not run a command whose redirect failed, so
// the app "closes right after launch"). Measured: 32 leftover JVMs, ~5.4 GB.
function killSpineTree(pid) {
  if (!pid) return;
  try {
    if (process.platform === "win32") {
      spawnSync("taskkill", ["/pid", String(pid), "/T", "/F"], { windowsHide: true });
    } else {
      // The spine was spawned detached, so -pid addresses its whole group.
      try { process.kill(-pid, "SIGTERM"); }
      catch { try { process.kill(pid, "SIGTERM"); } catch {} }
      setTimeout(() => { try { process.kill(-pid, "SIGKILL"); } catch {} }, 2000);
    }
  } catch { /* already gone */ }
}

// One teardown, used by every exit path. Closing stdin is the DESIGNED
// shutdown (the spine sees EOF, closes sessions, releases locks, exits); the
// tree kill is the fallback for a slow teardown and for the grandchild MCP JVMs
// that neither Windows nor POSIX cascades to.
function shutdown(reason) {
  if (sock) sock.end();
  const pid = spine && spine.pid;
  console.log(`[grog-client] shutdown (${reason})`);
  if (!pid) { LOG.close(); app.quit(); return; }
  setTimeout(() => {
    killSpineTree(pid);
    LOG.close();          // flush the log before the process goes away
    app.quit();
  }, 2500);
}

app.on("window-all-closed", () => shutdown("window-all-closed"));

// A signalled client (Ctrl-C in the launcher console, a test harness, a task
// manager kill) must ALSO reap — the spine leads its own process group, so a
// bare SIGTERM to Electron would leave it (and its MCP JVMs, and any inherited
// handles) orphaned. Without this, scripts/electron-smoke.js left orphans.
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
