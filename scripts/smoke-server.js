#!/usr/bin/env node
// grog server smoke driver.
//
// Spawns a grog server (stdio mode) and drives a DEEP RPC sequence — the paths a
// shallow `projects`/`sessions` probe never reaches: session creation (spawns the
// ECA child), connect, trust, a real prompt, and close. Logs every response and
// event with timeouts, prints a PASS/FAIL summary, and exits non-zero on failure.
//
// Two uses:
//   1. smoke test:      node scripts/smoke-server.js ./target/native/grog-server
//                       node scripts/smoke-server.js clojure -M -m grog.server
//   2. native-image tracing-agent config generation — wrap the command:
//      node scripts/smoke-server.js java \
//        -agentlib:native-image-agent=config-output-dir=native-image \
//        -cp "$(clojure -Spath -M:native)" clojure.main -m grog.server
//
// Env it sets for the child: GROG_SERVER_NO_SOCKET=1, GROG_MCP_BASE_PORT=9730;
// it UNSETS GROG_SERVER_DAEMON/SOCKET so we always get stdio mode (a daemon
// never exits and the smoke run would hang). SMOKE_PROJECT picks the scratch
// project (default ni-smoke). SMOKE_LABEL names the log at /tmp/smoke-<label>.log.
const { spawn } = require("child_process");
const fs = require("fs");

const cmd = process.argv.slice(2);
if (!cmd.length) { console.error("usage: node smoke-server.js <cmd...>"); process.exit(2); }

const label = process.env.SMOKE_LABEL || "smoke";
const project = process.env.SMOKE_PROJECT || "ni-smoke";
const promptText = process.env.SMOKE_PROMPT || "Reply with exactly the single word: pong";

const env = Object.assign({}, process.env, { GROG_SERVER_NO_SOCKET: "1", GROG_MCP_BASE_PORT: process.env.SMOKE_MCP_PORT || "9730" });
delete env.GROG_SERVER_DAEMON;
delete env.GROG_SERVER_SOCKET;

const log = fs.createWriteStream(`/tmp/smoke-${label}.log`);
const say = (s) => { console.log(s); log.write(s + "\n"); };

const child = spawn(cmd[0], cmd.slice(1), { env, stdio: ["pipe", "pipe", "pipe"] });

let buf = "", nextId = 1, answered = null, sawIdle = false;
const pending = new Map();

function send(method, params) {
  const id = nextId++;
  const p = new Promise((res) => pending.set(id, res));
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params: params || {} }) + "\n");
  say(`>>> #${id} ${method} ${JSON.stringify(params || {})}`);
  return p;
}

child.stdout.on("data", (b) => {
  buf += b.toString("utf8");
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i); buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let m; try { m = JSON.parse(line); } catch { say("<<< (unparseable) " + line.slice(0, 200)); continue; }
    if (m.id && pending.has(m.id)) {
      const res = pending.get(m.id); pending.delete(m.id);
      if (m.error) { say(`<<< #${m.id} ERROR ${JSON.stringify(m.error)}`); res({ __error: m.error }); }
      else { say(`<<< #${m.id} OK`); res(m.result); }
    } else if (m.method === "event") {
      const p = m.params || {};
      if (p.type === "content") {
        const c = p.content || {};
        say(`<<< EVENT content type=${c.type} ${JSON.stringify(String(c.text || c.summary || "")).slice(0, 140)}`);
        if (c.type === "text" && /pong/i.test(String(c.text || ""))) answered = String(c.text);
      } else {
        say(`<<< EVENT ${p.type} ${JSON.stringify(p).slice(0, 160)}`);
        if (p.type === "status" && /idle/i.test(String(p.value))) sawIdle = true;
      }
    } else {
      say(`<<< NOTIFY ${m.method} ${JSON.stringify(m.params || {}).slice(0, 160)}`);
    }
  }
});
child.stderr.on("data", (b) => log.write("[stderr] " + b.toString("utf8")));
child.on("exit", (code, sig) => say(`\n=== child exit code=${code} sig=${sig} ===`));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const timeout = (ms, what) => new Promise((_, rej) => setTimeout(() => rej(new Error(`TIMEOUT ${what} (${ms}ms)`)), ms));

(async () => {
  const R = {};
  const step = async (name, fn, ms) => {
    try { R[name] = await Promise.race([fn(), timeout(ms || 30000, name)]); }
    catch (e) { R[name] = "FAIL: " + e.message; }
  };
  try {
    await step("projects", async () => {
      const pr = await send("projects");
      return pr && pr.__error ? "ERR " + JSON.stringify(pr.__error) : `ok (${(pr || []).length} projects)`;
    }, 20000);
    // exercise the JNI-backed libs (sqlite-jdbc, java-keyring) so a tracing-agent
    // run records their reflection config for the native image
    await step("debug/jni", async () => {
      const r = await send("debug/jni");
      return r && r.__error ? "ERR " + JSON.stringify(r.__error) : JSON.stringify(r).slice(0, 220);
    }, 20000);
    await step("create-project", async () => {
      const r = await send("create-project", { name: project });
      return r && r.__error ? "ERR " + JSON.stringify(r.__error) : "ok";
    }, 20000);

    let id = null;
    await step("open", async () => {
      const snap = await send("open", { project });
      if (snap && snap.__error) return "ERR " + JSON.stringify(snap.__error);
      id = snap && snap.id;
      return `ok id=${id} model=${snap && snap.model} status=${snap && snap.status}`;
    }, 150000);

    if (id) {
      await step("connect", async () => { const r = await send("connect", { id }); return r && r.__error ? "ERR " + JSON.stringify(r.__error) : "ok"; }, 30000);
      await step("set-trust", async () => { const r = await send("set-trust", { id, on: true }); return r && r.__error ? "ERR " + JSON.stringify(r.__error) : "ok"; }, 15000);
      await step("prompt", async () => { const r = await send("prompt", { id, text: promptText }); return r && r.__error ? "ERR " + JSON.stringify(r.__error) : "sent"; }, 15000);
      const deadline = Date.now() + 120000;
      while (Date.now() < deadline && !answered) await sleep(500);
      R.answer = answered ? `ok: ${JSON.stringify(answered).slice(0, 100)}` : "FAIL: no 'pong' text within 120s";
      await step("close", async () => { const r = await send("close", { id }); return r && r.__error ? "ERR " + JSON.stringify(r.__error) : "ok"; }, 20000);
    } else {
      for (const k of ["connect", "set-trust", "prompt", "answer", "close"]) R[k] = "SKIPPED (no session)";
    }
  } catch (e) { R.FATAL = e.message; }

  say("\n=== SUMMARY (" + label + ") ===");
  for (const [k, v] of Object.entries(R)) say(`  ${k}: ${v}`);
  const failed = Object.entries(R).filter(([, v]) => /FAIL|ERR/.test(String(v)));
  say(failed.length ? `\n=== ${failed.length} STEP(S) FAILED ===` : "\n=== ALL STEPS OK ===");
  say(`(idle status seen: ${sawIdle})`);
  try { child.stdin.end(); } catch {}
  await sleep(2000);
  try { child.kill("SIGTERM"); } catch {}
  log.end();
  process.exit(failed.length ? 1 : 0);
})();
