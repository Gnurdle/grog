// grog web client — main-process logging.
//
// ONE FILE PER CLIENT INSTANCE, at the path grog documents:
//
//   <base>.<pid>.log
//
// `<base>` is resolved from the ENVIRONMENT. The desktop client's main process
// deliberately does NOT parse grog.edn: reproducing grog's four-file EDN merge
// in JS is a lot of risk for a log path, so env vars are the supported channel
// (the `:log {:dir … :keep …}` config key is not consulted here):
//
//   1. $GROG_LOG (a trailing `.log` is stripped)
//   2. otherwise `~/grog` (%USERPROFILE%\grog on Windows)
//
// Rotation keeps the newest `GROG_UI_LOG_KEEP` (default 5) instances.
//
// WHY THE CLIENT WRITES IT: the spine's own stderr is the richest diagnostic
// source (config trace, ECA, MCP), and the messages you most need are the ones
// emitted when the spine *never starts* — so the writer cannot be the spine.
// The main process already receives both streams, and it exists in every case.
const fs = require("fs");
const os = require("os");
const path = require("path");
const util = require("util");

function logBase() {
  const raw = String(process.env.GROG_LOG || "").trim();
  if (raw) return raw.toLowerCase().endsWith(".log") ? raw.slice(0, -4) : raw;
  return path.join(os.homedir(), "grog");
}

function keepCount() {
  const n = parseInt(process.env.GROG_UI_LOG_KEEP || "5", 10);
  return Number.isFinite(n) && n > 0 ? n : 5;
}

function prune(base, keep) {
  try {
    const dir = path.dirname(base);
    const stem = path.basename(base).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const mine = new RegExp(`^${stem}\\.\\d+\\.log$`);
    fs.readdirSync(dir)
      .filter((f) => mine.test(f))
      .map((f) => ({ f, t: fs.statSync(path.join(dir, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t)
      .slice(Math.max(0, keep))
      .forEach(({ f }) => { try { fs.unlinkSync(path.join(dir, f)); } catch (_) {} });
  } catch (_) { /* rotation is best effort, never fatal */ }
}

function install() {
  const base = logBase();
  const file = `${base}.${process.pid}.log`;
  let stream = null;
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    // Open the file FIRST and SYNCHRONOUSLY, so it already exists on disk (and
    // thus counts as the newest) when we prune. createWriteStream opens
    // asynchronously, so relying on it here makes readdir race the open — the
    // prune then either can't see our file (keeps one too many) or counts it
    // (keeps one too few). Opening the fd first pins the file (and its fresh
    // mtime) on disk before the directory is scanned.
    fs.closeSync(fs.openSync(file, "a"));
    prune(base, keepCount());
    stream = fs.createWriteStream(file, { flags: "a" });
  } catch (_) {
    stream = null; // logging must never prevent the app from starting
  }

  const write = (line) => {
    if (!stream) return;
    try {
      const s = typeof line === "string" ? line : util.inspect(line);
      stream.write(s.endsWith("\n") ? s : s + "\n");
    } catch (_) { /* ignore */ }
  };

  // Tee the main process's own console output. Wrapping console is deliberate:
  // every existing console.log/error becomes part of the file without touching
  // each call site.
  const tee = () => {
    for (const kind of ["log", "error", "warn"]) {
      const orig = console[kind].bind(console);
      console[kind] = (...args) => {
        orig(...args);
        write(args.map((a) => (typeof a === "string" ? a : util.inspect(a))).join(" "));
      };
    }
  };

  write(`=== grog client launch: ${new Date().toISOString()} pid=${process.pid} log=${file} ===`);

  return {
    path: file,
    write,
    tee,
    // The spine's stdout is the JSON-RPC wire (NDJSON): machine protocol, not
    // diagnostic text, and high volume. Off by default; GROG_LOG_WIRE=1 when
    // you need to see exactly what crossed it.
    wire: process.env.GROG_LOG_WIRE === "1",
    close: () => { try { if (stream) stream.end(); } catch (_) {} },
  };
}

module.exports = { install, logBase };
