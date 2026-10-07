#!/usr/bin/env node
// desktop-install-test.js — unit test for clients/web/src/main/desktop-install.js.
//
// Run: node scripts/desktop-install-test.js
//
// The real thing only fires inside an AppImage, so the planning/writing logic is
// exercised here against fake XDG dirs, a fake AppImage path and a fake PATH.
"use strict";
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const d = require("../clients/web/src/main/desktop-install");

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

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "grog-desk-"));
const data = path.join(tmp, "data");
const home = path.join(tmp, "home");
const res = path.join(tmp, "resources");
fs.mkdirSync(res, { recursive: true });
fs.writeFileSync(path.join(res, "icon.png"), "PNG\n");
const APP = path.join(tmp, "Apps", "grog.AppImage");
const OPTS = { env: { APPIMAGE: APP, XDG_DATA_HOME: data }, resourcesPath: res, configHome: home };

// --- basics ----------------------------------------------------------------
check("isAppImage: null when unset, resolved when set", () => {
  assert.strictEqual(d.isAppImage({}), null);
  assert.strictEqual(d.isAppImage({ APPIMAGE: "" }), null);
  assert.strictEqual(d.isAppImage({ APPIMAGE: APP }), path.resolve(APP));
});
check("dataHome honours XDG_DATA_HOME, else ~/.local/share", () => {
  assert.strictEqual(d.dataHome({ XDG_DATA_HOME: data }), path.resolve(data));
  assert.strictEqual(d.dataHome({}), path.join(os.homedir(), ".local", "share"));
});
check("quoteExecArg escapes per the spec", () => {
  assert.strictEqual(d.quoteExecArg("/a/b.AppImage"), '"/a/b.AppImage"');
  assert.strictEqual(d.quoteExecArg("/My Apps/g.AppImage"), '"/My Apps/g.AppImage"');
  assert.strictEqual(d.quoteExecArg('/a"b$c`d\\e'), '"/a\\"b\\$c\\`d\\\\e"');
});
check("execLine is absolute, sandbox off, class set", () => {
  const l = d.execLine("/x/g.AppImage");
  assert.ok(l.startsWith('Exec="/x/g.AppImage"'), l);
  assert.ok(l.includes("--no-sandbox"));
  assert.ok(l.includes("--class=grog"));
  assert.ok(l.endsWith("%U"));
});
check("entry has the keys a launcher needs", () => {
  const e = d.entryContents("/x/g.AppImage");
  assert.ok(e.includes("Name=grog"));
  assert.ok(e.includes("Icon=grog"));
  assert.ok(e.includes("StartupWMClass=grog"));
  assert.ok(e.includes("Type=Application"));
  assert.ok(e.includes(d.MANAGED_KEY));
  assert.ok(e.includes('Exec="/x/g.AppImage"'));
});

// --- planning --------------------------------------------------------------
check("not an AppImage -> n/a", () => {
  const p = d.plan({ env: { XDG_DATA_HOME: data }, resourcesPath: res, configHome: home });
  assert.strictEqual(p.state, "n/a");
  assert.strictEqual(p.appImage, null);
});
check("no entry -> install", () => {
  const p = d.plan(OPTS);
  assert.strictEqual(p.state, "install");
  assert.strictEqual(p.desktopFile, path.join(data, "applications", "grog.desktop"));
  assert.strictEqual(p.iconDest, path.join(data, "icons", "hicolor", "512x512", "apps", "grog.png"));
  assert.strictEqual(p.declined, false);
});
check("install writes icon + entry", () => {
  const p = d.plan(OPTS);
  const w = d.install(p);
  assert.strictEqual(w.length, 2);
  assert.ok(fs.existsSync(p.iconDest));
  assert.ok(fs.readFileSync(p.desktopFile, "utf8").includes(`Exec="${APP}"`));
});
check("state is ok once installed and unmoved", () => {
  assert.strictEqual(d.plan(OPTS).state, "ok");
});
check("moved AppImage -> update, and install re-points it", () => {
  const moved = path.join(tmp, "elsewhere", "grog.AppImage");
  const p = d.plan(Object.assign({}, OPTS, { env: { APPIMAGE: moved, XDG_DATA_HOME: data } }));
  assert.strictEqual(p.state, "update");
  assert.strictEqual(p.previousProgram, APP, "should report the copy being replaced");
  d.install(p);
  const txt = fs.readFileSync(p.desktopFile, "utf8");
  assert.ok(txt.includes(`Exec="${moved}"`), txt.split("\n").find((l) => l.startsWith("Exec=")));
  assert.ok(!txt.includes(APP + '"'));
  assert.strictEqual(d.plan(Object.assign({}, OPTS, { env: { APPIMAGE: moved, XDG_DATA_HOME: data } })).state, "ok");
});
check("an OLDER grog entry (no marker, elsewhere) is taken over", () => {
  const data4 = path.join(tmp, "data4");
  const oldApp = path.join(tmp, "old", "grog-0.1.0-x86_64.AppImage");
  fs.mkdirSync(path.join(data4, "applications"), { recursive: true });
  // what a previous grog version wrote: no X-Grog-Managed, Exec=AppRun
  fs.writeFileSync(
    path.join(data4, "applications", "grog.desktop"),
    ["[Desktop Entry]", "Name=grog", "Exec=AppRun --no-sandbox %U", "Icon=grog-web", "Type=Application", ""].join("\n")
  );
  const p = d.plan({ env: { APPIMAGE: oldApp, XDG_DATA_HOME: data4 }, resourcesPath: res, configHome: home });
  assert.strictEqual(p.state, "update");
  assert.deepStrictEqual(d.install(p).length, 2);
  const txt = fs.readFileSync(p.desktopFile, "utf8");
  assert.ok(txt.includes(`Exec="${oldApp}"`), txt.split("\n").find((l) => l.startsWith("Exec=")));
  assert.ok(txt.includes(d.MANAGED_KEY), "upgrade should add the marker");
  assert.strictEqual(
    d.plan({ env: { APPIMAGE: oldApp, XDG_DATA_HOME: data4 }, resourcesPath: res, configHome: home }).state,
    "ok"
  );
});
check("a grog.desktop that is NOT grog's is left alone", () => {
  const data2 = path.join(tmp, "data2");
  fs.mkdirSync(path.join(data2, "applications"), { recursive: true });
  fs.writeFileSync(path.join(data2, "applications", "grog.desktop"), "[Desktop Entry]\nName=something-else\n");
  const p = d.plan({ env: { APPIMAGE: APP, XDG_DATA_HOME: data2 }, resourcesPath: res, configHome: home });
  assert.strictEqual(p.state, "foreign");
  assert.throws(() => d.install(p), /refusing/);
  assert.strictEqual(fs.readFileSync(path.join(data2, "applications", "grog.desktop"), "utf8"), "[Desktop Entry]\nName=something-else\n");
});
check("decline writes a marker; plan reports it", () => {
  const p = d.plan(Object.assign({}, OPTS, { configHome: path.join(tmp, "home2") }));
  assert.strictEqual(p.declined, false);
  d.decline(p);
  assert.ok(fs.existsSync(p.marker));
  assert.strictEqual(d.plan(Object.assign({}, OPTS, { configHome: path.join(tmp, "home2") })).declined, true);
});
check("missing icon source -> entry still written, no icon", () => {
  const noIcon = path.join(tmp, "res-noicon");
  fs.mkdirSync(noIcon, { recursive: true });
  const data3 = path.join(tmp, "data3");
  const p = d.plan({ env: { APPIMAGE: APP, XDG_DATA_HOME: data3 }, resourcesPath: noIcon, configHome: home });
  assert.strictEqual(p.iconSourceExists, false);
  const w = d.install(p);
  assert.deepStrictEqual(w, [p.desktopFile]);
});

// --- cache refresh ---------------------------------------------------------
check("refresh only runs tools that exist", () => {
  const ran = [];
  const out = d.refresh({
    env: { XDG_DATA_HOME: data },
    exists: (cmd) => cmd === "kbuildsycoca6" || cmd === "update-desktop-database",
    run: (cmd, args) => ran.push([cmd, args]),
  });
  assert.deepStrictEqual(out.sort(), ["kbuildsycoca6", "update-desktop-database"]);
  assert.deepStrictEqual(ran.map((r) => r[0]).sort(), ["kbuildsycoca6", "update-desktop-database"]);
  assert.ok(!ran.some((r) => r[0] === "kbuildsycoca5"));
});
check("refresh survives a tool that throws", () => {
  const out = d.refresh({
    env: { XDG_DATA_HOME: data },
    exists: () => true,
    run: () => { throw new Error("boom"); },
  });
  assert.ok(out.length >= 1);
});

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\ndesktop-install-test: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
