// grog web client — Electron main: the CLIENT HOST (attach mode).
//
// Client 2 does NOT spawn grog-server. Per the multi-client layout
// (doc/multi-client-layout.md §5), the daemon owns ECA/MCP/projects and clients
// ATTACH — over the server's Unix-domain socket (grog.server). This process
// holds that socket and bridges it to the renderer over IPC; it also forwards
// window focus, which the question-visibility rule needs
// (doc/clients/web-client-plan.md §3.4.1).
//
// If no server is listening, we say so loudly (a visible "unreachable" state)
// and keep retrying — never fill the gap by spawning a second server (that
// double-claims project locks).
const { app, BrowserWindow, ipcMain, Menu, session } = require("electron");
const net = require("net");
const os = require("os");
const path = require("path");
const fs = require("fs");
const { execFile } = require("child_process");

// Same rendezvous the server computes (grog.server/default-socket-path):
// GROG_SERVER_SOCKET wins; else $XDG_RUNTIME_DIR/grog-$USER.sock; else tmp.
const SOCKET_PATH =
  process.env.GROG_SERVER_SOCKET ||
  path.join(process.env.XDG_RUNTIME_DIR || os.tmpdir(),
            `grog-${process.env.USER || "user"}.sock`);

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
    path: SOCKET_PATH,
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
  connecting = true;
  // TCP on a KNOWN host:port — a unix socket can't serve other machines.
  // GROG_SERVER_PORT / GROG_SERVER_HOST override; 9640 matches grog.server.
  const s = net.connect(Number(process.env.GROG_SERVER_PORT || 9640),
                        process.env.GROG_SERVER_HOST || "127.0.0.1");
  sock = s;
  s.on("connect", () => {
    connecting = false;
    connected = true;
    // reset backoff — next drop starts fast again
    retryDelay = RETRY_MIN_MS;
    retryAttempts = 0;
    nextRetryAt = 0;
    console.log(`[grog-client] attached to ${SOCKET_PATH}`);
    notifyRenderer();
  });
  s.on("data", (b) => {
    buf += b.toString("utf8");
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      if (!line.trim()) continue;
      let msg; try { msg = JSON.parse(line); } catch (e) { continue; }
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message || "server error"));
        else resolve(msg.result);
      } else if (msg.method) {
        send(msg.method, msg.params);
      }
    }
  });
  const lost = (err) => {
    connecting = false;
    if (connected || err) {
      console.log(`[grog-client] disconnected from ${SOCKET_PATH}: ${err ? err.message : "closed"}`);
    }
    connected = false;
    sock = null;
    buf = "";
    rejectAll(`grog-server unreachable at ${SOCKET_PATH}`);
    notifyRenderer();
    send("server-down", { text: `[grog] grog-server unreachable at ${SOCKET_PATH}` });
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

const call = (method, params) => new Promise((resolve, reject) => {
  if (!connected || !sock) {
    connect();
    return reject(new Error(`grog-server unreachable at ${SOCKET_PATH}`));
  }
  const id = nextId++;
  pending.set(id, { resolve, reject });
  sock.write(JSON.stringify({ jsonrpc: "2.0", id, method, params: params || {} }) + "\n");
});

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
  win.webContents.on("console-message", (_e, level, message, line, source) =>
    console.log(`[renderer:${level}] ${message} (${source}:${line})`));
  win.webContents.on("did-fail-load", (_e, code, desc, url) =>
    console.log(`[renderer] did-fail-load ${code} ${desc} ${url}`));
  win.webContents.on("did-finish-load", () => {
    console.log("[renderer] loaded ok");
    notifyRenderer();   // let the renderer paint the current attach state
  });
  // Dev: load from the shadow-cljs dev server (it serves resources/public and
  // /js with hot reload). Prod: load the built file directly.
  const devUrl = process.env.GROG_WEB_DEV_URL ||
    (process.env.NODE_ENV === "production" ? null : "http://localhost:9633");
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
app.on("window-all-closed", () => { if (sock) sock.end(); app.quit(); });
