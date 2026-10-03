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
const { app, BrowserWindow, ipcMain, Menu, session } = require("electron");
const net = require("net");
const os = require("os");
const path = require("path");
const fs = require("fs");
const { execFile, spawn, spawnSync } = require("child_process");

// Logging first, so everything after it (including a failed spawn) is captured.
// One file per client instance: $GROG_LOG -> else ~/grog, as <base>.<pid>.log.
const log = require("./log");
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

// A client-owned spine: no TCP socket, no MCP HTTP endpoint (tools reach ECA
// through the generated config instead), and its own MCP port range.
const SPINE_ENV_EXTRA = Object.assign(
  {
    GROG_SERVER_NO_SOCKET: "1",
    GROG_MCP_HTTP: "0",
    GROG_MCP_BASE_PORT: process.env.GROG_MCP_BASE_PORT || "9800",
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

function voiceCommand() {
  // GROG_VOICE_COMMAND: a JSON array (["whisper-cli","-m",…,"{wav}"]) or a plain
  // string; "{wav}" is replaced with the recorded file. Default: whisper-cli on
  // PATH, else the common local build, with the model under ~/.config/grog.
  const raw = process.env.GROG_VOICE_COMMAND;
  if (raw) {
    try {
      return (/^\s*\[/.test(raw) ? JSON.parse(raw) : raw.split(/\s+/))
        .map(String).filter(Boolean);
    } catch { return raw.split(/\s+/).map(String).filter(Boolean); }
  }
  const model = process.env.GROG_VOICE_MODEL ||
    path.join(os.homedir(), ".config", "grog", "models", "ggml-base.en.bin");
  const built = path.join(os.homedir(), "whisper.cpp", "build", "bin", "whisper-cli");
  const bin = fs.existsSync(built) ? built : "whisper-cli";
  return [bin, "-m", model, "-f", "{wav}", "-nt"];
}

function voiceStatus() {
  const argv = voiceCommand();
  return {
    enabled: VOICE_ENABLED && argv.length > 0,
    maxSeconds: VOICE_MAX_SECONDS,
    engine: argv.length ? path.basename(argv[0]) : null,
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

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 900, backgroundColor: "#020617",
    icon: path.join(__dirname, "..", "..", "resources", "public", "icon.png"),
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
  win.on("focus", () => { if (!win.isDestroyed()) win.webContents.send("grog:focus", true); });
  win.on("blur",  () => { if (!win.isDestroyed()) win.webContents.send("grog:focus", false); });
}

ipcMain.handle("grog:call", (_e, method, params) => call(method, params));
ipcMain.handle("grog:focused", () => !!(win && win.isFocused()));
ipcMain.handle("grog:socket-path", () => SOCKET_PATH);
ipcMain.handle("grog:voice-status", () => voiceStatus());
ipcMain.handle("grog:voice-transcribe", (_e, bytes) => transcribe(bytes));

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
  connect();
  createWindow();
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
