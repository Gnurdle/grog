#!/usr/bin/env node
// log-test.js -- unit test for clients/web/src/main/log.js.
//
// Pure resolution + rotation logic, no Electron and no window: proves the
// rules the docs promise (env base + trailing-.log strip, keep-N pruning, a
// nil stream on an unwritable path). The pruning has an easy-to-regress
// off-by-one/race (the file must be pinned on disk before the directory is
// scanned), so it gets its own test.
//
// Usage: node scripts/log-test.js
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");

const log = require(path.join(__dirname, "..", "clients", "web", "src", "main", "log.js"));
let n = 0;
const ok = (m) => { n++; console.log("  ok  " + m); };

(async () => {
  // --- logBase resolution ---------------------------------------------------
  delete process.env.GROG_LOG;
  assert.strictEqual(log.logBase(), path.join(os.homedir(), "grog"));
  ok("default base = ~/grog");

  process.env.GROG_LOG = "/var/log/mygrog";
  assert.strictEqual(log.logBase(), "/var/log/mygrog");
  ok("$GROG_LOG honored verbatim");

  process.env.GROG_LOG = "/var/log/mygrog.log";
  assert.strictEqual(log.logBase(), "/var/log/mygrog");
  ok("trailing .log stripped");

  process.env.GROG_LOG = "  /tmp/spaced.log  ";
  assert.strictEqual(log.logBase(), "/tmp/spaced");
  ok("whitespace trimmed + .log stripped");

  // --- prune: keep newest GROG_UI_LOG_KEEP ----------------------------------
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "grog-logtest-"));
  const base = path.join(dir, "grog");
  for (let i = 0; i < 8; i++) {
    const p = `${base}.${1000 + i}.log`;
    fs.writeFileSync(p, "x");
    const t = new Date(Date.now() - (100 - i) * 1000); // i=7 newest of these
    fs.utimesSync(p, t, t);
  }
  fs.writeFileSync(`${base}.notapid.log`, "keep me");

  process.env.GROG_LOG = base;
  process.env.GROG_UI_LOG_KEEP = "5";
  const inst = log.install();
  const left = fs.readdirSync(dir).filter((f) => /^grog\.\d+\.log$/.test(f)).sort();
  assert.strictEqual(left.length, 5, `expect 5 kept, got ${left.length}: ${left}`);
  assert.ok(left.includes(path.basename(inst.path)), "our own file survives");
  assert.ok(fs.existsSync(path.join(dir, "grog.notapid.log")), "decoy untouched");
  ok("prune keeps GROG_UI_LOG_KEEP (5), own file + decoy intact");

  // The stream buffers asynchronously, so read only after close() has flushed.
  inst.write("hello-from-test");
  inst.write("no-newline-appended");
  inst.close();
  await new Promise((r) => setTimeout(r, 150));
  const body = fs.readFileSync(inst.path, "utf8");
  assert.ok(/=== grog client launch:/.test(body), "banner written");
  assert.ok(/pid=\d+/.test(body), "pid in banner");
  ok("launch banner written");
  assert.ok(/\nhello-from-test\n/.test(body), "write() newline-terminated");
  assert.ok(/no-newline-appended\n$/.test(body), "write() appends newline");
  ok("write() appends newline-terminated lines");

  // --- GROG_UI_LOG_KEEP=1 keeps only the current instance --------------------
  const dir2 = fs.mkdtempSync(path.join(os.tmpdir(), "grog-logtest2-"));
  const base2 = path.join(dir2, "grog");
  for (let i = 0; i < 4; i++) {
    const p = `${base2}.${2000 + i}.log`;
    fs.writeFileSync(p, "x");
    const t = new Date(Date.now() - (50 - i) * 1000);
    fs.utimesSync(p, t, t);
  }
  process.env.GROG_LOG = base2;
  process.env.GROG_UI_LOG_KEEP = "1";
  const inst2 = log.install();
  const left2 = fs.readdirSync(dir2).filter((f) => /^grog\.\d+\.log$/.test(f));
  assert.strictEqual(left2.length, 1, `keep=1 -> only ours, got ${left2.length}`);
  assert.strictEqual(path.basename(inst2.path), left2[0]);
  inst2.close();
  ok("GROG_UI_LOG_KEEP=1 keeps only the current instance");

  // --- unwritable path must NOT throw ---------------------------------------
  // A FILE used as a parent directory: mkdirSync throws ENOTDIR deterministically.
  const notADir = path.join(os.tmpdir(), `grog-logtest-file-${process.pid}`);
  fs.writeFileSync(notADir, "im a file");
  process.env.GROG_LOG = path.join(notADir, "grog");
  const inst3 = log.install();     // must not throw
  inst3.write("noop");
  inst3.close();
  fs.unlinkSync(notADir);
  ok("unwritable path -> null stream, no throw");

  console.log(`\nALL ${n} LOG-JS CHECKS PASS`);
})().catch((e) => { console.error("TEST FAIL:", e.message); process.exit(1); });
