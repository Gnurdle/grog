// config-seed.js — the FIRST-RUN CONFIG OFFER.
//
// grog needs a config file. Without one it runs on built-in defaults, which is
// rarely what anyone wants: no model, no provider, no API key. The examples
// that make this easy ship INSIDE the app package (resources/config.examples/),
// which for an AppImage is a read-only squashfs mount a human cannot browse.
//
// So the client OFFERS to write them at startup, names the exact path, and
// reveals the folder. Deliberately NOT a silent copy: a config grog created
// behind your back still doesn't work (the model/provider/key are unset), so
// the user must know it exists, where it is, and that it needs editing before
// it will do anything.
//
// This module only plans and writes. Asking the user is the caller's job
// (main.js shows a native dialog).
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");

const MAIN_CONFIG = "grog.edn";
const EXAMPLE_SUFFIX = ".example";

/**
 * grog's config home. Mirrors `grog.platform/config-home-dir`:
 *   1. $GROG_CONFIG_HOME (absolute, or `~`-relative)
 *   2. ${XDG_CONFIG_HOME:-~/.config}/grog   (on every OS, Windows included)
 */
function configHome(env) {
  env = env || process.env;
  const raw = typeof env.GROG_CONFIG_HOME === "string" ? env.GROG_CONFIG_HOME.trim() : "";
  if (raw) {
    const expanded =
      raw === "~" ? os.homedir() : raw.startsWith("~/") ? path.join(os.homedir(), raw.slice(2)) : raw;
    return path.resolve(expanded);
  }
  const xdg = typeof env.XDG_CONFIG_HOME === "string" ? env.XDG_CONFIG_HOME.trim() : "";
  return path.join(xdg || path.join(os.homedir(), ".config"), "grog");
}

/**
 * Every `*.example` shipped with the app, sorted by name. `[]` when the package
 * has none (a dev tree that never ran `bb dist`, say).
 * Each entry: {name "grog.edn.example", target "grog.edn", from "<abs path>"}.
 */
function availableExamples(resourcesPath) {
  const dir = path.join(resourcesPath || process.resourcesPath, "config.examples");
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch (_e) {
    return [];
  }
  return names
    .filter((n) => n.endsWith(EXAMPLE_SUFFIX))
    .sort()
    .map((n) => ({
      name: n,
      target: n.slice(0, -EXAMPLE_SUFFIX.length),
      from: path.join(dir, n),
    }));
}

/**
 * What the first-run offer WOULD do. Reads the filesystem, writes nothing.
 *
 *   home        the config home (created only if the user accepts)
 *   grogEdn     <home>/grog.edn — the file the user must actually edit
 *   mainExample the shipped grog.edn.example, or nil
 *   needsMain   TRUE when grog.edn is absent and its example is available —
 *               this is the trigger for asking
 *   optional    the remaining examples (odoo/imap/gitlab/…), offered as
 *               `*.example` so an AppImage user can reach them at all
 */
function planOffer(opts) {
  const o = opts || {};
  const home = o.configHome || configHome(o.env);
  const examples = availableExamples(o.resourcesPath);
  const main = examples.find((e) => e.target === MAIN_CONFIG) || null;
  return {
    home,
    grogEdn: path.join(home, MAIN_CONFIG),
    mainExample: main,
    needsMain: !!main && !fs.existsSync(path.join(home, MAIN_CONFIG)),
    optional: examples.filter((e) => e.target !== MAIN_CONFIG),
  };
}

/**
 * Write `<home>/grog.edn` from the example, plus the optional examples as
 * `*.example`. NEVER overwrites an existing file. Returns the paths written
 * (so the caller can report them).
 */
function createConfig(plan) {
  const written = [];
  fs.mkdirSync(plan.home, { recursive: true });
  if (plan.mainExample && !fs.existsSync(plan.grogEdn)) {
    fs.copyFileSync(plan.mainExample.from, plan.grogEdn);
    written.push(plan.grogEdn);
  }
  for (const e of plan.optional) {
    const dest = path.join(plan.home, e.name);
    if (fs.existsSync(dest)) continue;
    fs.copyFileSync(e.from, dest);
    written.push(dest);
  }
  return written;
}

module.exports = { configHome, availableExamples, planOffer, createConfig };
