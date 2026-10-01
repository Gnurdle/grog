#!/usr/bin/env node
// electron-smoke.js -- headless regression test for the Electron MAIN process.
//
// Spawns the real client main, in production mode, with the GPU disabled, and
// asserts it actually came up:
//   1. "[grog-client] transport up"  -- the spine child spawned and answered
//   2. "[renderer] loaded ok"        -- the built index.html + bundle loaded
// then kills the tree and exits 0/non-zero. No LLM key needed: this proves the
// wiring (spawn, transport, renderer), which is what broke twice on Windows
// (a missing `java`, and a locked log-redirect that stopped Electron starting).
//
// Needs a display. On a headless box use:  xvfb-run -a node scripts/electron-smoke.js
//
// Usage: node scripts/electron-smoke.js [--timeout-ms N] [--keep]
const { spawn, spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const WIN = process.platform === "win32";
const root = path.resolve(__dirname, "..");
const web = path.join(root, "clients", "web");
const electronBin = path.join(web, "node_modules", "electron", "dist", WIN ? "electron.exe" : "electron");

function argNum(flag, dflt) {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? Number(process.argv[i + 1]) : dflt;
}
const TIMEOUT_MS = argNum("--timeout-ms", 60000);
const KEEP = process.argv.includes("--keep");

function fail(msg) { console.error("SMOKE FAIL: " + msg); process.exit(1); }

if (!fs.existsSync(electronBin)) fail("electron binary not found at " + electronBin + " (run: npm install in clients/web)");
if (!fs.existsSync(path.join(web, "resources", "public", "js", "main.js"))) fail("renderer bundle missing (run: npm run build)");
if (!fs.existsSync(path.join(root, "target", "grog-spine.jar")) && !WIN) {
  console.warn("warn: target/grog-spine.jar missing — the spine will fall back to `clojure -M -m grog.server`");
}
if (!WIN && !process.env.DISPLAY) {
  console.warn("warn: no DISPLAY — Electron needs one; try: xvfb-run -a node scripts/electron-smoke.js");
}

const seen = { transport: false, renderer: false };
const out = [];
const child = spawn(electronBin, ["."], {
  cwd: web,
  env: Object.assign({}, process.env, {
    NODE_ENV: "production",
    GROG_DISABLE_GPU: "1",
    GROG_MCP_BASE_PORT: "9940",
  }),
  windowsHide: true,
  detached: !WIN,   // POSIX: own process group so killTree can reap the spine + MCP grandchildren
});

let spinePid = null;
function onChunk(b) {
  const s = b.toString("utf8");
  out.push(s);
  if (/\[grog-client\] transport up/.test(s)) seen.transport = true;
  const m = s.match(/transport up: child pid (\d+)/);
  if (m) spinePid = Number(m[1]);
  if (/\[renderer\] loaded ok/.test(s)) seen.renderer = true;
}
child.stdout.on("data", onChunk);
child.stderr.on("data", onChunk);
child.on("error", (e) => fail("spawn error: " + e.message));

const t = setTimeout(() => {
  console.error("--- captured output (tail) ---");
  console.error(out.join("").split("\n").slice(-40).join("\n"));
  fail(`timed out after ${TIMEOUT_MS}ms (transport=${seen.transport} renderer=${seen.renderer})`);
}, TIMEOUT_MS);

// poll so we can pass as soon as BOTH markers appear
const poll = setInterval(() => {
  if (seen.transport && seen.renderer) {
    clearInterval(poll); clearTimeout(t);
    console.log("SMOKE OK: transport up + renderer loaded");
    if (KEEP) return;
    killTree(child.pid);
    // Let the client run its own teardown (main.js reaps the spine tree on
    // SIGTERM) before we exit; force-exit only if it overstays.
    const bye = setTimeout(() => process.exit(0), 9000);
    child.on("exit", (code) => { clearTimeout(bye); setTimeout(() => process.exit(code == null ? 0 : code), 250); });
  }
}, 250);

// The spine is spawned DETACHED by main.js, so it is in its own process group:
// signalling Electron's group alone would leave it (and its MCP JVMs) behind.
// Kill both groups explicitly.
function killTree(pid) {
  const groups = [pid, spinePid].filter(Boolean);
  for (const g of groups) {
    try {
      if (WIN) spawnSync("taskkill", ["/pid", String(g), "/T", "/F"], { windowsHide: true });
      else { try { process.kill(-g, "SIGTERM"); } catch { try { process.kill(g, "SIGTERM"); } catch {} } }
    } catch {}
  }
}
process.on("exit", () => { if (!KEEP) killTree(child.pid); });
