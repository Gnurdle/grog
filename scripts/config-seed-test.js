#!/usr/bin/env node
// config-seed-test.js — unit test for clients/web/src/main/config-seed.js.
//
// Run: node scripts/config-seed-test.js
//
// Why a unit test rather than an app run: main.js seeds the config home on
// first run from the example directory (packaged or source tree). This
// exercises the real planning + writing against a fake resources dir and
// config home.
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { configHome, availableExamples, planOffer, createConfig } =
  require("../clients/web/src/main/config-seed");

let pass = 0;
let fail = 0;
function check(name, fn) {
  try {
    fn();
    pass++;
    console.log("ok   " + name);
  } catch (e) {
    fail++;
    console.log("FAIL " + name + ": " + e.message);
  }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "grog-cfgseed-"));
const res = path.join(tmp, "resources");
const home = path.join(tmp, "home");
fs.mkdirSync(path.join(res, "config.examples"), { recursive: true });
const ex = (n, body) => fs.writeFileSync(path.join(res, "config.examples", n), body);
ex("grog.edn.example", "{:llm {:url \"\" :model \"\"}}\n");
ex("odoo-instances.edn.example", "{:instances []}\n");
ex("secrets.edn.example", "{}\n");
fs.writeFileSync(path.join(res, "config.examples", "README.md"), "not an example\n");

// --- configHome resolution (mirrors grog.platform/config-home-dir) ----------
check("GROG_CONFIG_HOME wins", () =>
  assert.strictEqual(configHome({ GROG_CONFIG_HOME: "/x/y" }), path.resolve("/x/y")));
check("GROG_CONFIG_HOME ~ expands", () =>
  assert.strictEqual(configHome({ GROG_CONFIG_HOME: "~/z" }), path.join(os.homedir(), "z")));
check("blank GROG_CONFIG_HOME falls through to XDG", () =>
  assert.strictEqual(configHome({ GROG_CONFIG_HOME: "  ", XDG_CONFIG_HOME: "/xdg" }), path.join("/xdg", "grog")));
check("default is ~/.config/grog (Windows included)", () =>
  assert.strictEqual(configHome({}), path.join(os.homedir(), ".config", "grog")));

// --- what ships ------------------------------------------------------------
check("availableExamples ignores README.md and maps target names", () => {
  const a = availableExamples(res);
  assert.deepStrictEqual(a.map((e) => e.name), ["grog.edn.example", "odoo-instances.edn.example", "secrets.edn.example"]);
  assert.deepStrictEqual(a.map((e) => e.target), ["grog.edn", "odoo-instances.edn", "secrets.edn"]);
  assert.ok(a[0].from.endsWith(path.join("config.examples", "grog.edn.example")));
});
check("no config.examples in the package -> []", () =>
  assert.deepStrictEqual(availableExamples(path.join(tmp, "nothing")), []));

// --- planning (must write nothing) -----------------------------------------
check("planOffer: missing grog.edn -> needsMain", () => {
  const p = planOffer({ resourcesPath: res, configHome: home });
  assert.strictEqual(p.needsMain, true);
  assert.strictEqual(p.grogEdn, path.join(home, "grog.edn"));
  assert.deepStrictEqual(p.optional.map((e) => e.name), ["odoo-instances.edn.example", "secrets.edn.example"]);
  assert.ok(!fs.existsSync(home), "planOffer must not create the config home");
});
check("planOffer: existing grog.edn -> no offer", () => {
  fs.mkdirSync(home, { recursive: true });
  fs.writeFileSync(path.join(home, "grog.edn"), "{}\n");
  assert.strictEqual(planOffer({ resourcesPath: res, configHome: home }).needsMain, false);
  fs.rmSync(path.join(home, "grog.edn"));
});
check("planOffer: no examples shipped -> no offer", () => {
  const p = planOffer({ resourcesPath: path.join(tmp, "nothing"), configHome: home });
  assert.strictEqual(p.needsMain, false);
  assert.strictEqual(p.mainExample, null);
});

// --- writing ---------------------------------------------------------------
check("createConfig writes grog.edn + the optional *.example files", () => {
  const p = planOffer({ resourcesPath: res, configHome: home });
  const w = createConfig(p);
  assert.strictEqual(w.length, 3);
  assert.ok(fs.existsSync(path.join(home, "grog.edn")));
  assert.ok(fs.existsSync(path.join(home, "odoo-instances.edn.example")));
  assert.ok(fs.existsSync(path.join(home, "secrets.edn.example")));
  assert.ok(!fs.existsSync(path.join(home, "README.md")), "README.md must not be copied");
  assert.strictEqual(fs.readFileSync(path.join(home, "grog.edn"), "utf8"), "{:llm {:url \"\" :model \"\"}}\n");
});
check("createConfig never overwrites an existing file", () => {
  fs.writeFileSync(path.join(home, "grog.edn"), "MY EDITS\n");
  const w = createConfig(planOffer({ resourcesPath: res, configHome: home }));
  assert.deepStrictEqual(w, []);
  assert.strictEqual(fs.readFileSync(path.join(home, "grog.edn"), "utf8"), "MY EDITS\n");
});
check("createConfig creates the config home if absent", () => {
  const fresh = path.join(tmp, "fresh-home");
  const w = createConfig(planOffer({ resourcesPath: res, configHome: fresh }));
  assert.strictEqual(w.length, 3);
  assert.ok(fs.existsSync(path.join(fresh, "grog.edn")));
});

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\nconfig-seed-test: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
